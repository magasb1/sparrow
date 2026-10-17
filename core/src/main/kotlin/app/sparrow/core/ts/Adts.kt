package app.sparrow.core.ts

/** ADTS framing for raw AAC-LC frames, as carried in TS stream type 0x0F (VID-5). */
object Adts {
    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun sampleRateIndex(rate: Int): Int =
        SAMPLE_RATES.indexOf(rate).also { require(it >= 0) { "unsupported AAC sample rate $rate" } }

    /** Returns [rawFrame] with a 7-byte ADTS header (no CRC) in front. */
    fun frame(rawFrame: ByteArray, sampleRate: Int, channels: Int, offset: Int = 0, length: Int = rawFrame.size - offset): ByteArray {
        val total = length + 7
        val sr = sampleRateIndex(sampleRate)
        val profile = 1 // AAC LC (object type 2) minus one
        val out = ByteArray(total)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // MPEG-4, layer 0, no CRC
        out[2] = ((profile shl 6) or (sr shl 2) or (channels ushr 2 and 0x01)).toByte()
        out[3] = (((channels and 0x03) shl 6) or (total ushr 11 and 0x03)).toByte()
        out[4] = (total ushr 3).toByte()
        out[5] = (((total and 0x07) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        System.arraycopy(rawFrame, offset, out, 7, length)
        return out
    }
}
