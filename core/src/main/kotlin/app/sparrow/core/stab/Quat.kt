package app.sparrow.core.stab

import app.sparrow.core.geo.Vec3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit quaternion (w, x, y, z) for rotations; [rotate] applies it to a vector. */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate() = Quat(w, -x, -y, -z)

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return Quat(w / n, x / n, y / n, z / n)
    }

    fun rotate(v: Vec3): Vec3 {
        val q = this * Quat(0.0, v.x, v.y, v.z) * conjugate()
        return Vec3(q.x, q.y, q.z)
    }

    /** Rotation angle in radians, 0..π. */
    val angle: Double get() = 2 * acos(kotlin.math.abs(w).coerceAtMost(1.0))

    /** Row-major 3×3 rotation matrix. */
    fun toMatrix(): DoubleArray = doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
    )

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun axisAngle(axis: Vec3, radians: Double): Quat {
            val a = axis.normalized()
            val s = sin(radians / 2)
            return Quat(cos(radians / 2), a.x * s, a.y * s, a.z * s)
        }

        /** Rotation by angular velocity [w] (rad/s, body frame) over [dt] seconds. */
        fun fromRate(w: Vec3, dt: Double): Quat {
            val rate = w.length
            return if (rate * dt < 1e-12) IDENTITY else axisAngle(w, rate * dt)
        }

        /** Spherical interpolation from [a] (t = 0) to [b] (t = 1) along the shorter arc. */
        fun slerp(a: Quat, b0: Quat, t: Double): Quat {
            var b = b0
            var dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            if (dot < 0) {
                b = Quat(-b.w, -b.x, -b.y, -b.z); dot = -dot
            }
            if (dot > 0.9995) {
                return Quat(a.w + t * (b.w - a.w), a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.z + t * (b.z - a.z)).normalized()
            }
            val theta = acos(dot)
            val sa = sin((1 - t) * theta) / sin(theta)
            val sb = sin(t * theta) / sin(theta)
            return Quat(sa * a.w + sb * b.w, sa * a.x + sb * b.x, sa * a.y + sb * b.y, sa * a.z + sb * b.z)
        }
    }
}
