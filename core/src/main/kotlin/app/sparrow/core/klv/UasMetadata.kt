package app.sparrow.core.klv

data class LatLon(val lat: Double, val lon: Double)

/**
 * One metadata sample, already converted to ST 0601 quantities. Null fields are omitted from
 * the packet: there is no fix, no ground intersection, or the value is unknown (KLV-3, KLV-6).
 */
data class UasMetadata(
    /** Microseconds since 1970-01-01T00:00:00Z (ST 0603 Precision Time Stamp). */
    val timestampUtcMicros: Long,
    val missionId: String? = null,
    val platformDesignation: String? = null,
    val imageSourceSensor: String? = null,
    val callSign: String? = null,
    val platformHeading: Double? = null,
    val platformPitch: Double? = null,
    val platformRoll: Double? = null,
    val sensorLatitude: Double? = null,
    val sensorLongitude: Double? = null,
    /** Metres above mean sea level. */
    val sensorTrueAltitude: Double? = null,
    /** Metres above the WGS84 ellipsoid. */
    val sensorEllipsoidHeight: Double? = null,
    val horizontalFov: Double? = null,
    val verticalFov: Double? = null,
    val sensorRelativeAzimuth: Double? = null,
    val sensorRelativeElevation: Double? = null,
    val sensorRelativeRoll: Double? = null,
    val slantRange: Double? = null,
    val frameCenter: LatLon? = null,
    /** Metres above mean sea level. */
    val frameCenterElevation: Double? = null,
    /** Upper-left, upper-right, lower-right, lower-left, as ST 0601 numbers corners 1–4. */
    val corners: List<LatLon>? = null,
    /** Encoded ST 0903 VMTI Local Set for Tag 74. */
    val vmti: ByteArray? = null,
)
