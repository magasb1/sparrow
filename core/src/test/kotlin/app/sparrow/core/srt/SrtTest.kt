package app.sparrow.core.srt

import app.sparrow.core.config.Destination
import app.sparrow.core.config.Profile
import app.sparrow.core.config.ProfileCodec
import app.sparrow.core.config.SrtMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtTest {
    @Test
    fun `parses caller URL with options`() {
        val d = SrtUrl.parse("srt://media.example.com:8890?streamid=publish:cam1&latency=800&passphrase=secret%20phrase1&pbkeylen=32")
        assertEquals("media.example.com", d.host)
        assertEquals(8890, d.port)
        assertEquals("publish:cam1", d.streamId)
        assertEquals(800, d.latencyMs)
        assertEquals("secret phrase1", d.passphrase)
        assertEquals(32, d.keyLength)
        assertEquals(SrtMode.CALLER, d.mode)
    }

    @Test
    fun `parses listener and IPv6`() {
        assertEquals(SrtMode.LISTENER, SrtUrl.parse("srt://:9000").mode)
        val v6 = SrtUrl.parse("srt://[2001:db8::1]:9000?mode=caller")
        assertEquals("2001:db8::1", v6.host)
        assertEquals(SrtMode.CALLER, v6.mode)
    }

    @Test(expected = SrtUrl.ParseException::class)
    fun `rejects missing port`() {
        SrtUrl.parse("srt://host")
    }

    @Test
    fun `format round trips and hides passphrase by default`() {
        val d = Destination(host = "10.0.0.2", port = 9999, streamId = "a b", latencyMs = 300, passphrase = "0123456789")
        val url = SrtUrl.format(d)
        assertFalse(url.contains("passphrase"))
        assertEquals(d.copy(passphrase = ""), SrtUrl.parse(url))
        assertEquals(d, SrtUrl.parse(SrtUrl.format(d, includePassphrase = true)))
    }

    @Test
    fun `backoff doubles to 30 s and resets`() {
        val b = Backoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), List(7) { b.nextDelayMs() })
        b.reset()
        assertEquals(1000L, b.nextDelayMs())
    }

    @Test
    fun `bitrate controller backs off on loss and probes up when clean`() {
        val c = BitrateController(minKbps = 500, maxKbps = 6000, initialKbps = 4000, latencyMs = 500)
        val lossy = LinkSample(packetsSent = 1000, packetsLost = 100, packetsRetransmitted = 100, packetsDropped = 0, rttMs = 80.0, sendBufferMs = 50)
        assertEquals(3000, c.onSample(lossy))
        val clean = lossy.copy(packetsLost = 0, packetsRetransmitted = 0)
        repeat(4) { assertNull(c.onSample(clean)) }
        assertEquals(3301, c.onSample(clean))
        repeat(100) { c.onSample(lossy) }
        assertEquals(500, c.targetKbps)
    }

    @Test
    fun `profile export strips passphrase and import assigns new ids`() {
        val p = Profile(name = "Field", destination = Destination(host = "h", passphrase = "0123456789abc"))
        val json = ProfileCodec.export(listOf(p))
        assertFalse(json.contains("0123456789abc"))
        val back = ProfileCodec.import(json).single()
        assertEquals("Field", back.name)
        assertTrue(back.id != p.id)
        assertEquals("", back.destination.passphrase)
    }

    @Test
    fun `validation catches bad settings`() {
        assertTrue(Profile(destination = Destination(host = "h")).validate().isEmpty())
        val errors = Profile(destination = Destination(host = "", passphrase = "short")).validate()
        assertEquals(2, errors.size)
    }
}
