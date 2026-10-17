package app.sparrow.core.klv

import app.sparrow.core.util.ByteSink
import app.sparrow.core.util.hexToBytes
import app.sparrow.core.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class St0601Test {
    private fun encodeTag(tag: Tag, value: Double): String {
        val sink = ByteSink()
        when (val f = tag.format) {
            is TagFormat.UMapped -> sink.uN(TagMapping.encodeUnsigned(f, value), f.bytes)
            is TagFormat.SMapped -> sink.uN(TagMapping.encodeSigned(f, value), f.bytes)
            else -> error("not numeric")
        }
        return sink.toByteArray().toHex()
    }

    /** Example values from the MISB ST 0601 tag tables. */
    @Test
    fun `tag mappings match ST 0601 examples`() {
        assertEquals("71C2", encodeTag(Tag.PLATFORM_HEADING, 159.9744))
        assertEquals("FD3D", encodeTag(Tag.PLATFORM_PITCH, -0.4315251))
        assertEquals("08B8", encodeTag(Tag.PLATFORM_ROLL, 3.405814))
        assertEquals("5595B66D", encodeTag(Tag.SENSOR_LATITUDE, 60.17682296))
        assertEquals("5B5360C4", encodeTag(Tag.SENSOR_LONGITUDE, 128.42675904))
        assertEquals("C221", encodeTag(Tag.SENSOR_TRUE_ALTITUDE, 14190.72))
        assertEquals("CD9C", encodeTag(Tag.SENSOR_HFOV, 144.5713))
        assertEquals("D917", encodeTag(Tag.SENSOR_VFOV, 152.6436))
        assertEquals("724A0A20", encodeTag(Tag.SENSOR_REL_AZIMUTH, 160.71921147))
        assertEquals("87F84B86", encodeTag(Tag.SENSOR_REL_ELEVATION, -168.79232483))
        assertEquals("7DC55ECE", encodeTag(Tag.SENSOR_REL_ROLL, 176.86543765))
        assertEquals("03830926", encodeTag(Tag.SLANT_RANGE, 68590.983298744))
        assertEquals("F101A229", encodeTag(Tag.FRAME_CENTER_LATITUDE, -10.54238863))
        assertEquals("14BC082B", encodeTag(Tag.FRAME_CENTER_LONGITUDE, 29.15789012))
        assertEquals("34F3", encodeTag(Tag.FRAME_CENTER_ELEVATION, 3216.037))
    }

    @Test
    fun `minimal packet matches golden bytes`() {
        val encoder = St0601Encoder(version = 19, enabledTags = setOf(Tag.PLATFORM_HEADING))
        val packet = encoder.encode(
            UasMetadata(timestampUtcMicros = 1224807209913000L, platformHeading = 159.9744, sensorLatitude = 60.0),
        )
        val expected = "060E2B34020B01010E01030101000000" + "15" +
            "0208000459F4A6AA4AA8" + "050271C2" + "410113" + "01027567"
        assertArrayEquals(expected.hexToBytes(), packet)
    }

    @Test
    fun `tag 2 first, tag 65 present, checksum last`() {
        val packet = St0601Encoder().encode(fullSample())
        val decoded = St0601Decoder.decode(packet)
        assertTrue(decoded.checksumValid)
        assertEquals(2, decoded.items.first().tagId)
        assertEquals(1, decoded.items.last().tagId)
        assertEquals(19L, decoded[Tag.UAS_LS_VERSION])
    }

    @Test
    fun `round trip within quantisation`() {
        val m = fullSample()
        val d = St0601Decoder.decode(St0601Encoder().encode(m))
        assertEquals(m.timestampUtcMicros, d[Tag.PRECISION_TIME_STAMP])
        assertEquals(m.missionId, d[Tag.MISSION_ID])
        assertEquals(m.callSign, d[Tag.PLATFORM_CALL_SIGN])
        assertEquals(m.sensorLatitude!!, d[Tag.SENSOR_LATITUDE] as Double, 1e-7)
        assertEquals(m.sensorLongitude!!, d[Tag.SENSOR_LONGITUDE] as Double, 1e-7)
        assertEquals(m.sensorTrueAltitude!!, d[Tag.SENSOR_TRUE_ALTITUDE] as Double, 0.3)
        assertEquals(m.platformHeading!!, d[Tag.PLATFORM_HEADING] as Double, 0.01)
        assertEquals(m.sensorRelativeElevation!!, d[Tag.SENSOR_REL_ELEVATION] as Double, 1e-6)
        assertEquals(m.corners!![2].lon, d[Tag.CORNER_LON_3] as Double, 1e-7)
        assertEquals(m.slantRange!!, d[Tag.SLANT_RANGE] as Double, 0.01)
    }

    @Test
    fun `missing values and disabled tags are omitted`() {
        val encoder = St0601Encoder(enabledTags = Tag.optionalTags.toSet() - Tag.MISSION_ID)
        val d = St0601Decoder.decode(encoder.encode(UasMetadata(timestampUtcMicros = 1, missionId = "M1")))
        assertFalse(d.has(Tag.MISSION_ID))
        assertFalse(d.has(Tag.SENSOR_LATITUDE))
        assertEquals(listOf(2, 65, 1), d.items.map { it.tagId })
    }

    @Test
    fun `corrupted packet fails checksum`() {
        val packet = St0601Encoder().encode(fullSample())
        packet[30] = (packet[30] + 1).toByte()
        assertFalse(St0601Decoder.decode(packet).checksumValid)
    }

    @Test
    fun `signed values saturate and decode reserved value as out of range`() {
        val f = Tag.PLATFORM_PITCH.format as TagFormat.SMapped
        assertEquals(0x7FFFL, TagMapping.encodeSigned(f, 25.0))
        assertEquals(0x8001L, TagMapping.encodeSigned(f, -25.0))
        assertNull(TagMapping.decodeSigned(f, 0x8000))
    }

    @Test
    fun `long text is truncated to 127 bytes`() {
        val d = St0601Decoder.decode(St0601Encoder().encode(UasMetadata(1, missionId = "x".repeat(300))))
        assertEquals(127, (d[Tag.MISSION_ID] as String).length)
    }

    @Test
    fun `BER long-form length round trips`() {
        val sink = ByteSink()
        Ber.writeLength(sink, 300)
        assertEquals("82012C", sink.toByteArray().toHex())
        assertEquals(300 to 3, Ber.readLength(sink.toByteArray(), 0))
        val oid = ByteSink()
        Ber.writeOid(oid, 144)
        assertEquals("8110", oid.toByteArray().toHex())
        assertEquals(144 to 2, Ber.readOid(oid.toByteArray(), 0))
    }

    companion object {
        fun fullSample() = UasMetadata(
            timestampUtcMicros = 1_760_000_000_123_456L,
            missionId = "MISSION 01",
            platformDesignation = "Sparrow",
            imageSourceSensor = "EO",
            callSign = "SPARROW1",
            platformHeading = 123.45,
            platformPitch = 0.0,
            platformRoll = 0.0,
            sensorLatitude = 59.9138688,
            sensorLongitude = 10.7522454,
            sensorTrueAltitude = 120.0,
            sensorEllipsoidHeight = 160.0,
            horizontalFov = 66.0,
            verticalFov = 39.0,
            sensorRelativeAzimuth = 0.0,
            sensorRelativeElevation = -30.0,
            sensorRelativeRoll = 2.0,
            slantRange = 240.0,
            frameCenter = LatLon(59.915, 10.755),
            frameCenterElevation = 0.0,
            corners = listOf(LatLon(59.92, 10.75), LatLon(59.92, 10.76), LatLon(59.91, 10.76), LatLon(59.91, 10.75)),
        )
    }
}
