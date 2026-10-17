package app.sparrow.core.ts

import app.sparrow.core.util.ByteSink

object TsConst {
    const val PACKET_SIZE = 188
    const val SYNC_BYTE = 0x47
    const val PAT_PID = 0x0000

    const val STREAM_TYPE_H264 = 0x1B
    const val STREAM_TYPE_H265 = 0x24
    const val STREAM_TYPE_AAC_ADTS = 0x0F
    const val STREAM_TYPE_METADATA_PES = 0x15
    const val STREAM_TYPE_PRIVATE_PES = 0x06

    const val STREAM_ID_VIDEO = 0xE0
    const val STREAM_ID_AUDIO = 0xC0
    const val STREAM_ID_METADATA = 0xFC
    const val STREAM_ID_PRIVATE_1 = 0xBD

    /** "KLVA" format identifier from the SMPTE registration authority. */
    val KLVA = byteArrayOf(0x4B, 0x4C, 0x56, 0x41)
}

enum class VideoCodec(val streamType: Int) { H264(TsConst.STREAM_TYPE_H264), H265(TsConst.STREAM_TYPE_H265) }

/** ST 1402 KLV carriage: synchronous metadata PES (0x15) or asynchronous private data (0x06). */
enum class KlvCarriage { SYNC, ASYNC }

data class TsConfig(
    val videoCodec: VideoCodec = VideoCodec.H264,
    val klvCarriage: KlvCarriage = KlvCarriage.SYNC,
    val audio: Boolean = false,
    val programNumber: Int = 1,
    val pmtPid: Int = 0x1000,
    val videoPid: Int = 0x0100,
    val klvPid: Int = 0x0101,
    val audioPid: Int = 0x0102,
    /** PTS = capture time + [ptsDelayUs]; PCR = mux time. Covers encode latency. */
    val ptsDelayUs: Long = 250_000,
    /** MUX-3: PCR at most every 40 ms. */
    val pcrIntervalUs: Long = 30_000,
    /** MUX-3: PAT/PMT at most every 100 ms. */
    val psiIntervalUs: Long = 80_000,
)

/**
 * Single-program MPEG-TS muxer for video, ST 0601 KLV and optional AAC audio (MUX-1..3).
 *
 * All times are microseconds on one monotonic capture clock: capture timestamps of frames and
 * metadata samples, and [nowUs] for when the data is handed to the muxer. Thread-safe.
 */
class TsMuxer(private val config: TsConfig, private val output: TsChunker) {
    private val packet = ByteArray(TsConst.PACKET_SIZE)
    private val cc = HashMap<Int, Int>()
    private val pes = ByteSink(256 * 1024)
    private var baseUs = Long.MIN_VALUE
    private var lastPcrUs = Long.MIN_VALUE
    private var lastPsiUs = Long.MIN_VALUE
    private var klvSequence = 0

    @Synchronized
    fun writeVideo(captureUs: Long, accessUnit: ByteArray, keyFrame: Boolean, nowUs: Long) {
        start(nowUs)
        if (keyFrame || due(lastPsiUs, nowUs, config.psiIntervalUs)) writePsi(nowUs)
        pes.reset()
        writePesHeader(TsConst.STREAM_ID_VIDEO, accessUnit.size, pts90k(captureUs), unbounded = true)
        pes.bytes(accessUnit)
        val pcr = due(lastPcrUs, nowUs, config.pcrIntervalUs / 2)
        if (pcr) lastPcrUs = nowUs
        packetize(config.videoPid, pes, pcrUs = if (pcr) nowUs else null, randomAccess = keyFrame)
    }

    /** Writes one ST 0601 packet. In sync mode it is time-stamped to [captureUs]. */
    @Synchronized
    fun writeKlv(captureUs: Long, klv: ByteArray, nowUs: Long) {
        start(nowUs)
        pes.reset()
        if (config.klvCarriage == KlvCarriage.SYNC) {
            // ISO 13818-1 §2.12.4 metadata Access Unit wrapper: one complete cell.
            writePesHeader(TsConst.STREAM_ID_METADATA, klv.size + 5, pts90k(captureUs))
            pes.u8(0x00) // metadata_service_id
            pes.u8(klvSequence++ and 0xFF)
            pes.u8(0xDF) // cell_fragment_indication=11, decoder_config=0, random_access=1, reserved
            pes.u16(klv.size)
        } else {
            writePesHeader(TsConst.STREAM_ID_PRIVATE_1, klv.size, pts90k = null)
        }
        pes.bytes(klv)
        packetize(config.klvPid, pes)
    }

    /** Writes one ADTS AAC frame. */
    @Synchronized
    fun writeAudio(captureUs: Long, adtsFrame: ByteArray, nowUs: Long) {
        if (!config.audio) return
        start(nowUs)
        pes.reset()
        writePesHeader(TsConst.STREAM_ID_AUDIO, adtsFrame.size, pts90k(captureUs))
        pes.bytes(adtsFrame)
        packetize(config.audioPid, pes)
    }

    /** Call every ~10–20 ms so PCR and PSI stay within their intervals between frames. */
    @Synchronized
    fun tick(nowUs: Long) {
        if (baseUs == Long.MIN_VALUE) return
        if (due(lastPsiUs, nowUs, config.psiIntervalUs)) writePsi(nowUs)
        if (due(lastPcrUs, nowUs, config.pcrIntervalUs)) {
            lastPcrUs = nowUs
            writePcrOnlyPacket(nowUs)
        }
    }

    @Synchronized
    fun flush() = output.flush()

    private fun due(last: Long, nowUs: Long, interval: Long) = last == Long.MIN_VALUE || nowUs - last >= interval

    private fun start(nowUs: Long) {
        if (baseUs == Long.MIN_VALUE) {
            // Start the clock at 1 s so early capture times never map below zero.
            baseUs = nowUs - 1_000_000
        }
    }

    private fun pts90k(captureUs: Long): Long = ((captureUs + config.ptsDelayUs - baseUs) * 9 / 100) and MASK_33

    private fun pcr27m(nowUs: Long): Long = (nowUs - baseUs) * 27

    // --- PES ---------------------------------------------------------------------------------

    private fun writePesHeader(streamId: Int, payloadSize: Int, pts90k: Long?, unbounded: Boolean = false) {
        val headerDataLength = if (pts90k != null) 5 else 0
        val pesLength = 3 + headerDataLength + payloadSize
        pes.u8(0x00).u8(0x00).u8(0x01).u8(streamId)
        pes.u16(if (unbounded && pesLength > 0xFFFF) 0 else pesLength)
        pes.u8(0x84) // '10', not scrambled, data_alignment_indicator = 1
        pes.u8(if (pts90k != null) 0x80 else 0x00)
        pes.u8(headerDataLength)
        if (pts90k != null) {
            pes.u8(0x21 or ((pts90k ushr 29).toInt() and 0x0E))
            pes.u16(((pts90k ushr 14).toInt() and 0xFFFE) or 1)
            pes.u16(((pts90k shl 1).toInt() and 0xFFFE) or 1)
        }
    }

    private fun packetize(pid: Int, data: ByteSink, pcrUs: Long? = null, randomAccess: Boolean = false) {
        var pos = 0
        var first = true
        while (pos < data.size) {
            val remaining = data.size - pos
            val needAf = first && (pcrUs != null || randomAccess)
            var afLen = if (needAf) 2 + (if (pcrUs != null) 6 else 0) else 0 // length byte + flags (+ PCR)
            var payload = minOf(remaining, TsConst.PACKET_SIZE - 4 - afLen)
            if (payload < TsConst.PACKET_SIZE - 4 - afLen) {
                // Last packet: pad with adaptation-field stuffing.
                afLen = TsConst.PACKET_SIZE - 4 - payload
            }
            var i = header(pid, pusi = first, hasAf = afLen > 0, hasPayload = true)
            if (afLen > 0) i = adaptationField(i, afLen, if (first) pcrUs else null, first && randomAccess)
            for (k in 0 until payload) packet[i + k] = data.byteAt(pos + k)
            pos += payload
            first = false
            output.add(packet)
        }
    }

    private fun writePcrOnlyPacket(nowUs: Long) {
        val i = header(config.videoPid, pusi = false, hasAf = true, hasPayload = false)
        adaptationField(i, TsConst.PACKET_SIZE - 4, nowUs, randomAccess = false)
        output.add(packet)
    }

    /** Writes the 4-byte TS header; returns the next write index. */
    private fun header(pid: Int, pusi: Boolean, hasAf: Boolean, hasPayload: Boolean): Int {
        val counter = cc[pid] ?: 0
        if (hasPayload) cc[pid] = (counter + 1) and 0x0F
        packet[0] = TsConst.SYNC_BYTE.toByte()
        packet[1] = ((if (pusi) 0x40 else 0) or (pid ushr 8 and 0x1F)).toByte()
        packet[2] = pid.toByte()
        val afc = (if (hasAf) 0x20 else 0) or (if (hasPayload) 0x10 else 0)
        packet[3] = (afc or (counter and 0x0F)).toByte()
        return 4
    }

    /**
     * Writes an adaptation field occupying [total] bytes including its length byte.
     * Returns the next write index.
     */
    private fun adaptationField(start: Int, total: Int, pcrUs: Long?, randomAccess: Boolean): Int {
        var i = start
        packet[i++] = (total - 1).toByte()
        if (total == 1) return i
        packet[i++] = ((if (randomAccess) 0x40 else 0) or (if (pcrUs != null) 0x10 else 0)).toByte()
        if (pcrUs != null) {
            val pcr = pcr27m(pcrUs)
            val base = (pcr / 300) and MASK_33
            val ext = (pcr % 300).toInt()
            packet[i++] = (base ushr 25).toByte()
            packet[i++] = (base ushr 17).toByte()
            packet[i++] = (base ushr 9).toByte()
            packet[i++] = (base ushr 1).toByte()
            packet[i++] = (((base and 1L).toInt() shl 7) or 0x7E or (ext ushr 8)).toByte()
            packet[i++] = ext.toByte()
        }
        while (i < start + total) packet[i++] = 0xFF.toByte()
        return i
    }

    // --- PSI ---------------------------------------------------------------------------------

    private fun writePsi(nowUs: Long) {
        lastPsiUs = nowUs
        writeSection(TsConst.PAT_PID, pat())
        writeSection(config.pmtPid, pmt())
    }

    private fun writeSection(pid: Int, section: ByteArray) {
        val i = header(pid, pusi = true, hasAf = false, hasPayload = true)
        packet[i] = 0 // pointer_field
        System.arraycopy(section, 0, packet, i + 1, section.size)
        java.util.Arrays.fill(packet, i + 1 + section.size, TsConst.PACKET_SIZE, 0xFF.toByte())
        output.add(packet)
    }

    private fun pat(): ByteArray {
        val body = ByteSink(16)
        body.u16(config.programNumber)
        body.u16(0xE000 or config.pmtPid)
        return section(tableId = 0x00, extension = 1, body)
    }

    private fun pmt(): ByteArray {
        val programInfo = ByteSink(32)
        if (config.klvCarriage == KlvCarriage.SYNC) {
            // metadata_pointer_descriptor (ISO 13818-1 §2.6.58)
            programInfo.u8(0x25).u8(15)
            programInfo.u16(0xFFFF).bytes(TsConst.KLVA)
            programInfo.u8(0xFF).bytes(TsConst.KLVA)
            programInfo.u8(0x00) // metadata_service_id
            programInfo.u8(0x1F) // locator_record_flag=0, MPEG_carriage_flags=00, reserved
            programInfo.u16(config.programNumber)
        }

        val body = ByteSink(128)
        body.u16(0xE000 or config.videoPid) // PCR_PID
        body.u16(0xF000 or programInfo.size)
        body.bytes(programInfo.toByteArray())

        body.u8(config.videoCodec.streamType).u16(0xE000 or config.videoPid).u16(0xF000)

        val klvInfo = ByteSink(32)
        if (config.klvCarriage == KlvCarriage.SYNC) {
            // metadata_descriptor (§2.6.60)
            klvInfo.u8(0x26).u8(13)
            klvInfo.u16(0xFFFF).bytes(TsConst.KLVA)
            klvInfo.u8(0xFF).bytes(TsConst.KLVA)
            klvInfo.u8(0x00) // metadata_service_id
            klvInfo.u8(0x0F) // decoder_config_flags=000, DSM-CC_flag=0, reserved
            // metadata_STD_descriptor (§2.6.62): leak rates in 400 bit/s, buffer in 1024 bytes.
            klvInfo.u8(0x27).u8(9)
            klvInfo.u24(0xC00000 or METADATA_LEAK_RATE)
            klvInfo.u24(0xC00000 or METADATA_BUFFER_KIB)
            klvInfo.u24(0xC00000 or METADATA_LEAK_RATE)
            body.u8(TsConst.STREAM_TYPE_METADATA_PES)
        } else {
            klvInfo.u8(0x05).u8(4).bytes(TsConst.KLVA) // registration_descriptor
            body.u8(TsConst.STREAM_TYPE_PRIVATE_PES)
        }
        body.u16(0xE000 or config.klvPid).u16(0xF000 or klvInfo.size).bytes(klvInfo.toByteArray())

        if (config.audio) {
            body.u8(TsConst.STREAM_TYPE_AAC_ADTS).u16(0xE000 or config.audioPid).u16(0xF000)
        }
        return section(tableId = 0x02, extension = config.programNumber, body)
    }

    private fun section(tableId: Int, extension: Int, body: ByteSink): ByteArray {
        val s = ByteSink(body.size + 12)
        s.u8(tableId)
        s.u16(0xB000 or (body.size + 9)) // syntax=1, '0', reserved, section_length
        s.u16(extension)
        s.u8(0xC1) // reserved, version 0, current_next=1
        s.u8(0x00).u8(0x00) // section_number, last_section_number
        s.bytes(body.toByteArray())
        val bytes = s.toByteArray()
        s.u32(Crc32Mpeg.compute(bytes).toLong() and 0xFFFFFFFFL)
        return s.toByteArray()
    }

    companion object {
        private const val MASK_33 = (1L shl 33) - 1
        private const val METADATA_LEAK_RATE = 2500 // 1 Mbit/s
        private const val METADATA_BUFFER_KIB = 8
    }
}

private fun ByteSink.byteAt(i: Int): Byte = this[i].toByte()
