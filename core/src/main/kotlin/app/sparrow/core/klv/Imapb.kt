package app.sparrow.core.klv

import app.sparrow.core.util.ByteSink
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * MISB ST 1201 IMAPB: a float in [min, max] mapped onto an unsigned integer of [length] bytes.
 * Used by ST 0903 VMTI for FOVs and target locations.
 */
class Imapb(val min: Double, val max: Double, val length: Int) {
    private val bPow = ceil(ln(max - min) / ln(2.0)).toInt()
    private val dPow = 8 * length - 1
    private val sF = 2.0.pow(dPow - bPow)
    private val sR = 2.0.pow(bPow - dPow)
    private val zOffset = if (min < 0 && max > 0) sF * min - floor(sF * min) else 0.0

    /** Resolution of one integer step. */
    val step: Double get() = sR

    fun encode(value: Double): Long {
        require(!value.isNaN()) { "NaN is not encoded" }
        val v = value.coerceIn(min, max)
        // The top bit flags special values (ST 1201 §8), so a power-of-two range must not reach it.
        return (sF * (v - min) + zOffset).toLong().coerceAtMost((1L shl dPow) - 1)
    }

    fun decode(raw: Long): Double = sR * (raw - zOffset) + min

    fun write(out: ByteSink, value: Double) {
        out.uN(encode(value), length)
    }

    fun read(data: ByteArray, offset: Int): Double {
        var raw = 0L
        for (i in 0 until length) raw = (raw shl 8) or (data[offset + i].toLong() and 0xFF)
        return decode(raw)
    }
}
