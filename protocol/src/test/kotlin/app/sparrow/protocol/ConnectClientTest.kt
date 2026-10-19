package app.sparrow.protocol

import app.sparrow.protocol.v1.StreamState
import app.sparrow.protocol.v1.SubscribeResponse
import app.sparrow.protocol.v1.heartbeat
import app.sparrow.protocol.v1.reportRequest
import app.sparrow.protocol.v1.reportResponse
import app.sparrow.protocol.v1.subscribeResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ConnectClientTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun client(token: String? = "tok") = DeviceClient(server.url("/").toString(), null, token, OkHttpClient())

    @Test
    fun unaryCallSendsProtobufAndBearerToken() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/proto")
                .body(Buffer().write(reportResponse { latestConfigRevision = 7 }.toByteArray())).build(),
        )
        val res = client().report(reportRequest { state = StreamState.STREAM_STATE_LIVE; sendKbps = 1200.0 })
        assertEquals(7L, res.latestConfigRevision)
        val req = server.takeRequest()
        assertEquals("/sparrow.v1.DeviceService/Report", req.url.encodedPath)
        assertEquals("application/proto", req.headers["Content-Type"])
        assertEquals("1", req.headers["Connect-Protocol-Version"])
        assertEquals("Bearer tok", req.headers["Authorization"])
        val sent = app.sparrow.protocol.v1.ReportRequest.parseFrom(req.body!!.toByteArray())
        assertEquals(StreamState.STREAM_STATE_LIVE, sent.state)
    }

    @Test
    fun errorsCarryTheConnectCode() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(401).addHeader("Content-Type", "application/json")
                .body("""{"code":"unauthenticated","message":"unknown or revoked device token"}""").build(),
        )
        try {
            client().getConfig()
            fail("no exception")
        } catch (e: ConnectException) {
            assertEquals("unauthenticated", e.code)
            assertEquals("unknown or revoked device token", e.serverMessage)
        }
    }

    private fun frames(vararg messages: SubscribeResponse, end: String = "{}"): Buffer {
        val b = Buffer()
        messages.forEach { b.write(ConnectClient.envelope(it.toByteArray())) }
        b.write(ConnectClient.envelope(end.encodeToByteArray(), flags = 2))
        return b
    }

    @Test
    fun serverStreamDecodesFramesUntilEndOfStream() = runBlocking {
        val hb = subscribeResponse { heartbeat = heartbeat {} }
        val cfg = subscribeResponse { configChanged = 4 }
        server.enqueue(
            MockResponse.Builder().code(200).headers(Headers.headersOf("Content-Type", "application/connect+proto"))
                .body(frames(hb, cfg)).build(),
        )
        val got = client().subscribe().toList()
        assertEquals(listOf(hb, cfg), got)
        val req = server.takeRequest()
        assertEquals("application/connect+proto", req.headers["Content-Type"])
        // The request is one enveloped, empty SubscribeRequest.
        assertEquals(listOf<Byte>(0, 0, 0, 0, 0), req.body!!.toByteArray().toList())
    }

    @Test
    fun serverStreamErrorInEndFrame() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).headers(Headers.headersOf("Content-Type", "application/connect+proto"))
                .body(frames(end = """{"error":{"code":"unauthenticated","message":"revoked"}}""")).build(),
        )
        try {
            client().subscribe().toList()
            fail("no exception")
        } catch (e: ConnectException) {
            assertEquals("unauthenticated", e.code)
        }
    }

    @Test
    fun nonConnectErrorIsNotUnauthenticated() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("<html>proxy login</html>").build())
        try {
            client().getConfig()
            fail("no exception")
        } catch (e: ConnectException) {
            fail("mapped to a Connect error: ${e.code}")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("401"))
        }
    }

    @Test
    fun malformedEndOfStreamFails() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).headers(Headers.headersOf("Content-Type", "application/connect+proto"))
                .body(frames(end = "not json")).build(),
        )
        try {
            client().subscribe().toList()
            fail("no exception")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("end-of-stream"))
        }
    }

    @Test
    fun truncatedStreamFails() = runBlocking {
        val b = Buffer().write(ConnectClient.envelope(subscribeResponse { configChanged = 1 }.toByteArray()))
        server.enqueue(MockResponse.Builder().code(200).body(b).build())
        try {
            client().subscribe().toList()
            fail("no exception")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("end-of-stream"))
        }
    }

    @Test
    fun pinnedTlsAcceptsOnlyTheEnrolledKey() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        val goodPin = PinnedTrustManager.pinOf(cert.certificate)
        val url = server.url("/").toString()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/proto")
                .body(Buffer().write(reportResponse {}.toByteArray())).build(),
        )
        DeviceClient(url, goodPin, "tok", OkHttpClient()).report(reportRequest {})

        val otherPin = PinnedTrustManager.pinOf(HeldCertificate.Builder().build().certificate)
        try {
            DeviceClient(url, otherPin, "tok", OkHttpClient()).report(reportRequest {})
            fail("connected with the wrong pin")
        } catch (e: IOException) {
            // SSLHandshakeException
        }
    }
}
