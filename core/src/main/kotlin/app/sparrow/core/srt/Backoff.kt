package app.sparrow.core.srt

/** Reconnect delays for SRT-3: 1 s doubling to 30 s, reset after a successful connection. */
class Backoff(private val initialMs: Long = 1_000, private val maxMs: Long = 30_000) {
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
