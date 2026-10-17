package app.sparrow.core.klv

import app.sparrow.core.geo.CameraPose
import app.sparrow.core.geo.FrameGeometry

/** A GNSS fix. Altitudes are null when the receiver did not report them. */
data class PositionFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMsl: Double?,
    val ellipsoidHeight: Double?,
    /** Capture-clock time of the fix, microseconds. */
    val captureUs: Long,
)

/** Static tag values and geometry settings set in the UI (KLV-3, KLV-4). */
data class ComposerSettings(
    val missionId: String = "",
    val platformDesignation: String = "",
    val imageSourceSensor: String = "",
    val callSign: String = "",
    /** Ground elevation, metres MSL, for frame center and corners. */
    val groundElevation: Double = 0.0,
    /** A fix older than this is stale and its position tags are omitted (KLV-6). */
    val maxFixAgeUs: Long = 5_000_000,
)

/**
 * Turns one sensor snapshot into ST 0601 values.
 *
 * A phone has no separate gimbal, so the platform frame is chosen to make the angles exact and in
 * range: platform heading = camera azimuth, platform pitch and roll = 0, and the camera's
 * elevation and roll go in the sensor-relative tags. Composing platform and relative angles, as
 * receivers do, gives back the true camera attitude.
 */
class MetadataComposer(private val settings: ComposerSettings) {
    fun compose(
        timestampUtcMicros: Long,
        captureUs: Long,
        fix: PositionFix?,
        pose: CameraPose?,
        horizontalFovDeg: Double?,
        verticalFovDeg: Double?,
    ): UasMetadata {
        val freshFix = fix?.takeIf { captureUs - it.captureUs <= settings.maxFixAgeUs }
        val altitude = freshFix?.let { it.altitudeMsl ?: it.ellipsoidHeight }
        val footprint = if (freshFix != null && altitude != null && pose != null &&
            horizontalFovDeg != null && verticalFovDeg != null
        ) {
            FrameGeometry.footprint(
                LatLon(freshFix.latitude, freshFix.longitude), altitude, settings.groundElevation,
                pose, horizontalFovDeg, verticalFovDeg,
            )
        } else null

        return UasMetadata(
            timestampUtcMicros = timestampUtcMicros,
            missionId = settings.missionId.ifBlank { null },
            platformDesignation = settings.platformDesignation.ifBlank { null },
            imageSourceSensor = settings.imageSourceSensor.ifBlank { null },
            callSign = settings.callSign.ifBlank { null },
            platformHeading = pose?.azimuthDeg,
            platformPitch = pose?.let { 0.0 },
            platformRoll = pose?.let { 0.0 },
            sensorLatitude = freshFix?.latitude,
            sensorLongitude = freshFix?.longitude,
            sensorTrueAltitude = freshFix?.altitudeMsl,
            sensorEllipsoidHeight = freshFix?.ellipsoidHeight,
            horizontalFov = horizontalFovDeg,
            verticalFov = verticalFovDeg,
            sensorRelativeAzimuth = pose?.let { 0.0 },
            sensorRelativeElevation = pose?.elevationDeg,
            sensorRelativeRoll = pose?.rollDeg?.let { if (it < 0) it + 360 else it },
            slantRange = footprint?.slantRange,
            frameCenter = footprint?.center,
            frameCenterElevation = footprint?.center?.let { settings.groundElevation },
            corners = footprint?.corners,
        )
    }
}
