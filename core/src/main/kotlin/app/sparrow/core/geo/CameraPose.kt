package app.sparrow.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class LensFacing { BACK, FRONT, EXTERNAL }

/**
 * Orientation of the camera image in the world (East-North-Up) frame.
 *
 * @property forward boresight, the direction the camera looks
 * @property up direction of the top row of the encoded (unrotated sensor) image
 */
data class CameraPose(val forward: Vec3, val up: Vec3) {
    /** Direction of the right-hand column of the encoded image. */
    val right: Vec3 get() = (forward cross up).normalized()

    /** Degrees clockwise from true north, 0..360. */
    val azimuthDeg: Double
        get() = Math.toDegrees(atan2(forward.x, forward.y)).let { if (it < 0) it + 360 else it }

    /** Degrees above the horizon, −90..90. */
    val elevationDeg: Double get() = Math.toDegrees(asin(forward.z.coerceIn(-1.0, 1.0)))

    /**
     * Rotation of the image about the boresight, degrees, positive clockwise when looking along
     * the boresight. Zero when the image top points as far up as the boresight allows.
     */
    val rollDeg: Double
        get() {
            val horizontalRight = (forward cross Vec3.Z).let {
                if (it.length < 1e-9) Vec3.X else it.normalized() // looking straight up/down
            }
            val levelUp = (horizontalRight cross forward).normalized()
            return Math.toDegrees(atan2(up dot horizontalRight, up dot levelUp))
        }

    /**
     * Pose of a virtual camera rotated by [correction] relative to this one (both in this pose's
     * image frame: x right, y down, z forward), e.g. after stabilisation.
     */
    fun corrected(correction: app.sparrow.core.stab.Quat): CameraPose {
        fun toWorld(v: Vec3) = right * v.x - up * v.y + forward * v.z
        return CameraPose(toWorld(correction.rotate(Vec3.Z)).normalized(), (-toWorld(correction.rotate(Vec3.Y))).normalized())
    }

    /** The same camera with its image turned clockwise by [degrees] (sensor → stream image). */
    fun rotatedImage(degrees: Int): CameraPose {
        val r = Math.toRadians(degrees.toDouble())
        return CameraPose(forward, (up * cos(r) - right * sin(r)).normalized())
    }

    /** Image axes (x right, y down, z forward) in device coordinates. */
    data class Axes(val x: Vec3, val y: Vec3, val z: Vec3)

    companion object {
        fun cameraAxesInDevice(facing: LensFacing, sensorOrientationDeg: Int): Axes {
            val p = fromDevice(Mat3.IDENTITY, facing, sensorOrientationDeg)
            return Axes(p.right, -p.up, p.forward)
        }

        /**
         * Builds the pose from the device rotation matrix (device → ENU, from the rotation vector
         * sensor, corrected to true north) and the Camera2 characteristics of the lens.
         *
         * Device axes: X right, Y up and Z out of the screen, in the natural orientation.
         * [sensorOrientationDeg] is Camera2 SENSOR_ORIENTATION: the clockwise rotation that makes
         * the sensor image upright in the natural orientation.
         */
        fun fromDevice(rotation: Mat3, facing: LensFacing, sensorOrientationDeg: Int): CameraPose {
            val forwardDevice = if (facing == LensFacing.FRONT) Vec3.Z else -Vec3.Z
            val viewerUp = Vec3.Y
            val viewerRight = (forwardDevice cross viewerUp).normalized()
            val theta = Math.toRadians(sensorOrientationDeg.toDouble())
            // The sensor's top row ends up at angle θ clockwise from "up" once rotated upright.
            val upDevice = viewerUp * cos(theta) + viewerRight * sin(theta)
            return CameraPose((rotation * forwardDevice).normalized(), (rotation * upDevice).normalized())
        }

        /** Pose from azimuth/elevation/roll in degrees (inverse of the getters). */
        fun fromAngles(azimuthDeg: Double, elevationDeg: Double, rollDeg: Double): CameraPose {
            val az = Math.toRadians(azimuthDeg)
            val el = Math.toRadians(elevationDeg)
            val roll = Math.toRadians(rollDeg)
            val forward = Vec3(sin(az) * cos(el), cos(az) * cos(el), sin(el))
            val horizontalRight = Vec3(cos(az), -sin(az), 0.0)
            val levelUp = (horizontalRight cross forward).normalized()
            val up = levelUp * cos(roll) + horizontalRight * sin(roll)
            return CameraPose(forward, up)
        }
    }
}
