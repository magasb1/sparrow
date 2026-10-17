package app.sparrow.core.cv

import app.sparrow.core.geo.CameraPose
import app.sparrow.core.geo.FrameGeometry
import app.sparrow.core.klv.LatLon
import app.sparrow.core.klv.VmtiFrame
import app.sparrow.core.klv.VmtiTarget
import kotlin.math.roundToInt

/** Where the camera was for one frame, to geolocate targets. All optional. */
data class TargetGeometry(
    val sensor: LatLon?,
    /** Metres MSL (or HAE if MSL is unknown), the same datum as [groundElevation]. */
    val sensorAltitude: Double?,
    val groundElevation: Double,
    /** Ellipsoid height minus MSL height at the sensor, when both are known. */
    val geoidSeparation: Double?,
    val pose: CameraPose?,
    val horizontalFov: Double?,
    val verticalFov: Double?,
)

/** Builds the ST 0903 VMTI frame for the confirmed tracks (KLV Tag 74). */
object TargetReport {
    fun vmti(
        tracks: List<TrackedObject>,
        totalDetected: Int,
        frameWidth: Int,
        frameHeight: Int,
        geometry: TargetGeometry,
        maxTargets: Int = 20,
    ): VmtiFrame {
        val reported = tracks.take(maxTargets).map { t ->
            val location = locate(t.box, geometry)
            VmtiTarget(
                id = t.id,
                left = (t.box.x1 * frameWidth).roundToInt(),
                top = (t.box.y1 * frameHeight).roundToInt(),
                right = (t.box.x2 * frameWidth).roundToInt() - 1,
                bottom = (t.box.y2 * frameHeight).roundToInt() - 1,
                confidence = (t.score * 100).roundToInt(),
                history = t.hits - 1,
                className = CocoClasses.name(t.classId),
                location = location,
                locationHae = location?.let { geometry.groundElevation + (geometry.geoidSeparation ?: 0.0) },
            )
        }
        return VmtiFrame(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            horizontalFov = geometry.horizontalFov,
            verticalFov = geometry.verticalFov,
            totalTargets = maxOf(totalDetected, reported.size),
            targets = reported,
        )
    }

    /** Ground position of the box's bottom centre, where the object meets the ground. */
    fun locate(box: Box, g: TargetGeometry): LatLon? {
        val sensor = g.sensor ?: return null
        val alt = g.sensorAltitude ?: return null
        val pose = g.pose ?: return null
        val h = g.horizontalFov ?: return null
        val v = g.verticalFov ?: return null
        return FrameGeometry.groundPoint(sensor, alt, g.groundElevation, pose, h, v, box.cx.toDouble(), box.y2.toDouble())
    }
}
