package app.sparrow.core.klv

import app.sparrow.core.util.ByteSink

/**
 * Builds ST 0601 Local Set packets: Tag 2 first, Tag 65 always, Tag 1 checksum last (KLV-1).
 *
 * @param enabledTags optional tags to include when a value is available (KLV-5). Mandatory
 *   tags are always written.
 */
class St0601Encoder(
    private val version: Int = St0601.DEFAULT_VERSION,
    enabledTags: Set<Tag> = Tag.optionalTags.toSet(),
) {
    private val enabled = enabledTags
    private val body = ByteSink(512)
    private val out = ByteSink(600)

    fun encode(m: UasMetadata): ByteArray {
        body.reset()
        raw(Tag.PRECISION_TIME_STAMP, m.timestampUtcMicros)
        text(Tag.MISSION_ID, m.missionId)
        num(Tag.PLATFORM_HEADING, m.platformHeading)
        num(Tag.PLATFORM_PITCH, m.platformPitch)
        num(Tag.PLATFORM_ROLL, m.platformRoll)
        text(Tag.PLATFORM_DESIGNATION, m.platformDesignation)
        text(Tag.IMAGE_SOURCE_SENSOR, m.imageSourceSensor)
        num(Tag.SENSOR_LATITUDE, m.sensorLatitude)
        num(Tag.SENSOR_LONGITUDE, m.sensorLongitude)
        num(Tag.SENSOR_TRUE_ALTITUDE, m.sensorTrueAltitude)
        num(Tag.SENSOR_HFOV, m.horizontalFov)
        num(Tag.SENSOR_VFOV, m.verticalFov)
        num(Tag.SENSOR_REL_AZIMUTH, m.sensorRelativeAzimuth)
        num(Tag.SENSOR_REL_ELEVATION, m.sensorRelativeElevation)
        num(Tag.SENSOR_REL_ROLL, m.sensorRelativeRoll)
        num(Tag.SLANT_RANGE, m.slantRange)
        num(Tag.FRAME_CENTER_LATITUDE, m.frameCenter?.lat)
        num(Tag.FRAME_CENTER_LONGITUDE, m.frameCenter?.lon)
        num(Tag.FRAME_CENTER_ELEVATION, m.frameCenterElevation)
        text(Tag.PLATFORM_CALL_SIGN, m.callSign)
        raw(Tag.UAS_LS_VERSION, version.toLong())
        m.vmti?.let { v ->
            header(Tag.VMTI_LOCAL_SET, v.size)
            body.bytes(v)
        }
        num(Tag.SENSOR_ELLIPSOID_HEIGHT, m.sensorEllipsoidHeight)
        m.corners?.takeIf { it.size == 4 }?.let { c ->
            num(Tag.CORNER_LAT_1, c[0].lat); num(Tag.CORNER_LON_1, c[0].lon)
            num(Tag.CORNER_LAT_2, c[1].lat); num(Tag.CORNER_LON_2, c[1].lon)
            num(Tag.CORNER_LAT_3, c[2].lat); num(Tag.CORNER_LON_3, c[2].lon)
            num(Tag.CORNER_LAT_4, c[3].lat); num(Tag.CORNER_LON_4, c[3].lon)
        }

        out.reset()
        out.bytes(St0601.UNIVERSAL_KEY)
        Ber.writeLength(out, body.size + 4)
        out.bytes(body.toByteArray())
        out.u8(Tag.CHECKSUM.id).u8(2)
        out.u16(checksum(out, out.size))
        return out.toByteArray()
    }

    private fun allowed(tag: Tag) = !tag.optional || tag in enabled

    private fun header(tag: Tag, length: Int) {
        Ber.writeOid(body, tag.id)
        Ber.writeLength(body, length)
    }

    private fun raw(tag: Tag, value: Long) {
        if (!allowed(tag)) return
        val n = (tag.format as TagFormat.UInt).bytes
        header(tag, n)
        body.uN(value, n)
    }

    private fun num(tag: Tag, value: Double?) {
        if (value == null || value.isNaN() || !allowed(tag)) return
        when (val f = tag.format) {
            is TagFormat.UMapped -> {
                header(tag, f.bytes)
                body.uN(TagMapping.encodeUnsigned(f, value), f.bytes)
            }
            is TagFormat.SMapped -> {
                header(tag, f.bytes)
                body.uN(TagMapping.encodeSigned(f, value), f.bytes)
            }
            else -> error("$tag is not numeric")
        }
    }

    private fun text(tag: Tag, value: String?) {
        if (value.isNullOrEmpty() || !allowed(tag)) return
        val bytes = value.toByteArray(Charsets.UTF_8).let {
            if (it.size > St0601.MAX_STRING_BYTES) it.copyOf(St0601.MAX_STRING_BYTES) else it
        }
        header(tag, bytes.size)
        body.bytes(bytes)
    }

    companion object {
        /** ST 0601 16-bit running sum over [length] bytes, starting at the Universal Key. */
        fun checksum(data: ByteSink, length: Int): Int {
            var bcc = 0
            for (i in 0 until length) bcc += data[i] shl (8 * ((i + 1) % 2))
            return bcc and 0xFFFF
        }

        fun checksum(data: ByteArray, offset: Int, length: Int): Int {
            var bcc = 0
            for (i in 0 until length) bcc += (data[offset + i].toInt() and 0xFF) shl (8 * ((i + 1) % 2))
            return bcc and 0xFFFF
        }
    }
}
