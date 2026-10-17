package app.sparrow.core.stab

import app.sparrow.core.geo.CameraPose
import app.sparrow.core.geo.LensFacing
import app.sparrow.core.geo.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan

/**
 * How one frame is re-rendered: [homography] maps an output point (s, t) — normalised stream
 * image coordinates, y down — to the sensor image point (x, y) to sample, as a row-major 3×3
 * projective matrix. [correction] rotates virtual-camera vectors into the real camera frame.
 */
data class FrameTransform(val correction: Quat, val homography: DoubleArray, val cropScale: Double) {
    override fun equals(other: Any?) = other is FrameTransform && other.correction == correction && other.cropScale == cropScale
    override fun hashCode() = correction.hashCode()
}

/**
 * Gyroscope video stabilisation with optional horizon levelling.
 *
 * The camera's orientation is integrated from the gyroscope in the sensor-image frame (x right,
 * y down, z forward). A virtual camera follows it through a low-pass filter (time constant
 * [smoothingS]); each frame is re-rendered from the virtual camera, zoomed in by [cropScale] so
 * the correction never shows the image edge. If a correction would show it anyway, it is reduced
 * and the filter follows, so fast pans pass through with a little lag. With [level] the virtual
 * camera is also rolled so the horizon stays level in the streamed image.
 *
 * @param rotationDeg clockwise rotation from the sensor image to the stream image
 */
class Stabilizer(
    private val facing: LensFacing,
    private val sensorOrientationDeg: Int,
    private val rotationDeg: Int,
    /** Sensor-orientation FOV of the camera buffer, degrees. */
    private val horizontalFovDeg: Double,
    private val verticalFovDeg: Double,
    private val level: Boolean,
    val cropScale: Double = if (level) 1.2 else 1.1,
    private val smoothingS: Double = 0.5,
) {
    private val axes = CameraPose.cameraAxesInDevice(facing, sensorOrientationDeg)
    private val times = LongArray(HISTORY)
    private val orientations = arrayOfNulls<Quat>(HISTORY)
    private var head = 0
    private var count = 0
    private var orientation = Quat.IDENTITY
    private var lastGyroUs = Long.MIN_VALUE

    private var smooth: Quat? = null
    private var lastFrameUs = Long.MIN_VALUE
    private val applied = arrayOfNulls<Pair<Long, Quat>>(64)
    private var appliedHead = 0

    private var fx = 0.5 / tan(Math.toRadians(horizontalFovDeg / 2)) // focal length, normalised to width
    private var fy = 0.5 / tan(Math.toRadians(verticalFovDeg / 2))   // ... and to height

    /** New sensor-orientation FOV, e.g. after a zoom change. */
    @Synchronized
    fun setFieldOfView(horizontalDeg: Double, verticalDeg: Double) {
        fx = 0.5 / tan(Math.toRadians(horizontalDeg / 2))
        fy = 0.5 / tan(Math.toRadians(verticalDeg / 2))
    }

    /** Gyroscope sample: angular rate (rad/s) in the device frame, at [timeUs] on the capture clock. */
    @Synchronized
    fun onGyro(timeUs: Long, wx: Float, wy: Float, wz: Float) {
        if (lastGyroUs != Long.MIN_VALUE) {
            val dt = (timeUs - lastGyroUs) / 1e6
            if (dt in 0.0..0.1) {
                val w = Vec3(wx.toDouble(), wy.toDouble(), wz.toDouble())
                val wCam = Vec3(w dot axes.x, w dot axes.y, w dot axes.z)
                orientation = (orientation * Quat.fromRate(wCam, dt)).normalized()
            }
        }
        lastGyroUs = timeUs
        times[head] = timeUs
        orientations[head] = orientation
        head = (head + 1) % HISTORY
        if (count < HISTORY) count++
    }

    /** Camera orientation at [timeUs], interpolated between gyroscope samples. */
    private fun orientationAt(timeUs: Long): Quat {
        if (count == 0) return Quat.IDENTITY
        var newer = -1
        var older = -1
        for (k in 0 until count) {
            val i = (head - 1 - k + HISTORY) % HISTORY
            if (times[i] >= timeUs) newer = i else {
                older = i; break
            }
        }
        return when {
            newer == -1 -> orientations[older]!! // frame is newer than all samples: hold the latest
            older == -1 -> orientations[newer]!!
            else -> {
                val span = (times[newer] - times[older]).toDouble()
                Quat.slerp(orientations[older]!!, orientations[newer]!!, if (span > 0) (timeUs - times[older]) / span else 0.0)
            }
        }
    }

    /**
     * Transform for the frame captured at [timeUs]. Call once per frame, in order.
     * [sensorPose] (the real camera's pose in sensor-image orientation) is needed for levelling.
     */
    @Synchronized
    fun frame(timeUs: Long, sensorPose: CameraPose?): FrameTransform {
        val raw = orientationAt(timeUs)
        val previous = smooth
        var s = if (previous == null || lastFrameUs == Long.MIN_VALUE) raw else {
            val dt = ((timeUs - lastFrameUs) / 1e6).coerceIn(0.0, 0.5)
            Quat.slerp(previous, raw, 1 - exp(-dt / smoothingS))
        }
        lastFrameUs = timeUs
        var c = raw.conjugate() * s
        if (level && sensorPose != null) c = c * levelRoll(c, sensorPose)
        val fitted = fit(c)
        if (fitted != c) {
            // Let the virtual camera follow: the next frames start from what was shown.
            s = raw * fitted
        }
        smooth = s
        applied[appliedHead] = timeUs to fitted
        appliedHead = (appliedHead + 1) % applied.size
        return FrameTransform(fitted, homography(fitted), cropScale)
    }

    /** Correction applied to the frame nearest [timeUs] (for metadata), identity if unknown. */
    @Synchronized
    fun correctionAt(timeUs: Long): Quat =
        applied.filterNotNull().minByOrNull { kotlin.math.abs(it.first - timeUs) }?.second ?: Quat.IDENTITY

    /**
     * Roll about the virtual camera's axis that turns world "up" onto the stream image's up
     * direction (the sensor image's −y turned by the stream rotation).
     */
    private fun levelRoll(c: Quat, sensorPose: CameraPose): Quat {
        val worldUpRaw = Vec3(sensorPose.right.z, -sensorPose.up.z, sensorPose.forward.z) // world Z in camera axes
        val u = c.conjugate().rotate(worldUpRaw) // ... in the virtual camera
        if (u.x * u.x + u.y * u.y < 1e-6) return Quat.IDENTITY // looking straight up or down
        val r = Math.toRadians(rotationDeg.toDouble())
        val beta = atan2(u.y, u.x) - atan2(-cos(r), -sin(r))
        return Quat.axisAngle(Vec3.Z, beta)
    }

    /** Largest part of [c] (towards identity) that keeps the output inside the source image. */
    private fun fit(c: Quat): Quat {
        if (inside(c)) return c
        var lo = 0.0
        var hi = 1.0
        repeat(10) {
            val mid = (lo + hi) / 2
            if (inside(Quat.slerp(Quat.IDENTITY, c, mid))) lo = mid else hi = mid
        }
        return Quat.slerp(Quat.IDENTITY, c, lo)
    }

    private fun inside(c: Quat): Boolean {
        val h = homography(c)
        for (s in doubleArrayOf(0.0, 1.0)) for (t in doubleArrayOf(0.0, 1.0)) {
            val z = h[6] * s + h[7] * t + h[8]
            if (z <= 0) return false
            val x = (h[0] * s + h[1] * t + h[2]) / z
            val y = (h[3] * s + h[4] * t + h[5]) / z
            if (x < 0 || x > 1 || y < 0 || y > 1) return false
        }
        return true
    }

    /**
     * Output (s, t) in the stream image → sensor image (x, y): undo the stream rotation, unproject
     * with the cropped intrinsics, rotate by [c], project with the camera's intrinsics.
     */
    private fun homography(c: Quat): DoubleArray {
        val rot = streamToSensor(rotationDeg)
        // The output camera has a longer focal length (fx·crop): a narrower view inside the source.
        val ox = fx * cropScale
        val oy = fy * cropScale
        val kOutInv = doubleArrayOf(
            1 / ox, 0.0, -0.5 / ox,
            0.0, 1 / oy, -0.5 / oy,
            0.0, 0.0, 1.0,
        )
        val k = doubleArrayOf(fx, 0.0, 0.5, 0.0, fy, 0.5, 0.0, 0.0, 1.0)
        return mul(k, mul(c.toMatrix(), mul(kOutInv, rot)))
    }

    companion object {
        private const val HISTORY = 512

        /** Normalised stream (s, t, 1) → sensor (x, y, 1) for a clockwise stream rotation. */
        fun streamToSensor(rotationDeg: Int): DoubleArray = when (rotationDeg) {
            90 -> doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 1.0)
            180 -> doubleArrayOf(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0, 0.0, 0.0, 1.0)
            270 -> doubleArrayOf(0.0, -1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            else -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        }

        fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
            val r = i / 3
            val col = i % 3
            a[r * 3] * b[col] + a[r * 3 + 1] * b[3 + col] + a[r * 3 + 2] * b[6 + col]
        }

        /** FOV after the stabilisation crop. */
        fun croppedFov(fovDeg: Double, cropScale: Double): Double =
            Math.toDegrees(2 * kotlin.math.atan(tan(Math.toRadians(fovDeg / 2)) / cropScale))
    }
}
