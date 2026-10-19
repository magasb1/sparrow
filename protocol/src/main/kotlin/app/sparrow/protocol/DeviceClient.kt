package app.sparrow.protocol

import app.sparrow.protocol.v1.CommandDoneRequest
import app.sparrow.protocol.v1.GetConfigResponse
import app.sparrow.protocol.v1.RegisterRequest
import app.sparrow.protocol.v1.RegisterResponse
import app.sparrow.protocol.v1.ReportRequest
import app.sparrow.protocol.v1.ReportResponse
import app.sparrow.protocol.v1.SubscribeResponse
import app.sparrow.protocol.v1.getConfigRequest
import app.sparrow.protocol.v1.subscribeRequest
import app.sparrow.protocol.v1.unregisterRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Typed client for `sparrow.v1.DeviceService`. [pin] limits TLS trust to the backend's key;
 * without it the system's CAs apply. [token] is the device token, null before registration.
 */
class DeviceClient(serverUrl: String, pin: String?, private val token: String?, base: OkHttpClient = sharedHttp) {
    private val unary: ConnectClient
    private val streaming: ConnectClient

    init {
        // A redirect would turn the POST into a GET; RPCs never follow one.
        val noRedirects = base.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val tls = if (pin != null && serverUrl.startsWith("https://", ignoreCase = true)) {
            PinnedTrustManager.apply(noRedirects, pin)
        } else noRedirects
        unary = ConnectClient(tls.newBuilder().callTimeout(20, TimeUnit.SECONDS).build(), serverUrl)
        // The server sends a heartbeat every 15 s; three missed ones mean the stream is dead.
        streaming = ConnectClient(
            tls.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(STREAM_IDLE_SECONDS, TimeUnit.SECONDS).build(),
            serverUrl,
        )
    }

    private val auth: Map<String, String>
        get() = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    suspend fun register(request: RegisterRequest): RegisterResponse =
        RegisterResponse.parseFrom(unary.unary("$SERVICE/Register", request.toByteArray()))

    suspend fun report(request: ReportRequest): ReportResponse =
        ReportResponse.parseFrom(unary.unary("$SERVICE/Report", request.toByteArray(), auth))

    suspend fun getConfig(): GetConfigResponse =
        GetConfigResponse.parseFrom(unary.unary("$SERVICE/GetConfig", getConfigRequest {}.toByteArray(), auth))

    suspend fun commandDone(request: CommandDoneRequest) {
        unary.unary("$SERVICE/CommandDone", request.toByteArray(), auth)
    }

    suspend fun unregister() {
        unary.unary("$SERVICE/Unregister", unregisterRequest {}.toByteArray(), auth)
    }

    fun subscribe(): Flow<SubscribeResponse> =
        streaming.serverStream("$SERVICE/Subscribe", subscribeRequest {}.toByteArray(), auth)
            .map { SubscribeResponse.parseFrom(it) }

    companion object {
        const val SERVICE = "sparrow.v1.DeviceService"
        const val STREAM_IDLE_SECONDS = 45L
        val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        }
    }
}
