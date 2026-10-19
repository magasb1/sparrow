package app.sparrow.protocol

import app.sparrow.protocol.v1.StreamState
import app.sparrow.protocol.v1.deviceInfo
import app.sparrow.protocol.v1.registerRequest
import app.sparrow.protocol.v1.reportRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to a running sparrowd. Set SPARROW_IT_LINK to a fresh enrollment link from its
 * "Add phone" page; skipped otherwise.
 */
class LiveServerTest {
    @Test
    fun registerReportSubscribeUnregister() = runBlocking {
        val text = System.getenv("SPARROW_IT_LINK")
        assumeTrue("SPARROW_IT_LINK not set", !text.isNullOrBlank())
        val link = EnrollLink.parse(text!!)
        val reg = DeviceClient(link.serverUrl, link.pin, null).register(
            registerRequest {
                enrollmentToken = link.token
                device = deviceInfo { manufacturer = "JVM"; model = "LiveServerTest"; zoomMin = 1.0; zoomMax = 4.0 }
            },
        )
        assertTrue(reg.alias.startsWith("cam-"))
        val client = DeviceClient(link.serverUrl, link.pin, reg.deviceToken)
        val res = client.report(reportRequest { state = StreamState.STREAM_STATE_STANDBY; profileName = "Default" })
        assertEquals(0L, res.latestConfigRevision)
        val first = withTimeout(10_000) { client.subscribe().first() }
        assertTrue(first.hasHeartbeat())
        client.unregister()
        try {
            client.report(reportRequest {})
            throw AssertionError("report accepted after unregister")
        } catch (e: ConnectException) {
            assertEquals("unauthenticated", e.code)
        }
    }
}
