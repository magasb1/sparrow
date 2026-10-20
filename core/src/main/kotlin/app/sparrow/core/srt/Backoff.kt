package app.sparrow.core.srt

/**
 * Reconnect delays for SRT-3: 1 s doubling to 5 s, reset after a successful connection. The cap
 * is low because a server keeps a dead session for 5–10 s and rejects the stream id until then;
 * an SRT handshake is cheap, so retrying every 5 s costs nothing and recovers promptly.
 */
class Backoff(private val initialMs: Long = 1_000, private val maxMs: Long = 5_000) {
    private var next = initialMs

    fun nextDelayMs(): Long {
        val d = next
        next = minOf(next * 2, maxMs)
        return d
    }

    fun reset() {
        next = initialMs
    }
}
