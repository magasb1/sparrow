package app.sparrow.protocol

import java.net.URI
import java.net.URLDecoder

/**
 * An enrollment link from the backend's "Add phone" page:
 * `sparrow://enroll?u=<server URL>&t=<enrollment token>&fp=<server key pin>`.
 */
data class EnrollLink(val serverUrl: String, val token: String, val pin: String?) {
    /** True when the server is reached over plain HTTP (lab use only). */
    val insecure: Boolean get() = serverUrl.startsWith("http://", ignoreCase = true)

    class ParseException(message: String) : IllegalArgumentException(message)

    companion object {
        private val PIN = Regex("^[A-Za-z0-9_-]{43}$")

        fun parse(text: String): EnrollLink {
            val uri = runCatching { URI(text.trim()) }.getOrElse { throw ParseException("Not a valid link") }
            if (!uri.scheme.equals("sparrow", ignoreCase = true) || !uri.host.equals("enroll", ignoreCase = true)) {
                throw ParseException("Link must start with sparrow://enroll")
            }
            val q = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val server = q["u"]?.trimEnd('/') ?: throw ParseException("Link has no server address")
            val serverUri = runCatching { URI(server) }.getOrNull()
            if (serverUri == null || serverUri.host.isNullOrEmpty() ||
                serverUri.scheme?.lowercase() !in setOf("https", "http")
            ) {
                throw ParseException("Server address must be an http(s) URL")
            }
            val token = q["t"]?.takeIf { it.isNotBlank() } ?: throw ParseException("Link has no enrollment token")
            val pin = q["fp"]?.takeIf { it.isNotEmpty() }
            if (pin != null && !PIN.matches(pin)) throw ParseException("Server key pin is malformed")
            return EnrollLink(server, token, pin)
        }
    }
}
