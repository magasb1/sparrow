package app.sparrow.core.util

/** Growable big-endian byte buffer used by the KLV encoder and the TS muxer. */
class ByteSink(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
        }
    }

    fun u8(v: Int): ByteSink {
        ensure(1)
        buf[size++] = v.toByte()
        return this
    }

    fun u16(v: Int): ByteSink = u8(v ushr 8).u8(v)

    fun u24(v: Int): ByteSink = u8(v ushr 16).u8(v ushr 8).u8(v)

    fun u32(v: Long): ByteSink = u8((v ushr 24).toInt()).u8((v ushr 16).toInt()).u8((v ushr 8).toInt()).u8(v.toInt())

    /** Writes the low [n] bytes of [v], most significant first. */
    fun uN(v: Long, n: Int): ByteSink {
        for (i in n - 1 downTo 0) u8((v ushr (8 * i)).toInt())
        return this
    }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off): ByteSink {
        ensure(len)
        System.arraycopy(b, off, buf, size, len)
        size += len
        return this
    }

    fun fill(v: Int, count: Int): ByteSink {
        ensure(count)
        java.util.Arrays.fill(buf, size, size + count, v.toByte())
        size += count
        return this
    }

    operator fun get(i: Int): Int = buf[i].toInt() and 0xFF

    operator fun set(i: Int, v: Int) {
        buf[i] = v.toByte()
    }

    fun reset() {
        size = 0
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
