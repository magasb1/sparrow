package app.sparrow.stream

import android.content.Context
import android.os.Environment
import android.util.Size
import app.sparrow.core.config.Profile
import app.sparrow.core.config.StabilizationMode
import app.sparrow.core.stab.Stabilizer
import app.sparrow.core.geo.LensFacing
import app.sparrow.core.geo.StreamOrientation
import app.sparrow.core.klv.ComposerSettings
import app.sparrow.core.klv.MetadataComposer
import app.sparrow.core.klv.St0601Encoder
import app.sparrow.core.klv.Tag
import app.sparrow.core.srt.BitrateController
import app.sparrow.core.srt.LinkSample
import app.sparrow.core.ts.TsChunker
import app.sparrow.core.ts.TsConfig
import app.sparrow.core.ts.TsMuxer
import app.sparrow.log.AppLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One streaming session: camera → encoder → TS muxer (+ KLV, + audio) → SRT and optional local
 * recording. Video and KLV are stamped from one capture clock (MUX-3, NFR-2).
 */
class StreamPipeline(
    private val context: Context,
    private val profile: Profile,
    /** Display rotation (0/90/180/270) when Go live was pressed; the stream is upright for it. */
    private val displayRotationDeg: Int,
    private val onFatalError: (String) -> Unit,
) {
    private val sensors = SensorHub(context)
    private val cameraInfo: CameraInfo = CameraInfo.all(context).let { all ->
        all.firstOrNull { it.id == profile.video.cameraId }
            ?: all.firstOrNull { it.facing == app.sparrow.core.geo.LensFacing.BACK }
            ?: all.firstOrNull()
            ?: error("No camera available")
    }
    /** Camera buffer size, in sensor (landscape) orientation. */
    private val cameraSize = CameraInfo.chooseSize(cameraInfo.encoderSizes, profile.video.width, profile.video.height)
    private val sensorAspect = cameraSize.width.toDouble() / cameraSize.height
    private val orientation = StreamOrientation.of(
        cameraInfo.sensorOrientation, displayRotationDeg, cameraInfo.facing, cameraSize.width, cameraSize.height,
    ).let { o ->
        val mime = if (profile.video.codec == app.sparrow.core.ts.VideoCodec.H265) "video/hevc" else "video/avc"
        if (o.rotationDeg != 0 && !VideoEncoder.supportsSize(mime, o.width, o.height)) {
            AppLog.w("Encoder cannot do ${o.width}x${o.height}; streaming in sensor orientation")
            StreamOrientation.of(cameraInfo.sensorOrientation, cameraInfo.sensorOrientation, LensFacing.BACK, cameraSize.width, cameraSize.height)
        } else o
    }
    private val size = Size(orientation.width, orientation.height)
    private var renderer: FrameRenderer? = null

    /** Gyro stabilisation (and levelling); null unless the profile asks for it and there is a gyroscope. */
    private val stabilizer: Stabilizer? = if (profile.video.stabilizationMode == StabilizationMode.GYRO) {
        val (h, v) = cameraInfo.fieldOfView(cameraSize.width.toDouble() / cameraSize.height, profile.video.zoomRatio.coerceIn(cameraInfo.zoomRange))
        Stabilizer(cameraInfo.facing, cameraInfo.sensorOrientation, orientation.rotationDeg, h, v, profile.video.levelHorizon)
            .also { st -> sensors.gyroSink = { t, x, y, z -> st.onGyro(t, x, y, z) } }
    } else null

    private val composer = MetadataComposer(
        ComposerSettings(
            missionId = profile.metadata.missionId,
            platformDesignation = profile.metadata.platformDesignation,
            imageSourceSensor = profile.metadata.imageSourceSensor,
            callSign = profile.metadata.callSign,
            groundElevation = profile.metadata.groundElevationM,
            maxFixAgeUs = SensorHub.FIX_TIMEOUT_MS * 1000,
        ),
    )
    private val klvEncoder = St0601Encoder(
        version = profile.metadata.lsVersion,
        enabledTags = profile.metadata.enabledTags.mapNotNull { Tag.of(it) }.toSet(),
    )
    private val klvIntervalUs = (1_000_000 / profile.metadata.rateHz).toLong()
    private val halfFrameUs = 500_000L / profile.video.fps
    private var nextKlvUs = Long.MIN_VALUE
    private var lastInspectorUs = 0L

    private var recordingFile: File? = null
    @Volatile private var recorder: OutputStream? = null
    private var stopped = false

    private val sender = SrtSender(profile.destination, object : SrtSender.Listener {
        override fun onState(state: LinkState) = StreamController.update {
            val since = if (state == LinkState.Live) it.liveSinceMs ?: android.os.SystemClock.elapsedRealtime() else null
            it.copy(link = state, liveSinceMs = since)
        }

        override fun onConnected() {
            // Let a new receiver start decoding straight away.
            encoder.requestKeyFrame()
        }

        override fun onStats(sample: LinkSample, sendKbps: Double) {
            val loss = if (sample.packetsSent > 0) 100.0 * sample.packetsLost / sample.packetsSent else 0.0
            abr?.onSample(sample)?.let {
                AppLog.i("ABR: video bitrate → $it kbit/s")
                encoder.setBitrate(it)
            }
            StreamController.update {
                it.copy(sendKbps = sendKbps, rttMs = sample.rttMs, lossPercent = loss, targetKbps = encoder.bitrateKbps)
            }
        }
    })

    private val chunker = TsChunker { chunk ->
        sender.send(chunk)
        recorder?.let { r -> runCatching { r.write(chunk) } }
    }

    private val encoder: VideoEncoder = VideoEncoder(
        profile.video, size,
        onAccessUnit = { cameraUs, au, key -> onVideo(cameraUs, au, key) },
        onError = onFatalError,
    )

    private val muxer = TsMuxer(
        TsConfig(
            videoCodec = encoder.codec,
            klvCarriage = profile.metadata.carriage,
            audio = profile.audio.enabled,
            ptsDelayUs = profile.advanced.ptsDelayMs * 1000L,
        ),
        chunker,
    )

    private val abr = if (profile.advanced.adaptiveBitrate) {
        BitrateController(
            minKbps = profile.advanced.minBitrateKbps,
            // Never cap below the configured bitrate; a higher maximum lets ABR probe upwards.
            maxKbps = maxOf(profile.advanced.maxBitrateKbps, profile.video.bitrateKbps),
            initialKbps = profile.video.bitrateKbps,
            latencyMs = profile.destination.latencyMs,
        )
    } else null

    private val camera = CameraSource(context, cameraInfo, profile.video, onFatalError)

    private val audio = if (profile.audio.enabled) {
        AudioEncoder(profile.audio) { captureUs, adts -> muxer.writeAudio(captureUs, adts, SensorHub.nowUs()) }
    } else null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "mux-ticker") }

    fun start() {
        AppLog.i("Starting '${profile.name}' with ${cameraInfo.label} at ${size.width}x${size.height}")
        if (profile.advanced.recordLocally) recorder = openRecording()
        sensors.start()
        encoder.start()
        val cameraTarget = if (orientation.rotationDeg == 0 && stabilizer == null) encoder.inputSurface else {
            val mapping = stabilizer?.let { st ->
                { timestampNs: Long ->
                    val captureUs = camera.toCaptureUs(timestampNs / 1000)
                    st.frame(captureUs, sensors.poseAt(captureUs, cameraInfo.facing, cameraInfo.sensorOrientation)).homography
                }
            }
            FrameRenderer(
                encoder.inputSurface, cameraSize.width, cameraSize.height, orientation.rotationDeg, size.width, size.height, mapping,
            ).also { renderer = it }.inputSurface
        }
        camera.start(cameraTarget)
        audio?.start()
        sender.start()
        scheduler.scheduleWithFixedDelay({ muxer.tick(SensorHub.nowUs()) }, 10, 10, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay({ publishStatus() }, 0, 1, TimeUnit.SECONDS)
        StreamController.update {
            it.copy(
                profileName = profile.name,
                targetKbps = profile.video.bitrateKbps,
                resolution = "${size.width}×${size.height} ${encoder.codec}",
                displayRotationDeg = displayRotationDeg,
                recordingPath = recordingFile?.absolutePath,
            )
        }
    }

    fun setPreview(target: PreviewTarget?) = camera.setPreview(target?.takeIf { it.surface.isValid })


    fun setZoom(ratio: Double) {
        camera.setZoom(ratio)
        stabilizer?.let { st ->
            val (h, v) = cameraInfo.fieldOfView(sensorAspect, ratio.coerceIn(cameraInfo.zoomRange))
            st.setFieldOfView(h, v)
        }
    }

    val zoomRange get() = cameraInfo.zoomRange

    fun onNetworkChanged() = sender.onNetworkChanged()

    /** Releases everything; safe after a partial or failed start, and idempotent. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        AppLog.i("Stopping stream")
        runCatching { scheduler.shutdownNow() }
        runCatching { sender.stop() }
        runCatching { audio?.stop() }
        // The camera must stop writing into the encoder surface before the encoder is released.
        runCatching { camera.close() }
        runCatching { renderer?.release() }
        runCatching { encoder.release() }
        runCatching { sensors.stop() }
        runCatching { muxer.flush() }
        recorder?.let { runCatching { it.close() } }
        recorder = null
    }

    private fun onVideo(cameraUs: Long, accessUnit: ByteArray, keyFrame: Boolean) {
        val captureUs = camera.toCaptureUs(cameraUs)
        val now = SensorHub.nowUs()
        muxer.writeVideo(captureUs, accessUnit, keyFrame, now)
        // KLV samples are aligned to video frames so their PTS equals the frame's (NFR-2).
        // Samples land on the frame nearest each scheduled time, so the average rate is exact even
        // when it does not divide the frame rate (10 Hz at 15 fps).
        if (nextKlvUs == Long.MIN_VALUE || captureUs >= nextKlvUs - halfFrameUs) {
            nextKlvUs = if (nextKlvUs == Long.MIN_VALUE || nextKlvUs + klvIntervalUs < captureUs) captureUs + klvIntervalUs
            else nextKlvUs + klvIntervalUs
            writeKlv(captureUs, now)
        }
    }

    private fun writeKlv(captureUs: Long, now: Long) {
        // FOV in sensor orientation, then swapped if the stream is turned on its side.
        val (sensorH, sensorV) = cameraInfo.fieldOfView(sensorAspect, camera.zoomRatio)
        val (rawH, rawV) = if (orientation.swapsAxes) sensorV to sensorH else sensorH to sensorV
        // Stabilisation shows a cropped, steadied virtual camera: describe that one.
        val crop = stabilizer?.cropScale ?: 1.0
        val hfov = Stabilizer.croppedFov(rawH, crop)
        val vfov = Stabilizer.croppedFov(rawV, crop)
        val pose = stabilizer?.let { st ->
            sensors.poseAt(captureUs, cameraInfo.facing, cameraInfo.sensorOrientation)
                ?.corrected(st.correctionAt(captureUs))
                ?.rotatedImage(orientation.rotationDeg)
        } ?: sensors.poseAt(captureUs, cameraInfo.facing, orientation.remainingSensorRotationDeg)
        val metadata = composer.compose(
            timestampUtcMicros = sensors.utcMicros(captureUs),
            captureUs = captureUs,
            fix = sensors.latestFix,
            pose = pose,
            horizontalFovDeg = hfov,
            verticalFovDeg = vfov,
        )
        val packet = klvEncoder.encode(metadata)
        muxer.writeKlv(captureUs, packet, now)
        if (captureUs - lastInspectorUs > 250_000) {
            lastInspectorUs = captureUs
            StreamController.publishKlv(packet)
        }
    }

    private fun publishStatus() {
        StreamController.update { it.copy(gnss = sensors.gnssStatus(SensorHub.nowUs())) }
    }

    private fun openRecording(): OutputStream? = runCatching {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val name = "sparrow-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".ts"
        val file = File(dir, name)
        recordingFile = file
        AppLog.i("Recording to ${file.absolutePath}")
        BufferedOutputStream(file.outputStream(), 256 * 1024)
    }.onFailure { AppLog.e("Could not open recording", it) }.getOrNull()
}
