package app.sparrow.core.ts

/** CRC-32/MPEG-2 used by PSI sections (poly 0x04C11DB7, init 0xFFFFFFFF, no reflection). */
object Crc32Mpeg {
    private val table = IntArray(256) { i ->
        var c = i shl 24
        repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        c
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = -1
        for (i in offset until offset + length) {
            crc = (crc shl 8) xor table[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }
}
