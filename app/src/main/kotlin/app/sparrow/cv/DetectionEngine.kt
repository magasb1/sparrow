package app.sparrow.cv

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import app.sparrow.core.config.DetectionSettings
import app.sparrow.core.cv.Yolo
import app.sparrow.core.cv.TrackedObject
import app.sparrow.core.cv.Tracker
import app.sparrow.core.cv.YuvFrame
import app.sparrow.log.AppLog
import java.nio.FloatBuffer

/** Live detector figures for the UI. */
data class DetectionStats(val inferenceMs: Long, val rateHz: Double, val detections: Int)

/**
 * Runs YOLO26n on a low-resolution copy of the camera stream (an ImageReader added to the
 * capture session) and keeps a tracker of the results. Frames arrive at the camera rate; at most
 * [DetectionSettings.rateHz] are analysed and the rest are dropped unread.
 *
 * Detections are in the stream's orientation ([rotationDeg] is applied before inference), so
 * boxes map directly onto the encoded frame and the viewfinder.
 */
class DetectionEngine(
    private val context: Context,
    private val settings: DetectionSettings,
    private val rotationDeg: Int,
    analysisSize: Size,
    /** Converts a camera timestamp (µs) to the capture clock. */
    private val toCaptureUs: (Long) -> Long,
    private val onResults: (DetectionStats) -> Unit,
) {
    // Frames are taken and returned on one thread and analysed on another, so a slow inference
    // never holds camera buffers (which would stall the encoder and preview too).
    private val thread = HandlerThread("analysis-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val inference = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "detector") }
    @Volatile private var busy = false
    private val reader = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2)
    // The confidence setting is the BYTE high threshold; weaker detections still extend tracks.
    val tracker = Tracker(highScore = settings.minConfidence.toFloat())

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loadFailed = false
    private val input = FloatArray(3 * Yolo.INPUT_SIZE * Yolo.INPUT_SIZE)
    /** Reused native input buffer; a heap buffer would make ONNX Runtime allocate 3 MB per run. */
    private val tensorBuffer: FloatBuffer = java.nio.ByteBuffer.allocateDirect(4 * input.size)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
    private var frame: YuvFrame? = null
    private val intervalMs = (1000 / settings.rateHz).toLong()
    private var lastRunMs = 0L
    private var lastStatsMs = 0L
    private var runs = 0
    private var statsLogged = 0
    private var preMs = 0L
    private var runMs = 0L

    @Volatile var enabled = false
        set(value) {
            field = value
            // Cleared on the detector thread so an inference in flight cannot bring tracks back.
            if (!value) runCatching { inference.execute { tracker.clear() } }
        }

    val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ onImage(it) }, handler)
    }

    private fun onImage(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        val captureUs: Long
        val f: YuvFrame
        try {
            val now = SystemClock.elapsedRealtime()
            if (!enabled || loadFailed || busy || now - lastRunMs < intervalMs) return
            lastRunMs = now
            captureUs = toCaptureUs(image.timestamp / 1000)
            // Safe to reuse: the previous inference has finished reading it (busy is false).
            f = copy(image, frame).also { frame = it }
        } finally {
            image.close()
        }
        busy = true
        inference.execute {
            try {
                detect(f, captureUs)
            } finally {
                busy = false
            }
        }
    }

    private fun detect(f: YuvFrame, captureUs: Long) {
        val s = session ?: load() ?: return
        val t0 = SystemClock.elapsedRealtime()
        try {
            val lb = Yolo.preprocess(f, rotationDeg, input)
            tensorBuffer.clear()
            tensorBuffer.put(input)
            tensorBuffer.flip()
            preMs = SystemClock.elapsedRealtime() - t0
            val n = Yolo.INPUT_SIZE.toLong()
            OnnxTensor.createTensor(env, tensorBuffer, longArrayOf(1, 3, n, n)).use { tensor ->
                val t1 = SystemClock.elapsedRealtime()
                s.run(mapOf(Yolo.INPUT_NAME to tensor)).use { out ->
                    runMs = SystemClock.elapsedRealtime() - t1
                    val rows = (out.get(Yolo.OUTPUT_NAME).get() as OnnxTensor).floatBuffer.toArray()
                    val detections = Yolo.postprocess(rows, lb, LOW_SCORE, settings.classes)
                    if (enabled) tracker.update(detections, captureUs)
                }
            }
        } catch (e: Exception) {
            AppLog.w("Detection failed", e)
            return
        }
        val ms = SystemClock.elapsedRealtime() - t0
        runs++
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsMs >= 1000) {
            val rate = if (lastStatsMs == 0L) 0.0 else runs * 1000.0 / (now - lastStatsMs)
            lastStatsMs = now
            runs = 0
            onResults(DetectionStats(ms, rate, tracker.lastDetectionCount))
            if (++statsLogged % 10 == 0) AppLog.i("Detection: %.1f Hz, %d ms (pre %d, run %d), %d targets".format(rate, ms, preMs, runMs, tracker.lastDetectionCount))
        }
    }

    private fun load(): OrtSession? {
        if (loadFailed) return null
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val e = OrtEnvironment.getEnvironment()
            val model = context.assets.open(Yolo.ASSET).use { it.readBytes() }
            // CPU provider (MLAS NCHWc convolutions) on the four big cores, workers spinning.
            // ponytail: XNNPACK not yet measured with YOLO26 (it lost for the old RF-DETR
            // transformer); A/B it on a device before adding it.
            val s = e.createSession(model, OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            })
            env = e
            session = s
            AppLog.i("YOLO26n loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            s
        } catch (e: Exception) {
            AppLog.e("Could not load the detection model", e)
            loadFailed = true
            null
        }
    }

    /** Copies the YUV planes into reusable arrays so the Image can be returned at once. */
    private fun copy(image: Image, reuse: YuvFrame?): YuvFrame {
        val (yp, up, vp) = image.planes
        fun bytes(buffer: java.nio.ByteBuffer, old: ByteArray?): ByteArray {
            val n = buffer.remaining()
            val a = if (old != null && old.size >= n) old else ByteArray(n)
            buffer.get(a, 0, n)
            return a
        }
        return YuvFrame(
            width = image.width,
            height = image.height,
            y = bytes(yp.buffer, reuse?.y),
            yRowStride = yp.rowStride,
            u = bytes(up.buffer, reuse?.u),
            v = bytes(vp.buffer, reuse?.v),
            uvRowStride = up.rowStride,
            uvPixelStride = up.pixelStride,
        )
    }

    fun snapshot(captureUs: Long): List<TrackedObject> = if (enabled) tracker.snapshot(captureUs) else emptyList()

    fun release() {
        enabled = false
        handler.post { reader.close() }
        thread.quitSafely()
        inference.execute {
            runCatching { session?.close() }
            session = null
        }
        inference.shutdown()
    }

    private fun FloatBuffer.toArray(): FloatArray = FloatArray(remaining()).also { get(it) }

    companion object {
        /** Detections below this are not even offered to the tracker's second association. */
        private const val LOW_SCORE = 0.1f

        /** Smallest YUV size with the stream's aspect ratio and at least 640 px wide. */
        fun analysisSize(sizes: List<Size>, aspect: Double): Size? = sizes
            .filter { kotlin.math.abs(it.width.toDouble() / it.height - aspect) < 0.02 && it.width >= 640 }
            .minByOrNull { it.width * it.height }
    }
}
