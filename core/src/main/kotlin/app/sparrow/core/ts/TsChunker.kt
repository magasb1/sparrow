package app.sparrow.core.ts

/**
 * Groups 188-byte TS packets into SRT payloads of [packetsPerChunk] packets (MUX-4: 7 × 188 =
 * 1316 bytes). Each emitted array is freshly allocated, so sinks may keep it.
 */
class TsChunker(
    private val packetsPerChunk: Int = 7,
    private val sink: (ByteArray) -> Unit,
) {
    private val chunk = ByteArray(packetsPerChunk * TsConst.PACKET_SIZE)
    private var count = 0

    fun add(packet: ByteArray, offset: Int = 0) {
        System.arraycopy(packet, offset, chunk, count * TsConst.PACKET_SIZE, TsConst.PACKET_SIZE)
        if (++count == packetsPerChunk) {
            sink(chunk.copyOf())
            count = 0
        }
    }

    /** Emits any partial chunk, e.g. when stopping. */
    fun flush() {
        if (count > 0) {
            sink(chunk.copyOf(count * TsConst.PACKET_SIZE))
            count = 0
        }
    }
}
