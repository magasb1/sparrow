package app.sparrow.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import app.sparrow.core.config.RateControl
import app.sparrow.core.config.VideoSettings
import app.sparrow.core.ts.AccessUnitAssembler
import app.sparrow.core.ts.VideoCodec
import app.sparrow.log.AppLog

/**
 * Hardware H.264/H.265 encoder fed through an input Surface (VID-1..3). Each output access unit
 * is delivered with SPS/PPS before IDR frames, time-stamped in camera-clock microseconds.
 */
class VideoEncoder(
    settings: VideoSettings,
    val size: Size,
    private val onAccessUnit: (cameraUs: Long, accessUnit: ByteArray, keyFrame: Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    val codec: VideoCodec = if (settings.codec == VideoCodec.H265 && !supports(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
        AppLog.w("No hardware H.265 encoder; using H.264")
        VideoCodec.H264
    } else settings.codec

    private val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    private val thread = HandlerThread("video-encoder").apply { start() }
    private val mediaCodec = MediaCodec.createEncoderByType(mime)
    private val assembler = AccessUnitAssembler(codec)
    lateinit var inputSurface: Surface
        private set

    @Volatile var bitrateKbps: Int = settings.bitrateKbps
        private set

    /** True when the encoder takes per-frame QP offset rectangles (Android 15+). */
    @Volatile var roiSupported = false
        private set
    private var lastRoi: String? = null

    init {
        val format = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, settings.gopSeconds.toFloat())
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / settings.fps * 3)
            // Drop camera frames beyond the configured rate (many cameras only offer 15–30 fps ranges).
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, settings.fps.toFloat())
            val mode = if (settings.rateControl == RateControl.CBR) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            if (supportsMode(mode)) setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
        }
        val profiled = MediaFormat(format).apply {
            applyBestProfile(this, size)
            if (roiCapable()) setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_Roi, true)
        }
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buffer = codec.getOutputBuffer(index) ?: return
                    if (info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            assembler.setCodecConfig(data)
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            onAccessUnit(info.presentationTimeUs, assembler.assemble(data, key), key)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    AppLog.w("Encoder output after stop", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                AppLog.e("Video encoder error", e)
                if (!e.isTransient) onError("Video encoder failed")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                AppLog.i("Encoder output format $format")
            }
        }
        mediaCodec.setCallback(callback, Handler(thread.looper))
        try {
            try {
                mediaCodec.configure(profiled, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                roiSupported = roiCapable()
            } catch (e: Exception) {
                AppLog.w("Encoder rejected the requested profile; using its default", e)
                roiSupported = false
                mediaCodec.reset()
                mediaCodec.setCallback(callback, Handler(thread.looper))
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = mediaCodec.createInputSurface()
        } catch (e: Exception) {
            mediaCodec.release()
            thread.quitSafely()
            throw e
        }
        AppLog.i("Video encoder ${mediaCodec.name}: $codec ${size.width}x${size.height} ${settings.fps} fps ${settings.bitrateKbps} kbit/s")
    }

    fun start() = mediaCodec.start()

    /** Changes the bitrate on the fly (ABR). */
    fun setBitrate(kbps: Int) {
        if (kbps == bitrateKbps) return
        bitrateKbps = kbps
        runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) }) }
    }

    private fun roiCapable(): Boolean = android.os.Build.VERSION.SDK_INT >= 35 && runCatching {
        mediaCodec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_Roi)
    }.getOrDefault(false)

    /**
     * Sets the quality regions for the coming frames in MediaCodec's qp-offset-rects format; an
     * empty string clears them. Repeated values are not resent.
     */
    fun setRoi(rects: String) {
        if (!roiSupported || android.os.Build.VERSION.SDK_INT < 35 || rects == lastRoi) return
        lastRoi = rects
        // A neutral 16×16 region clears earlier ones on encoders that keep the last list.
        val value = rects.ifEmpty { "0,0-15,15=0" }
        runCatching { mediaCodec.setParameters(Bundle().apply { putString(MediaCodec.PARAMETER_KEY_QP_OFFSET_RECTS, value) }) }
            .onFailure { AppLog.w("ROI rejected; disabling", it); roiSupported = false }
    }

    /** Requests an IDR frame, e.g. when a receiver (re)connects. */
    fun requestKeyFrame() {
        runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        runCatching { mediaCodec.stop() }
        runCatching { mediaCodec.release() }
        runCatching { inputSurface.release() }
        thread.quitSafely()
    }

    private fun supportsMode(mode: Int): Boolean = runCatching {
        mediaCodec.codecInfo.getCapabilitiesForType(mime).encoderCapabilities!!.isBitrateModeSupported(mode)
    }.getOrDefault(false)

    /**
     * H.264 High profile (about 10–15% fewer bits than the Baseline many encoders default to) or
     * HEVC Main, when the encoder offers it. No B-frames either way, for latency.
     */
    private fun applyBestProfile(format: MediaFormat, size: Size) {
        val levels = runCatching { mediaCodec.codecInfo.getCapabilitiesForType(mime).profileLevels.toList() }.getOrDefault(emptyList())
        if (codec == VideoCodec.H264) {
            val high = levels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }.maxByOrNull { it.level } ?: return
            val wanted = if (size.width * size.height <= 1280 * 720) MediaCodecInfo.CodecProfileLevel.AVCLevel31 else MediaCodecInfo.CodecProfileLevel.AVCLevel4
            format.setInteger(MediaFormat.KEY_PROFILE, high.profile)
            format.setInteger(MediaFormat.KEY_LEVEL, minOf(wanted, high.level))
        } else if (levels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        }
    }

    companion object {
        /** True if a hardware encoder for [mime] accepts [width]×[height] (portrait sizes are not universal). */
        fun supportsSize(mime: String, width: Int, height: Int): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching { info.getCapabilitiesForType(mime).videoCapabilities!!.isSizeSupported(width, height) }.getOrDefault(false)
        }

        fun supports(mime: String): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }
}
