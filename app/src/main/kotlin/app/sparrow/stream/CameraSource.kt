package app.sparrow.stream

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import app.sparrow.core.config.FocusMode
import app.sparrow.core.config.VideoSettings
import app.sparrow.core.geo.FrameGeometry
import app.sparrow.core.geo.LensFacing
import app.sparrow.log.AppLog
import java.util.concurrent.Executor
import kotlin.math.abs

/** Static facts about one camera, as needed for capture and metadata. */
data class CameraInfo(
    val id: String,
    val facing: LensFacing,
    val sensorOrientation: Int,
    /** Width and height of the active array in mm. */
    val activeWidthMm: Double,
    val activeHeightMm: Double,
    val activeAspect: Double,
    val focalLengthMm: Double,
    val zoomRange: ClosedFloatingPointRange<Double>,
    val realtimeTimestamps: Boolean,
    val encoderSizes: List<Size>,
    val previewSizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val label: String,
) {
    /** Horizontal and vertical FOV in degrees of an output with aspect [aspect] at [zoom]. */
    fun fieldOfView(aspect: Double, zoom: Double): Pair<Double, Double> {
        val (w, h) = if (aspect >= activeAspect) activeWidthMm to activeWidthMm / aspect
        else activeHeightMm * aspect to activeHeightMm
        return FrameGeometry.fieldOfView(w, focalLengthMm, zoom) to FrameGeometry.fieldOfView(h, focalLengthMm, zoom)
    }

    companion object {
        fun all(context: Context): List<CameraInfo> {
            val manager = context.getSystemService(CameraManager::class.java)
            return manager.cameraIdList.mapNotNull { id -> runCatching { of(manager, id) }.getOrNull() }
        }

        fun of(manager: CameraManager, id: String): CameraInfo {
            val c = manager.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
                else -> LensFacing.EXTERNAL
            }
            val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
            val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.0f
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val zoom = if (Build.VERSION.SDK_INT >= 30) {
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower.toDouble()..it.upper.toDouble() }
            } else null
            val maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble() ?: 1.0
            val activeWmm = physical.width * active.width().toDouble() / pixels.width
            val activeHmm = physical.height * active.height().toDouble() / pixels.height
            return CameraInfo(
                id = id,
                facing = facing,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                activeWidthMm = activeWmm,
                activeHeightMm = activeHmm,
                activeAspect = active.width().toDouble() / active.height(),
                focalLengthMm = focal.toDouble(),
                zoomRange = zoom ?: 1.0..maxDigital,
                realtimeTimestamps = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                encoderSizes = map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty(),
                previewSizes = map.getOutputSizes(SurfaceHolder::class.java)?.toList().orEmpty(),
                fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
                label = "${facing.name.lowercase().replaceFirstChar { it.uppercase() }} camera $id " +
                    "(%.1f mm, %.0f° HFOV)".format(focal, FrameGeometry.fieldOfView(activeWmm, focal.toDouble())),
            )
        }

        /** The requested size if supported, else the closest supported size of similar aspect. */
        fun chooseSize(sizes: List<Size>, width: Int, height: Int): Size {
            sizes.firstOrNull { it.width == width && it.height == height }?.let { return it }
            val aspect = width.toDouble() / height
            return sizes.minByOrNull { s ->
                abs(s.width.toDouble() / s.height - aspect) * 10_000 + abs(s.width * s.height - width * height) / 1000.0
            } ?: Size(width, height)
        }
    }
}

/**
 * Drives one Camera2 device into the encoder surface, plus the UI preview surface while one is
 * attached. Timestamps are converted to the capture clock (elapsedRealtime) via [toCaptureUs].
 */
class CameraSource(
    private val context: Context,
    val info: CameraInfo,
    private val settings: VideoSettings,
    private val onFatalError: (String) -> Unit,
) {
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoderSurface: Surface? = null
    private var preview: PreviewTarget? = null
    @Volatile private var closed = false
    @Volatile private var opening = false
    private val deviceClosed = java.util.concurrent.CountDownLatch(1)

    @Volatile var zoomRatio: Double = settings.zoomRatio.coerceIn(info.zoomRange)
        private set

    /** True when output timestamps are already elapsedRealtime. */
    private val realtimeBase = Build.VERSION.SDK_INT >= 33 || info.realtimeTimestamps

    /** elapsedRealtime − monotonic clock, for devices whose camera uses CLOCK_MONOTONIC. */
    private val monotonicOffsetUs = (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1000

    fun toCaptureUs(cameraUs: Long): Long = if (realtimeBase) cameraUs else cameraUs + monotonicOffsetUs

    @SuppressLint("MissingPermission")
    /** Opens the camera. [encoderSurface] is null for a preview-only session (framing before going live). */
    fun start(encoderSurface: Surface?) {
        this.encoderSurface = encoderSurface
        val manager = context.getSystemService(CameraManager::class.java)
        opening = true
        manager.openCamera(info.id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opening = false
                if (closed) {
                    camera.close(); return
                }
                device = camera
                AppLog.i("Camera ${info.id} opened")
                configure()
            }

            override fun onClosed(camera: CameraDevice) {
                deviceClosed.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                opening = false
                AppLog.w("Camera ${info.id} disconnected")
                camera.close()
                device = null
                if (!closed) onFatalError("Camera disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                opening = false
                AppLog.e("Camera ${info.id} error $error")
                camera.close()
                device = null
                if (!closed) onFatalError("Camera error $error")
            }
        })
    }

    /**
     * Adds or removes the preview output. Removal waits briefly for the old session to close so
     * the UI can release its surface safely.
     */
    fun setPreview(target: PreviewTarget?) {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            if (preview != target) {
                preview = target
                configure()
            }
            done.countDown()
        }
        if (target == null && Thread.currentThread() != thread) done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun setZoom(ratio: Double) {
        handler.post {
            zoomRatio = ratio.coerceIn(info.zoomRange)
            session?.let { runCatching { it.setRepeatingRequest(buildRequest(it.device), null, handler) } }
        }
    }

    private fun configure() {
        val camera = device ?: return
        session?.close()
        session = null
        val targets = listOfNotNull(encoderSurface, preview?.surface?.takeIf { it.isValid })
        if (targets.isEmpty()) return
        val outputs = targets.map { s ->
            OutputConfiguration(s).apply {
                if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_REALTIME
            }
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) {
                    s.close(); return
                }
                session = s
                runCatching { s.setRepeatingRequest(buildRequest(camera, targets), null, handler) }
                    .onFailure { AppLog.e("Could not start capture", it) }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                AppLog.e("Camera session configuration failed")
                if (preview != null && encoderSurface != null) {
                    // Some devices cannot add a preview stream at this size; keep streaming without it.
                    preview = null
                    configure()
                } else {
                    onFatalError("Camera session configuration failed")
                }
            }
        }
        runCatching {
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback))
        }.onFailure {
            AppLog.e("createCaptureSession failed", it)
            onFatalError("Camera session failed")
        }
    }

    private var lastTargets: List<Surface> = emptyList()

    private fun buildRequest(camera: CameraDevice, targets: List<Surface> = lastTargets): CaptureRequest {
        lastTargets = targets
        return camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            targets.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange())
            when (settings.focus) {
                FocusMode.CONTINUOUS -> set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                FocusMode.INFINITY -> {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                }
            }
            set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (settings.stabilization) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            if (Build.VERSION.SDK_INT >= 30) {
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.toFloat())
            } else if (zoomRatio > 1.0) {
                val active = context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(info.id).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
                val w = (active.width() / zoomRatio).toInt()
                val h = (active.height() / zoomRatio).toInt()
                val l = active.left + (active.width() - w) / 2
                val t = active.top + (active.height() - h) / 2
                set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(l, t, l + w, t + h))
            }
        }.build()
    }

    private fun fpsRange(): Range<Int> {
        val fps = settings.fps
        // Prefer a fixed rate, then a range capped at the target (AE may slow down in the dark),
        // then any range containing it; the encoder drops the excess (KEY_MAX_FPS_TO_ENCODER).
        return info.fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: info.fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: info.fpsRanges.filter { fps in it.lower..it.upper }.minByOrNull { it.upper - it.lower }
            ?: Range(fps, fps)
    }

    /**
     * Closes the session and device and waits (up to 1.5 s) until the camera has let go, so the
     * encoder surface can be released safely. Callbacks of a pending open still run on the thread.
     */
    fun close() {
        closed = true
        handler.post {
            runCatching { session?.close() }
            val d = device
            session = null
            device = null
            if (d != null) runCatching { d.close() } else if (!opening) deviceClosed.countDown()
        }
        if (Thread.currentThread() != thread) deviceClosed.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }
}
