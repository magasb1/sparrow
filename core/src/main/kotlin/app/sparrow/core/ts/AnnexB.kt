package app.sparrow.core.ts

import app.sparrow.core.util.ByteSink

/** Annex B NAL unit helpers for H.264 and H.265 elementary streams. */
object AnnexB {
    data class Nal(val offset: Int, val length: Int, val type: Int)

    /** Splits an Annex B buffer into NAL units (start codes excluded). */
    fun split(data: ByteArray, codec: VideoCodec): List<Nal> {
        val starts = ArrayList<Pair<Int, Int>>() // (start code position, payload position)
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts.add(i to i + 3); i += 3; continue
                }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts.add(i to i + 4); i += 4; continue
                }
            }
            i++
        }
        return starts.mapIndexed { idx, (_, payload) ->
            val end = if (idx + 1 < starts.size) starts[idx + 1].first else data.size
            val header = data.getOrNull(payload)?.toInt() ?: 0
            val type = when (codec) {
                VideoCodec.H264 -> header and 0x1F
                VideoCodec.H265 -> (header ushr 1) and 0x3F
            }
            Nal(payload, end - payload, type)
        }
    }

    fun isParameterSet(codec: VideoCodec, type: Int) = when (codec) {
        VideoCodec.H264 -> type == 7 || type == 8
        VideoCodec.H265 -> type in 32..34
    }

    fun isAud(codec: VideoCodec, type: Int) = when (codec) {
        VideoCodec.H264 -> type == 9
        VideoCodec.H265 -> type == 35
    }
}

/**
 * Turns encoder output into TS-ready access units (VID-3): an access unit delimiter first, and the
 * latest parameter sets (SPS/PPS, plus VPS for H.265) before every IDR frame.
 */
class AccessUnitAssembler(private val codec: VideoCodec) {
    private var parameterSets: ByteArray? = null
    private val out = ByteSink(512 * 1024)

    /** Stores codec config output (MediaCodec BUFFER_FLAG_CODEC_CONFIG). */
    fun setCodecConfig(config: ByteArray) {
        parameterSets = config.copyOf()
    }

    val hasCodecConfig: Boolean get() = parameterSets != null

    fun assemble(frame: ByteArray, keyFrame: Boolean): ByteArray {
        val nals = AnnexB.split(frame, codec)
        out.reset()
        out.bytes(START_CODE)
        when (codec) {
            VideoCodec.H264 -> out.u8(0x09).u8(0xF0)
            VideoCodec.H265 -> out.u8(0x46).u8(0x01).u8(0x50)
        }
        val hasParams = nals.any { AnnexB.isParameterSet(codec, it.type) }
        if (keyFrame && !hasParams) parameterSets?.let { out.bytes(it) }
        for (nal in nals) {
            if (AnnexB.isAud(codec, nal.type)) continue
            out.bytes(START_CODE)
            out.bytes(frame, nal.offset, nal.length)
        }
        return out.toByteArray()
    }

    private companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
