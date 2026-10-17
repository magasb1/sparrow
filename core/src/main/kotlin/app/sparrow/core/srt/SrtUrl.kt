package app.sparrow.core.srt

import app.sparrow.core.config.Destination
import app.sparrow.core.config.SrtMode
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Parses and formats `srt://host:port?streamid=…&latency=…&passphrase=…&pbkeylen=…&mode=…`
 * destinations (SRT-4), using the query keys of libsrt's srt-live-transmit.
 */
object SrtUrl {
    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(url: String, base: Destination = Destination()): Destination {
        val trimmed = url.trim()
        if (!trimmed.startsWith("srt://", ignoreCase = true)) throw ParseException("URL must start with srt://")
        val rest = trimmed.substring(6)
        val authority = rest.substringBefore('?').trimEnd('/')
        val query = if ('?' in rest) rest.substringAfter('?') else ""

        val (host, portText) = when {
            authority.startsWith("[") -> {
                val close = authority.indexOf(']')
                if (close < 0) throw ParseException("Unclosed IPv6 address")
                authority.substring(1, close) to authority.substring(close + 1).removePrefix(":")
            }
            ':' in authority -> authority.substringBeforeLast(':') to authority.substringAfterLast(':')
            else -> authority to ""
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw ParseException("Missing or invalid port")

        var d = base.copy(host = host, port = port)
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=').lowercase()
            val v = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            k to v
        }
        params["streamid"]?.let { d = d.copy(streamId = it) }
        params["latency"]?.let { v ->
            d = d.copy(latencyMs = v.toIntOrNull() ?: throw ParseException("Invalid latency"))
        }
        params["passphrase"]?.let { d = d.copy(passphrase = it) }
        params["pbkeylen"]?.let { v ->
            d = d.copy(keyLength = v.toIntOrNull() ?: throw ParseException("Invalid pbkeylen"))
        }
        params["mode"]?.let { v ->
            d = d.copy(
                mode = when (v.lowercase()) {
                    "caller", "client" -> SrtMode.CALLER
                    "listener", "server" -> SrtMode.LISTENER
                    else -> throw ParseException("Unsupported mode '$v'")
                },
            )
        } ?: run {
            // srt-live-transmit convention: no host means listener.
            if (host.isEmpty()) d = d.copy(mode = SrtMode.LISTENER)
        }
        return d
    }

    fun format(d: Destination, includePassphrase: Boolean = false): String {
        val host = if (':' in d.host) "[${d.host}]" else d.host
        val params = buildList {
            if (d.streamId.isNotEmpty()) add("streamid=" + enc(d.streamId))
            add("latency=${d.latencyMs}")
            if (d.mode == SrtMode.LISTENER) add("mode=listener")
            if (includePassphrase && d.passphrase.isNotEmpty()) add("passphrase=" + enc(d.passphrase))
            if (d.keyLength != 0) add("pbkeylen=${d.keyLength}")
        }
        return "srt://$host:${d.port}" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
