package app.sparrow.core.klv

import kotlin.math.roundToLong

/**
 * MISB ST 0601 UAS Datalink Local Set: the subset of tags Sparrow produces.
 *
 * Integer mappings follow ST 0601: unsigned values map [min, max] onto 0..(2^n - 1); signed
 * values map the symmetric range onto ±(2^(n-1) - 1), keeping the most negative integer as the
 * "out of range" indicator.
 */
object St0601 {
    /** 16-byte Universal Label of the UAS Datalink Local Set. */
    val UNIVERSAL_KEY: ByteArray = byteArrayOf(
        0x06, 0x0E, 0x2B, 0x34, 0x02, 0x0B, 0x01, 0x01,
        0x0E, 0x01, 0x03, 0x01, 0x01, 0x00, 0x00, 0x00,
    )

    /** ST 0601 revision announced in Tag 65 unless the profile overrides it. */
    const val DEFAULT_VERSION = 19

    const val MAX_STRING_BYTES = 127
}

sealed interface TagFormat {
    /** Fixed-length unsigned integer mapped linearly from [min, max]. */
    data class UMapped(val bytes: Int, val min: Double, val max: Double) : TagFormat

    /** Fixed-length two's complement integer mapped from the symmetric range ±[span]/2. */
    data class SMapped(val bytes: Int, val span: Double) : TagFormat

    /** Raw unsigned integer (time stamps, version, checksum). */
    data class UInt(val bytes: Int) : TagFormat

    /** ISO 646 / UTF-8 text, up to 127 bytes. */
    data object Text : TagFormat
}

/** Tags Sparrow can emit. [optional] tags can be switched off per profile (KLV-5). */
enum class Tag(val id: Int, val label: String, val format: TagFormat, val optional: Boolean, val unit: String = "") {
    CHECKSUM(1, "Checksum", TagFormat.UInt(2), optional = false),
    PRECISION_TIME_STAMP(2, "Precision Time Stamp", TagFormat.UInt(8), optional = false, unit = "µs"),
    MISSION_ID(3, "Mission ID", TagFormat.Text, optional = true),
    PLATFORM_HEADING(5, "Platform Heading Angle", TagFormat.UMapped(2, 0.0, 360.0), optional = true, unit = "°"),
    PLATFORM_PITCH(6, "Platform Pitch Angle", TagFormat.SMapped(2, 40.0), optional = true, unit = "°"),
    PLATFORM_ROLL(7, "Platform Roll Angle", TagFormat.SMapped(2, 100.0), optional = true, unit = "°"),
    PLATFORM_DESIGNATION(10, "Platform Designation", TagFormat.Text, optional = true),
    IMAGE_SOURCE_SENSOR(11, "Image Source Sensor", TagFormat.Text, optional = true),
    SENSOR_LATITUDE(13, "Sensor Latitude", TagFormat.SMapped(4, 180.0), optional = true, unit = "°"),
    SENSOR_LONGITUDE(14, "Sensor Longitude", TagFormat.SMapped(4, 360.0), optional = true, unit = "°"),
    SENSOR_TRUE_ALTITUDE(15, "Sensor True Altitude", TagFormat.UMapped(2, -900.0, 19000.0), optional = true, unit = "m"),
    SENSOR_HFOV(16, "Sensor Horizontal FOV", TagFormat.UMapped(2, 0.0, 180.0), optional = true, unit = "°"),
    SENSOR_VFOV(17, "Sensor Vertical FOV", TagFormat.UMapped(2, 0.0, 180.0), optional = true, unit = "°"),
    SENSOR_REL_AZIMUTH(18, "Sensor Relative Azimuth", TagFormat.UMapped(4, 0.0, 360.0), optional = true, unit = "°"),
    SENSOR_REL_ELEVATION(19, "Sensor Relative Elevation", TagFormat.SMapped(4, 360.0), optional = true, unit = "°"),
    SENSOR_REL_ROLL(20, "Sensor Relative Roll", TagFormat.UMapped(4, 0.0, 360.0), optional = true, unit = "°"),
    SLANT_RANGE(21, "Slant Range", TagFormat.UMapped(4, 0.0, 5_000_000.0), optional = true, unit = "m"),
    FRAME_CENTER_LATITUDE(23, "Frame Center Latitude", TagFormat.SMapped(4, 180.0), optional = true, unit = "°"),
    FRAME_CENTER_LONGITUDE(24, "Frame Center Longitude", TagFormat.SMapped(4, 360.0), optional = true, unit = "°"),
    FRAME_CENTER_ELEVATION(25, "Frame Center Elevation", TagFormat.UMapped(2, -900.0, 19000.0), optional = true, unit = "m"),
    PLATFORM_CALL_SIGN(59, "Platform Call Sign", TagFormat.Text, optional = true),
    UAS_LS_VERSION(65, "UAS Datalink LS Version", TagFormat.UInt(1), optional = false),
    SENSOR_ELLIPSOID_HEIGHT(75, "Sensor Ellipsoid Height", TagFormat.UMapped(2, -900.0, 19000.0), optional = true, unit = "m"),
    CORNER_LAT_1(82, "Corner Latitude Point 1", TagFormat.SMapped(4, 180.0), optional = true, unit = "°"),
    CORNER_LON_1(83, "Corner Longitude Point 1", TagFormat.SMapped(4, 360.0), optional = true, unit = "°"),
    CORNER_LAT_2(84, "Corner Latitude Point 2", TagFormat.SMapped(4, 180.0), optional = true, unit = "°"),
    CORNER_LON_2(85, "Corner Longitude Point 2", TagFormat.SMapped(4, 360.0), optional = true, unit = "°"),
    CORNER_LAT_3(86, "Corner Latitude Point 3", TagFormat.SMapped(4, 180.0), optional = true, unit = "°"),
    CORNER_LON_3(87, "Corner Longitude Point 3", TagFormat.SMapped(4, 360.0), optional = true, unit = "°"),
    CORNER_LAT_4(88, "Corner Latitude Point 4", TagFormat.SMapped(4, 180.0), optional = true, unit = "°"),
    CORNER_LON_4(89, "Corner Longitude Point 4", TagFormat.SMapped(4, 360.0), optional = true, unit = "°");

    companion object {
        private val byId = entries.associateBy { it.id }
        fun of(id: Int): Tag? = byId[id]
        val optionalTags: List<Tag> = entries.filter { it.optional }
    }
}

/** Linear integer mappings shared by the encoder and decoder. */
object TagMapping {
    fun encodeUnsigned(f: TagFormat.UMapped, value: Double): Long {
        val maxInt = (1L shl (8 * f.bytes)) - 1
        val clamped = value.coerceIn(f.min, f.max)
        return ((clamped - f.min) * maxInt / (f.max - f.min)).roundToLong().coerceIn(0, maxInt)
    }

    fun decodeUnsigned(f: TagFormat.UMapped, raw: Long): Double {
        val maxInt = (1L shl (8 * f.bytes)) - 1
        return f.min + raw.toDouble() * (f.max - f.min) / maxInt
    }

    fun encodeSigned(f: TagFormat.SMapped, value: Double): Long {
        val limit = (1L shl (8 * f.bytes - 1)) - 1
        val half = f.span / 2
        val clamped = value.coerceIn(-half, half)
        val v = (clamped * (2 * limit) / f.span).roundToLong().coerceIn(-limit, limit)
        // Two's complement in f.bytes bytes (signed tags are at most 4 bytes).
        return v and ((1L shl (8 * f.bytes)) - 1)
    }

    /** Returns null for the reserved "out of range" value. */
    fun decodeSigned(f: TagFormat.SMapped, raw: Long): Double? {
        val bits = 8 * f.bytes
        val signed = (raw shl (64 - bits)) shr (64 - bits)
        val limit = (1L shl (bits - 1)) - 1
        if (signed == -limit - 1) return null
        return signed.toDouble() * f.span / (2 * limit)
    }
}
