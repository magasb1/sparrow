package app.sparrow.core.srt

/** One second of SRT sender statistics (from srt_bstats with clear = true). */
data class LinkSample(
    val packetsSent: Long,
    val packetsLost: Int,
    val packetsRetransmitted: Int,
    val packetsDropped: Int,
    val rttMs: Double,
    /** Data waiting in the send buffer, milliseconds of media. */
    val sendBufferMs: Int,
)

/**
 * Adaptive bitrate from SRT statistics (SRT-5). Backs off quickly when the link shows
 * congestion and probes upwards slowly once it has been clean for a while.
 */
class BitrateController(
    private val minKbps: Int,
    private val maxKbps: Int,
    initialKbps: Int,
    private val latencyMs: Int,
) {
    var targetKbps: Int = initialKbps.coerceIn(minKbps, maxKbps)
        private set

    private var cleanSeconds = 0

    /** Feeds one sample; returns the new target if it changed. */
    fun onSample(s: LinkSample): Int? {
        val lossRatio = if (s.packetsSent > 0) (s.packetsLost + s.packetsDropped).toDouble() / s.packetsSent else 0.0
        val congested = s.packetsDropped > 0 || lossRatio > 0.05 || s.sendBufferMs > latencyMs / 2
        val previous = targetKbps
        if (congested) {
            cleanSeconds = 0
            targetKbps = maxOf(minKbps, (targetKbps * 0.75).toInt())
        } else if (lossRatio < 0.01 && s.sendBufferMs < latencyMs / 4) {
            if (++cleanSeconds >= 5) {
                cleanSeconds = 0
                targetKbps = minOf(maxKbps, (targetKbps * 1.1).toInt() + 1)
            }
        } else {
            cleanSeconds = 0
        }
        return targetKbps.takeIf { it != previous }
    }
}
