package app.sparrow.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import app.sparrow.core.config.AudioSettings
import app.sparrow.core.ts.Adts
import app.sparrow.log.AppLog

/** Microphone → AAC-LC → ADTS frames time-stamped on the capture clock (VID-5). */
class AudioEncoder(
    private val settings: AudioSettings,
    private val onFrame: (captureUs: Long, adts: ByteArray) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        running = true
        thread = Thread({ loop() }, "audio-encoder").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val channelMask = if (settings.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(settings.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER, settings.sampleRate, channelMask,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 8192),
        )
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, settings.sampleRate, settings.channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateKbps * 1000)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            codec.start()
            record.startRecording()
            val bytesPerFrame = 2 * settings.channels
            var framesRead = 0L
            val ts = AudioTimestamp()
            var anchorUs = SensorHub.nowUs()
            var anchorFrames = 0L
            val info = MediaCodec.BufferInfo()
            while (running) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val n = record.read(buf, buf.capacity())
                    if (n > 0) {
                        if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                            anchorUs = ts.nanoTime / 1000
                            anchorFrames = ts.framePosition
                        }
                        val ptsUs = anchorUs + (framesRead - anchorFrames) * 1_000_000 / settings.sampleRate
                        codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                        framesRead += n / bytesPerFrame
                    } else {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    }
                }
                var outIndex = codec.dequeueOutputBuffer(info, 0)
                while (outIndex >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val out = codec.getOutputBuffer(outIndex)!!
                        val raw = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(raw)
                        onFrame(info.presentationTimeUs, Adts.frame(raw, settings.sampleRate, settings.channels))
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            AppLog.e("Audio encoder stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }
}
