package app.sparrow.stream

import android.content.Context
import android.os.Environment
import android.util.Size
import app.sparrow.core.config.Profile
import app.sparrow.core.cv.TargetGeometry
import app.sparrow.core.cv.TargetReport
import app.sparrow.core.geo.CameraPose
import app.sparrow.core.klv.LatLon
import app.sparrow.core.klv.PositionFix
import app.sparrow.core.klv.Vmti
import app.sparrow.cv.DetectionEngine
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
    private val sensors = SensorHub(context, profile.metadata.headingOffsetDeg)
    private val cameraInfo: CameraInfo = CameraInfo.all(context).let { all ->
        all.firstOrNull { it.id == profile.video.cameraId }
            ?: all.firstOrNull { it.facing == app.sparrow.core.geo.LensFacing.BACK }
            ?: all.firstOrNull()
            ?: error("No camera available")
    }
    /** Camera buffer size, in sensor (landscape) orientation. */
    private val cameraSize = cameraInfo.encoderSizes.let { all ->
        // Prefer sizes the chosen codec's encoder accepts; otherwise VideoEncoder falls back to H.264.
        val v = profile.video
        val encodable = all.filter { VideoEncoder.findEncoder(v.codec, it.width, it.height, v.fps) != null }
        CameraInfo.chooseSize(encodable.ifEmpty { all }, v.width, v.height)
    }
    private val sensorAspect = cameraSize.width.toDouble() / cameraSize.height
    private val orientation = StreamOrientation.of(
        cameraInfo.sensorOrientation, displayRotationDeg, cameraInfo.facing, cameraSize.width, cameraSize.height,
    ).let { o ->
        if (o.rotationDeg != 0 && VideoEncoder.chooseCodec(profile.video.codec, o.width, o.height, profile.video.fps) == null) {
            AppLog.w("Encoder cannot do ${o.width}x${o.height}; streaming in sensor orientation")
            StreamOrientation.of(cameraInfo.sensorOrientation, cameraInfo.sensorOrientation, LensFacing.BACK, cameraSize.width, cameraSize.height)
        } else o
    }
    private val size = Size(orientation.width, orientation.height)

    /**
     * Object detection on a low-resolution copy of the stream. Created the first time detection is
     * switched on, so a stream without detection carries no extra threads, buffers or camera output.
     */
    @Volatile private var detector: DetectionEngine? = null
    private val analysisSize = DetectionEngine.analysisSize(cameraInfo.yuvSizes, sensorAspect)
    private var renderer: RotatingRenderer? = null

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

    private val camera = CameraSource(context, cameraInfo, profile.video, onFatalError, onAnalysisUnavailable = { onAnalysisUnavailable() })

    private val audio = if (profile.audio.enabled) {
        AudioEncoder(profile.audio) { captureUs, adts -> muxer.writeAudio(captureUs, adts, SensorHub.nowUs()) }
    } else null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "mux-ticker") }

    fun start() {
        AppLog.i("Starting '${profile.name}' with ${cameraInfo.label} at ${size.width}x${size.height}")
        if (profile.advanced.recordLocally) recorder = openRecording()
        sensors.start()
        encoder.start()
        val cameraTarget = if (orientation.rotationDeg == 0) encoder.inputSurface else {
            RotatingRenderer(
                encoder.inputSurface, cameraSize.width, cameraSize.height, orientation.rotationDeg, size.width, size.height,
            ).also { renderer = it }.inputSurface
        }
        // Before start(), so the first capture session already includes the analysis stream.
        setDetectionEnabled(profile.detection.enabled)
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


    fun setZoom(ratio: Double) = camera.setZoom(ratio)

    /** Turns detection on or off live; the analysis stream is only in the camera session while on. */
    @Synchronized
    fun setDetectionEnabled(on: Boolean) {
        if (stopped) return
        if (on && (analysisSize == null || cameraInfo.id in CameraSource.analysisUnsupportedCameras)) {
            onAnalysisUnavailable()
            return
        }
        val d = detector ?: if (on) createDetector(analysisSize!!) else return
        if (d.enabled == on) return
        d.enabled = on
        if (on) {
            // Adding the output rebuilds the session once (a short gap in the video when live);
            // switching off keeps it, so the stream is not interrupted again.
            camera.setAnalysis(d.surface)
        } else {
            StreamController.publishTracks(emptyList())
            StreamController.update { it.copy(detection = null) }
        }
        AppLog.i("Object detection ${if (on) "on" else "off"}")
    }

    private fun createDetector(size: Size) = DetectionEngine(
        context, profile.detection, orientation.rotationDeg, size, { camera.toCaptureUs(it) },
    ) { stats -> StreamController.update { it.copy(detection = stats) } }.also { detector = it }

    private fun onAnalysisUnavailable() {
        detector?.enabled = false
        StreamController.publishTracks(emptyList())
        StreamController.update { it.copy(detection = null, detectionUnavailable = true) }
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
        runCatching { detector?.release() }
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
        // The overlay sits on the live preview, not on this (already encoded, older) frame: predict to now.
        detector?.takeIf { it.enabled }?.let { StreamController.publishTracks(it.snapshot(now)) }
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
        val (hfov, vfov) = if (orientation.swapsAxes) sensorV to sensorH else sensorH to sensorV
        // The frame timestamp is when the first row starts exposing; the picture as a whole shows
        // the pose at mid-exposure of the middle row, often a frame period later.
        val exposureUs = captureUs + camera.exposureMidOffsetUs
        val fix = sensors.latestFix
        val pose = sensors.poseAt(exposureUs, cameraInfo.facing, orientation.remainingSensorRotationDeg)
        val metadata = composer.compose(
            timestampUtcMicros = sensors.utcMicros(captureUs),
            captureUs = captureUs,
            fix = fix,
            pose = pose,
            horizontalFovDeg = hfov,
            verticalFovDeg = vfov,
        ).let { m -> vmti(captureUs, fix, pose, hfov, vfov)?.let { m.copy(vmti = it) } ?: m }
        val packet = klvEncoder.encode(metadata)
        muxer.writeKlv(captureUs, packet, now)
        if (captureUs - lastInspectorUs > 250_000) {
            lastInspectorUs = captureUs
            StreamController.publishKlv(packet)
        }
    }

    /** ST 0903 VMTI for the tracks at this frame (KLV Tag 74), or null when detection is off. */
    private fun vmti(captureUs: Long, fix: PositionFix?, pose: CameraPose?, hfov: Double, vfov: Double): ByteArray? {
        val d = detector?.takeIf { it.enabled && profile.detection.sendVmti } ?: return null
        val fresh = fix?.takeIf { captureUs - it.captureUs <= SensorHub.FIX_TIMEOUT_MS * 1000 }
        val geometry = TargetGeometry(
            sensor = fresh?.let { LatLon(it.latitude, it.longitude) },
            sensorAltitude = fresh?.let { it.altitudeMsl ?: it.ellipsoidHeight },
            groundElevation = profile.metadata.groundElevationM,
            geoidSeparation = fresh?.let { f -> f.ellipsoidHeight?.let { hae -> f.altitudeMsl?.let { msl -> hae - msl } } },
            pose = pose,
            horizontalFov = hfov,
            verticalFov = vfov,
        )
        val frame = TargetReport.vmti(d.snapshot(captureUs), d.tracker.lastDetectionCount, size.width, size.height, geometry)
        return Vmti.encode(frame)
    }

    private fun publishStatus() {
        val now = SensorHub.nowUs()
        val gnss = sensors.gnssStatus(now)
        val fix = sensors.latestFix?.takeIf { gnss.hasFix }
        val pose = sensors.poseAt(now, cameraInfo.facing, orientation.remainingSensorRotationDeg)
        val position = fix?.let {
            PositionStatus(it.latitude, it.longitude, it.altitudeMsl, gnss.accuracyM, pose?.azimuthDeg, pose?.elevationDeg)
        }
        StreamController.update { it.copy(gnss = gnss, position = position) }
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
