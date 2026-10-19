package app.sparrow.protocol

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An error returned by the server, with its Connect code ("unauthenticated", "permission_denied", …). */
class ConnectException(val code: String, message: String) : IOException("$code: $message") {
    val serverMessage: String = message
}

/**
 * Minimal client for the Connect protocol (https://connectrpc.com/docs/protocol): unary calls are
 * a POST with a protobuf body; a server stream is a POST whose response is a sequence of
 * length-prefixed messages ending with a JSON end-of-stream frame. Works over HTTP/1.1 and HTTP/2.
 */
class ConnectClient(private val http: OkHttpClient, baseUrl: String) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    private fun url(method: String) = base.newBuilder().addPathSegments(method).build()

    suspend fun unary(method: String, body: ByteArray, headers: Map<String, String> = emptyMap()): ByteArray {
        val request = Request.Builder()
            .url(url(method))
            .post(body.toRequestBody(PROTO))
            .header("Connect-Protocol-Version", "1")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return http.newCall(request).await().use { response ->
            val bytes = response.body.bytes()
            if (response.code != 200) throw errorOf(response.code, bytes)
            val type = response.header("Content-Type").orEmpty()
            if (!type.startsWith("application/proto")) throw IOException("Unexpected response type '$type'")
            bytes
        }
    }

    /**
     * Opens a server stream. The flow emits each message's bytes, completes at the end of the
     * stream and fails with [ConnectException] or [IOException]. Cancelling the collector closes
     * the connection.
     */
    fun serverStream(method: String, body: ByteArray, headers: Map<String, String> = emptyMap()): Flow<ByteArray> = callbackFlow {
        val request = Request.Builder()
            .url(url(method))
            .post(envelope(body).toRequestBody(STREAM))
            .header("Connect-Protocol-Version", "1")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val call = http.newCall(request)
        thread(name = "connect-stream", isDaemon = true) {
            try {
                call.execute().use { response ->
                    if (response.code != 200) throw errorOf(response.code, response.body.bytes())
                    val source = response.body.source()
                    while (true) {
                        if (source.exhausted()) throw IOException("Stream ended without an end-of-stream frame")
                        val flags = source.readByte().toInt() and 0xff
                        val length = source.readInt()
                        if (length < 0 || length > MAX_MESSAGE) throw IOException("Message too large: $length")
                        val payload = source.readByteArray(length.toLong())
                        if (flags and FLAG_COMPRESSED != 0) throw IOException("Compressed message not requested")
                        if (flags and FLAG_END != 0) {
                            endOfStream(payload)?.let { throw it }
                            break
                        }
                        if (trySendBlocking(payload).isFailure) break
                    }
                }
                channel.close()
            } catch (e: Throwable) {
                channel.close(e)
            }
        }
        awaitClose { call.cancel() }
    }

    companion object {
        private val PROTO = "application/proto".toMediaType()
        private val STREAM = "application/connect+proto".toMediaType()
        private const val FLAG_COMPRESSED = 0x01
        private const val FLAG_END = 0x02
        private const val MAX_MESSAGE = 4 shl 20
        private val json = Json { ignoreUnknownKeys = true }

        fun envelope(message: ByteArray, flags: Int = 0): ByteArray =
            Buffer().writeByte(flags).writeInt(message.size).write(message).readByteArray()

        /** The error in an end-of-stream frame, or null when the stream ended normally. */
        internal fun endOfStream(payload: ByteArray): IOException? {
            val obj = runCatching { json.parseToJsonElement(payload.decodeToString()).jsonObject }.getOrNull()
                ?: return IOException("Malformed end-of-stream frame")
            val error = obj["error"] as? JsonObject ?: return null
            return ConnectException(
                error["code"]?.jsonPrimitive?.content ?: "unknown",
                error["message"]?.jsonPrimitive?.content ?: "",
            )
        }

        /**
         * The Connect error in a non-200 response. A body that is not a Connect error (from a
         * proxy, say) never maps to "unauthenticated", which would make the phone forget its
         * registration.
         */
        internal fun errorOf(status: Int, body: ByteArray): IOException {
            val obj = runCatching { json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()
            val code = obj?.get("code")?.jsonPrimitive?.content
                ?: return IOException("HTTP $status from the server")
            return ConnectException(code, obj["message"]?.jsonPrimitive?.content ?: "")
        }
    }
}

/** Runs the call asynchronously; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}
