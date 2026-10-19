package app.sparrow.core.ts

import java.io.ByteArrayOutputStream

/**
 * Minimal MPEG-TS demuxer: reassembles PES packets and PSI per PID and checks continuity.
 * Used by tests and diagnostics, not on the streaming path.
 */
class TsDemuxer {
    data class Pes(val pid: Int, val streamId: Int, val pts90k: Long?, val payload: ByteArray, val randomAccess: Boolean)

    val pesPackets = ArrayList<Pes>()
    val sections = ArrayList<Pair<Int, ByteArray>>()
    val pcrs = ArrayList<Long>() // 27 MHz
    var continuityErrors = 0
        private set
    var packetCount = 0
        private set

    private val buffers = HashMap<Int, ByteArrayOutputStream>()
    private val randomAccess = HashMap<Int, Boolean>()
    private val lastCc = HashMap<Int, Int>()
    var psiPids: Set<Int> = setOf(TsConst.PAT_PID)

    fun feed(data: ByteArray) {
        require(data.size % TsConst.PACKET_SIZE == 0) { "not whole TS packets" }
        for (off in data.indices step TsConst.PACKET_SIZE) packet(data, off)
    }

    fun finish() {
        for (pid in buffers.keys.toList()) flushPes(pid)
    }

    private fun packet(d: ByteArray, o: Int) {
        packetCount++
        require(d[o].toInt() and 0xFF == TsConst.SYNC_BYTE) { "lost sync at $o" }
        val pusi = d[o + 1].toInt() and 0x40 != 0
        val pid = ((d[o + 1].toInt() and 0x1F) shl 8) or (d[o + 2].toInt() and 0xFF)
        val afc = (d[o + 3].toInt() ushr 4) and 0x03
        val cc = d[o + 3].toInt() and 0x0F
        var i = o + 4
        var ra = false
        if (afc and 0x02 != 0) {
            val afLen = d[i].toInt() and 0xFF
            if (afLen > 0) {
                val flags = d[i + 1].toInt() and 0xFF
                if (flags and 0x40 != 0) ra = true
                if (flags and 0x10 != 0) {
                    var base = 0L
                    for (k in 0 until 4) base = (base shl 8) or (d[i + 2 + k].toLong() and 0xFF)
                    base = (base shl 1) or ((d[i + 6].toLong() and 0x80) ushr 7)
                    val ext = ((d[i + 6].toInt() and 0x01) shl 8) or (d[i + 7].toInt() and 0xFF)
                    pcrs.add(base * 300 + ext)
                }
            }
            i += 1 + afLen
        }
        // A packet without payload repeats the previous counter (§2.4.3.3); a jump counts as loss.
        val hasPayload = afc and 0x01 != 0
        lastCc[pid]?.let { if ((if (hasPayload) (it + 1) and 0x0F else it) != cc) continuityErrors++ }
        lastCc[pid] = cc
        if (!hasPayload) return

        if (pid in psiPids) {
            if (pusi) {
                val pointer = d[i].toInt() and 0xFF
                val start = i + 1 + pointer
                val len = (((d[start + 1].toInt() and 0x0F) shl 8) or (d[start + 2].toInt() and 0xFF)) + 3
                sections.add(pid to d.copyOfRange(start, start + len))
            }
            return
        }
        if (pusi) {
            flushPes(pid)
            randomAccess[pid] = ra
        }
        buffers.getOrPut(pid) { ByteArrayOutputStream() }.write(d, i, o + TsConst.PACKET_SIZE - i)
    }

    private fun flushPes(pid: Int) {
        val buf = buffers.remove(pid)?.toByteArray() ?: return
        val ra = randomAccess.remove(pid) == true
        if (buf.size < 9 || buf[0].toInt() != 0 || buf[1].toInt() != 0 || buf[2].toInt() != 1) return
        val streamId = buf[3].toInt() and 0xFF
        val pesLen = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val flags = buf[7].toInt() and 0xFF
        val hdrLen = buf[8].toInt() and 0xFF
        val pts = if (flags and 0x80 != 0) {
            ((buf[9].toLong() and 0x0E) shl 29) or
                ((buf[10].toLong() and 0xFF) shl 22) or ((buf[11].toLong() and 0xFE) shl 14) or
                ((buf[12].toLong() and 0xFF) shl 7) or ((buf[13].toLong() and 0xFE) ushr 1)
        } else null
        val end = if (pesLen == 0) buf.size else 6 + pesLen
        pesPackets.add(Pes(pid, streamId, pts, buf.copyOfRange(9 + hdrLen, end), ra))
    }
}
