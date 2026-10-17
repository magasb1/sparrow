package app.sparrow.core.klv

import app.sparrow.core.util.ByteSink

/** BER length and BER-OID tag coding (SMPTE ST 336, MISB ST 0601 §6). */
object Ber {
    fun writeLength(out: ByteSink, length: Int) {
        require(length >= 0)
        if (length < 0x80) {
            out.u8(length)
            return
        }
        val n = when {
            length <= 0xFF -> 1
            length <= 0xFFFF -> 2
            length <= 0xFFFFFF -> 3
            else -> 4
        }
        out.u8(0x80 or n)
        out.uN(length.toLong(), n)
    }

    /** Returns (length, bytesConsumed). */
    fun readLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        val first = data[offset].toInt() and 0xFF
        if (first < 0x80) return first to 1
        val n = first and 0x7F
        require(n in 1..4) { "unsupported BER length of $n bytes" }
        var len = 0
        for (i in 1..n) len = (len shl 8) or (data[offset + i].toInt() and 0xFF)
        return len to n + 1
    }

    fun writeOid(out: ByteSink, value: Int) {
        require(value >= 0)
        if (value < 0x80) {
            out.u8(value)
            return
        }
        val groups = ArrayList<Int>()
        var v = value
        while (v > 0) {
            groups.add(v and 0x7F)
            v = v ushr 7
        }
        for (i in groups.indices.reversed()) {
            out.u8(groups[i] or if (i > 0) 0x80 else 0)
        }
    }

    /** Returns (value, bytesConsumed). */
    fun readOid(data: ByteArray, offset: Int): Pair<Int, Int> {
        var value = 0
        var i = offset
        while (true) {
            val b = data[i++].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) break
        }
        return value to i - offset
    }
}
