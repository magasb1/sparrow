package app.sparrow.core.stab

import app.sparrow.core.geo.CameraPose
import app.sparrow.core.geo.LensFacing
import app.sparrow.core.geo.Mat3
import app.sparrow.core.geo.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StabilizerTest {
    /** Back camera, sensor orientation 90, landscape stream (rotation 0), 66°×39° FOV. */
    private fun stab(level: Boolean = false, rotation: Int = 0) = Stabilizer(LensFacing.BACK, 90, rotation, 66.0, 39.0, level)

    private fun apply(h: DoubleArray, s: Double, t: Double): Pair<Double, Double> {
        val z = h[6] * s + h[7] * t + h[8]
        return (h[0] * s + h[1] * t + h[2]) / z to (h[3] * s + h[4] * t + h[5]) / z
    }

    @Test
    fun `no motion gives the plain crop`() {
        val st = stab()
        for (i in 0..50) st.onGyro(i * 5_000L, 0f, 0f, 0f)
        val f = st.frame(200_000, null)
        assertEquals(0.0, f.correction.angle, 1e-9)
        val (cx, cy) = apply(f.homography, 0.5, 0.5)
        assertEquals(0.5, cx, 1e-9); assertEquals(0.5, cy, 1e-9)
        // Corners sample 1/1.1 of the image around the centre.
        val (x0, y0) = apply(f.homography, 0.0, 0.0)
        assertEquals(0.5 - 0.5 / 1.1, x0, 1e-6); assertEquals(0.5 - 0.5 / 1.1, y0, 1e-6)
    }

    @Test
    fun `hand shake is reduced`() {
        val st = stab()
        // 5 Hz tremor about the device X axis (pitch), ±0.6° amplitude.
        val amp = Math.toRadians(0.6)
        var t = 0L
        var maxRaw = 0.0
        var maxOut = 0.0
        val sensorAxes = CameraPose.cameraAxesInDevice(LensFacing.BACK, 90)
        for (frame in 0 until 150) {
            repeat(13) {
                val rate = amp * 2 * PI * 5 * kotlin.math.cos(2 * PI * 5 * t / 1e6)
                st.onGyro(t, rate.toFloat(), 0f, 0f)
                t += 5_000
            }
            val f = st.frame(t, null)
            if (frame > 30) {
                val rawAngle = amp * sin(2 * PI * 5 * t / 1e6)
                maxRaw = maxOf(maxRaw, kotlin.math.abs(rawAngle))
                // The virtual camera's deviation from the start is the shake left in the output.
                val virtual = f.correction.angle - kotlin.math.abs(rawAngle)
                maxOut = maxOf(maxOut, kotlin.math.abs(virtual))
            }
        }
        assertTrue(sensorAxes.z.length > 0.99)
        assertTrue("residual ${Math.toDegrees(maxOut)}° of ${Math.toDegrees(maxRaw)}°", maxOut < 0.3 * maxRaw)
    }

    @Test
    fun `a fast pan is limited to the crop margin and followed`() {
        val st = stab()
        var t = 0L
        var last: FrameTransform? = null
        for (frame in 0 until 60) {
            repeat(13) { st.onGyro(t, 0f, 1.0f, 0f); t += 5_000 } // 1 rad/s yaw
            last = st.frame(t, null)
            val h = last!!.homography
            for (s in listOf(0.0, 1.0)) for (u in listOf(0.0, 1.0)) {
                val (x, y) = apply(h, s, u)
                assertTrue("corner $x,$y outside", x in -1e-6..1.000001 && y in -1e-6..1.000001)
            }
        }
        // After 4 s of panning the virtual camera trails by at most the margin, not 4 rad.
        assertTrue(last!!.correction.angle < Math.toRadians(8.0))
    }

    @Test
    fun `levelling cancels a tilted phone`() {
        // Phone in landscape (top to the left) facing north, rolled 5° clockwise.
        val base = Mat3(doubleArrayOf(0.0, -1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0))
        val sensorPose = CameraPose.fromDevice(base, LensFacing.BACK, 90)
        val tilted = CameraPose(sensorPose.forward, (sensorPose.up * kotlin.math.cos(Math.toRadians(5.0)) + sensorPose.right * kotlin.math.sin(Math.toRadians(5.0))).normalized())
        assertEquals(5.0, tilted.rollDeg, 1e-6)
        val st = stab(level = true)
        st.onGyro(0, 0f, 0f, 0f)
        val f = st.frame(10_000, tilted)
        val out = tilted.corrected(f.correction)
        assertEquals(0.0, out.rollDeg, 0.05)
        assertEquals(tilted.azimuthDeg, out.azimuthDeg, 0.05)
    }

    @Test
    fun `levelling also works for a portrait stream`() {
        // Phone upright in portrait facing north, rolled 4°: stream rotation 90.
        val r = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0))
        val sensorPose = CameraPose.fromDevice(r, LensFacing.BACK, 90)
        val streamPose = sensorPose.rotatedImage(90)
        assertEquals(0.0, streamPose.rollDeg, 1e-6)
        val roll = Math.toRadians(4.0)
        val tiltedSensor = CameraPose(sensorPose.forward, (sensorPose.up * kotlin.math.cos(roll) + sensorPose.right * kotlin.math.sin(roll)).normalized())
        val st = stab(level = true, rotation = 90)
        st.onGyro(0, 0f, 0f, 0f)
        val f = st.frame(10_000, tiltedSensor)
        assertEquals(0.0, tiltedSensor.corrected(f.correction).rotatedImage(90).rollDeg, 0.05)
    }

    @Test
    fun `rotatedImage matches the device formula`() {
        val r = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0))
        val a = CameraPose.fromDevice(r, LensFacing.BACK, 90).rotatedImage(90)
        val b = CameraPose.fromDevice(r, LensFacing.BACK, 0)
        assertEquals(b.up.x, a.up.x, 1e-9); assertEquals(b.up.y, a.up.y, 1e-9); assertEquals(b.up.z, a.up.z, 1e-9)
    }

    @Test
    fun `gyro integration turns the correction the right way`() {
        // Landscape, top of the phone to the left (device +X is world up), facing north.
        // Turning left (towards west) is a positive rate about device X. The scene then drifts
        // right in the real image, so the steady virtual camera must sample right of centre.
        val st = Stabilizer(LensFacing.BACK, 90, 0, 66.0, 39.0, level = false, smoothingS = 1e9)
        var t = 0L
        st.onGyro(t, 0f, 0f, 0f)
        st.frame(t, null)
        val rate = Math.toRadians(1.0) / 0.1
        repeat(10) { t += 10_000; st.onGyro(t, rate.toFloat(), 0f, 0f) }
        val f = st.frame(t, null)
        assertEquals(1.0, Math.toDegrees(f.correction.angle), 0.02)
        val (x, y) = apply(f.homography, 0.5, 0.5)
        assertTrue("centre samples x=$x", x > 0.51)
        assertEquals(0.5, y, 1e-3)
        // And the metadata pose of the virtual camera still faces north (the real one is 1° west).
        val r = Mat3(doubleArrayOf(0.0, -1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0))
        val north = CameraPose.fromDevice(r, LensFacing.BACK, 90)
        val yaw = Quat.axisAngle(Vec3.Z, Math.toRadians(1.0)) // counter-clockwise from above: towards west
        val real = CameraPose(yaw.rotate(north.forward), yaw.rotate(north.up))
        assertEquals(359.0, real.azimuthDeg, 1e-6)
        val virtual = real.corrected(f.correction)
        assertEquals(0.0, ((virtual.azimuthDeg + 180) % 360) - 180, 0.02)
    }
}
