package app.sparrow.core.geo

import app.sparrow.core.klv.LatLon
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Ground footprint of the image; any part is null when its ray misses the ground. */
data class Footprint(
    val center: LatLon?,
    val slantRange: Double?,
    /** Upper-left, upper-right, lower-right, lower-left; null unless all four hit the ground. */
    val corners: List<LatLon>?,
)

/**
 * Flat-earth intersection of image rays with a horizontal plane at a fixed ground elevation
 * (KLV-3). Good to a few metres for the short ranges a hand-held or mast camera sees.
 */
object FrameGeometry {
    private const val WGS84_A = 6378137.0
    private const val WGS84_E2 = 6.69437999014e-3

    /** Rays this close to the horizon (or above it) are treated as missing the ground. */
    private const val MIN_DOWN_COMPONENT = 1e-3

    fun footprint(
        sensor: LatLon,
        sensorAltitude: Double,
        groundElevation: Double,
        pose: CameraPose,
        horizontalFovDeg: Double,
        verticalFovDeg: Double,
    ): Footprint {
        val height = sensorAltitude - groundElevation
        if (height <= 0) return Footprint(null, null, null)

        val tanH = tan(Math.toRadians(horizontalFovDeg / 2))
        val tanV = tan(Math.toRadians(verticalFovDeg / 2))
        val f = pose.forward
        val r = pose.right
        val u = pose.up

        fun hit(ray: Vec3): Vec3? {
            val d = ray.normalized()
            if (d.z > -MIN_DOWN_COMPONENT) return null
            return d * (height / -d.z)
        }

        val centerHit = hit(f)
        val corners = listOf(
            f - r * tanH + u * tanV, // upper-left
            f + r * tanH + u * tanV, // upper-right
            f + r * tanH - u * tanV, // lower-right
            f - r * tanH - u * tanV, // lower-left
        ).map { hit(it) }

        return Footprint(
            center = centerHit?.let { offset(sensor, it) },
            slantRange = centerHit?.length,
            corners = if (corners.all { it != null }) corners.map { offset(sensor, it!!) } else null,
        )
    }

    /**
     * Ground point seen at normalised image position ([nx], [ny]) (0..1, y down), or null if that
     * ray does not reach the ground. Used to place detected targets.
     */
    fun groundPoint(
        sensor: LatLon,
        sensorAltitude: Double,
        groundElevation: Double,
        pose: CameraPose,
        horizontalFovDeg: Double,
        verticalFovDeg: Double,
        nx: Double,
        ny: Double,
    ): LatLon? {
        val height = sensorAltitude - groundElevation
        if (height <= 0) return null
        val tanH = tan(Math.toRadians(horizontalFovDeg / 2))
        val tanV = tan(Math.toRadians(verticalFovDeg / 2))
        val ray = (pose.forward + pose.right * ((2 * nx - 1) * tanH) - pose.up * ((2 * ny - 1) * tanV)).normalized()
        if (ray.z > -MIN_DOWN_COMPONENT) return null
        return offset(sensor, ray * (height / -ray.z))
    }

    /** Moves [origin] by an East-North offset in metres on the WGS84 ellipsoid. */
    fun offset(origin: LatLon, enu: Vec3): LatLon {
        val lat = Math.toRadians(origin.lat)
        val s = sin(lat)
        val w = sqrt(1 - WGS84_E2 * s * s)
        val meridian = WGS84_A * (1 - WGS84_E2) / (w * w * w)
        val primeVertical = WGS84_A / w
        val dLat = Math.toDegrees(enu.y / meridian)
        val dLon = Math.toDegrees(enu.x / (primeVertical * cos(lat)))
        var lon = origin.lon + dLon
        if (lon > 180) lon -= 360
        if (lon < -180) lon += 360
        return LatLon((origin.lat + dLat).coerceIn(-90.0, 90.0), lon)
    }

    /**
     * Field of view of a lens, degrees, for a sensor dimension in mm (after any crop to the
     * output aspect ratio) and a focal length in mm, divided by the digital zoom ratio.
     */
    fun fieldOfView(sensorSizeMm: Double, focalLengthMm: Double, zoomRatio: Double = 1.0): Double =
        Math.toDegrees(2 * kotlin.math.atan(sensorSizeMm / (2 * focalLengthMm * zoomRatio)))
}
