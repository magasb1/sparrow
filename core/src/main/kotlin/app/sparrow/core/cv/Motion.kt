package app.sparrow.core.cv

/** Motion in one analysed frame. Boxes are normalised stream-image coordinates. */
data class MotionResult(
    /** Fraction of the frame that is moving, after noise removal (0..1). */
    val activity: Float,
    val blobs: List<Box>,
    /** True while the background model is (re)learning after start or camera movement. */
    val learning: Boolean,
)

/**
 * Scene motion from MOG2 on a small luma image (default 160×90, in stream orientation).
 *
 * MOG2 assumes a still camera. While the phone itself turns ([cameraMoving]) the whole frame
 * would read as motion, so analysis pauses and the model relearns once the camera settles.
 */
class MotionAnalyzer(
    val width: Int = 160,
    val height: Int = 90,
    /** Frames after a reset before the mask is trusted. */
    private val warmupFrames: Int = 15,
    /** Smallest blob, as a fraction of the frame (0.15% ≈ 22 px at 160×90). */
    minBlobFraction: Float = 0.0015f,
) {
    private val mog = Mog2(width, height)
    private val gray = ByteArray(width * height)
    private val mask = ByteArray(width * height)
    private val scratch = ByteArray(width * height)
    private val labels = IntArray(width * height)
    private val minBlob = maxOf(4, (minBlobFraction * width * height).toInt())
    private var wasMoving = false

    fun analyze(frame: YuvFrame, rotationDeg: Int, cameraMoving: Boolean): MotionResult {
        if (cameraMoving) {
            wasMoving = true
            return MotionResult(0f, emptyList(), learning = true)
        }
        if (wasMoving) {
            mog.reset()
            wasMoving = false
        }
        sampleLuma(frame, rotationDeg)
        return analyzeGray(gray)
    }

    /** Same as [analyze] for an already downscaled frame (tests). */
    fun analyzeGray(input: ByteArray): MotionResult {
        mog.apply(input, mask)
        if (mog.frames < warmupFrames) return MotionResult(0f, emptyList(), learning = true)
        MaskOps.open(mask, width, height, scratch)
        var on = 0
        for (b in mask) if (b.toInt() != 0) on++
        return MotionResult(on.toFloat() / mask.size, MaskOps.blobs(mask, width, height, minBlob, labels), learning = false)
    }

    fun reset() {
        mog.reset()
    }

    /** Area-average downscale of the luma plane into [gray], rotated into stream orientation. */
    private fun sampleLuma(f: YuvFrame, rotationDeg: Int) {
        val swap = rotationDeg % 180 != 0
        // Source pixels per output pixel, measured along the sensor axes.
        val stepX = (if (swap) f.width.toFloat() / height else f.width.toFloat() / width).toInt().coerceAtLeast(1)
        val stepY = (if (swap) f.height.toFloat() / width else f.height.toFloat() / height).toInt().coerceAtLeast(1)
        val bx = minOf(stepX, 4)
        val by = minOf(stepY, 4)
        for (row in 0 until height) {
            val t = (row + 0.5f) / height
            for (col in 0 until width) {
                val s = (col + 0.5f) / width
                val sx = RfDetr.sensorX(s, t, rotationDeg)
                val sy = RfDetr.sensorY(s, t, rotationDeg)
                val x0 = (sx * f.width).toInt().coerceIn(0, f.width - bx)
                val y0 = (sy * f.height).toInt().coerceIn(0, f.height - by)
                var sum = 0
                for (dy in 0 until by) {
                    val o = (y0 + dy) * f.yRowStride + x0
                    for (dx in 0 until bx) sum += f.y[o + dx].toInt() and 0xFF
                }
                gray[row * width + col] = (sum / (bx * by)).toByte()
            }
        }
    }
}

/**
 * Decides when the detector runs, so RF-DETR works only when the scene changes, and never more
 * often than its configured rate:
 * - at that rate while there is motion (and for [holdUs] after it), the camera moves, or a moving
 *   blob is not explained by a known track (even below the activity threshold);
 * - otherwise every [keepAliveUs], so still targets are not lost.
 */
class DetectionScheduler(
    private val activeIntervalUs: Long,
    private val keepAliveUs: Long = 2_000_000,
    private val holdUs: Long = 2_000_000,
    /** Below this moving fraction a frame counts as static. */
    private val activityThreshold: Float = 0.002f,
) {
    private var lastRunUs = Long.MIN_VALUE
    private var lastMotionUs = Long.MIN_VALUE

    fun shouldRun(nowUs: Long, motion: MotionResult?, cameraMoving: Boolean, tracks: List<TrackedObject>): Boolean {
        val since = if (lastRunUs == Long.MIN_VALUE) Long.MAX_VALUE else nowUs - lastRunUs
        if (motion == null) return since >= activeIntervalUs // no motion analysis: plain rate
        val moving = cameraMoving || motion.learning || motion.activity >= activityThreshold
        if (moving) lastMotionUs = nowUs
        val active = moving || (lastMotionUs != Long.MIN_VALUE && nowUs - lastMotionUs < holdUs)
        val unexplained = motion.blobs.any { b -> tracks.none { it.box.iou(b) > 0.1f || contains(it.box, b) } }
        val run = if (active || unexplained) since >= activeIntervalUs else since >= keepAliveUs
        if (run) lastRunUs = nowUs
        return run
    }

    private fun contains(outer: Box, inner: Box) =
        inner.cx in outer.x1..outer.x2 && inner.cy in outer.y1..outer.y2
}
