package app.sparrow.core.cv

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in normalised image coordinates (0..1, y down). */
data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val width get() = x2 - x1
    val height get() = y2 - y1
    val cx get() = (x1 + x2) / 2
    val cy get() = (y1 + y2) / 2
    val area get() = max(0f, width) * max(0f, height)

    fun iou(o: Box): Float {
        val ix = max(0f, min(x2, o.x2) - max(x1, o.x1))
        val iy = max(0f, min(y2, o.y2) - max(y1, o.y1))
        val inter = ix * iy
        val union = area + o.area - inter
        return if (union > 0) inter / union else 0f
    }

    fun clamped() = Box(x1.coerceIn(0f, 1f), y1.coerceIn(0f, 1f), x2.coerceIn(0f, 1f), y2.coerceIn(0f, 1f))

    companion object {
        fun ofCenter(cx: Float, cy: Float, w: Float, h: Float) = Box(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
}

data class Detection(val classId: Int, val score: Float, val box: Box)

/** YUV_420_888 frame copied out of an Android Image. Plane arrays are reused between frames. */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRowStride: Int,
    val uvPixelStride: Int,
)

/**
 * Where the stream image sits inside the square model input: [width]×[height] pixels at
 * ([padX], [padY]), the rest grey.
 */
data class Letterbox(val padX: Float, val padY: Float, val width: Float, val height: Float) {
    companion object {
        /** Fits a [w]×[h] sensor image, turned by [rotationDeg], into an [n]×[n] input keeping its aspect. */
        fun of(w: Int, h: Int, rotationDeg: Int, n: Int): Letterbox {
            val swap = rotationDeg % 180 != 0
            val sw = if (swap) h else w
            val sh = if (swap) w else h
            val scale = n.toFloat() / max(sw, sh)
            val cw = Math.round(sw * scale)
            val ch = Math.round(sh * scale)
            return Letterbox(((n - cw) / 2).toFloat(), ((n - ch) / 2).toFloat(), cw.toFloat(), ch.toFloat())
        }
    }
}

/**
 * YOLO26n model contract (see tools/export_yolo26.py): 512×512 RGB input scaled to 0..1 and
 * letterboxed with grey 114; the NMS-free head gives 300 rows of (x1, y1, x2, y2, score, class)
 * in input pixels, with classes 0–79 in COCO order.
 */
object Yolo {
    const val INPUT_SIZE = 512
    const val ROWS = 300
    const val INPUT_NAME = "images"
    const val OUTPUT_NAME = "output0"
    const val ASSET = "models/yolo26n.onnx"

    private const val PAD = 114f / 255f

    /** The 80 YOLO classes in order, as COCO category ids (the ids the rest of the app uses). */
    private val COCO_IDS = CocoClasses.names.keys.sorted().toIntArray()

    /**
     * Maps a normalised point of the (rotated) stream image back to the sensor image.
     * [rotationDeg] is the clockwise rotation applied to the sensor image to make the stream.
     */
    fun sensorPoint(s: Float, t: Float, rotationDeg: Int): Pair<Float, Float> = sensorX(s, t, rotationDeg) to sensorY(s, t, rotationDeg)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun sensorX(s: Float, t: Float, rotationDeg: Int): Float = when (rotationDeg) {
        90 -> t
        180 -> 1f - s
        270 -> 1f - t
        else -> s
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun sensorY(s: Float, t: Float, rotationDeg: Int): Float = when (rotationDeg) {
        90 -> 1f - s
        180 -> 1f - t
        270 -> s
        else -> t
    }

    /**
     * Fills [out] (3×n×n, CHW) from a YUV frame in sensor orientation, rotated into stream
     * orientation and letterboxed into the square input. Luma is sampled bilinearly, chroma nearest.
     */
    fun preprocess(frame: YuvFrame, rotationDeg: Int, out: FloatArray, n: Int = INPUT_SIZE): Letterbox {
        val plane = n * n
        val w = frame.width
        val h = frame.height
        val lb = Letterbox.of(w, h, rotationDeg, n)
        // Sample positions per output index, computed once instead of per pixel (262k of them):
        // sensorX/Y read only s or only t for a given rotation, so one value serves both. With a
        // 90/270° rotation the output row drives the sensor x and the column the sensor y.
        val x0s = IntArray(n); val x1s = IntArray(n); val axs = FloatArray(n)
        val y0s = IntArray(n); val y1s = IntArray(n); val ays = FloatArray(n)
        for (k in 0 until n) {
            val s = (k + 0.5f - lb.padX) / lb.width
            val t = (k + 0.5f - lb.padY) / lb.height
            val fx = sensorX(s, t, rotationDeg) * w - 0.5f
            val fy = sensorY(s, t, rotationDeg) * h - 0.5f
            x0s[k] = fx.toInt().coerceIn(0, w - 1); x1s[k] = min(x0s[k] + 1, w - 1); axs[k] = (fx - x0s[k]).coerceIn(0f, 1f)
            y0s[k] = fy.toInt().coerceIn(0, h - 1); y1s[k] = min(y0s[k] + 1, h - 1); ays[k] = (fy - y0s[k]).coerceIn(0f, 1f)
        }
        val swap = rotationDeg % 180 != 0
        val left = lb.padX.toInt(); val right = left + lb.width.toInt()
        val top = lb.padY.toInt(); val bottom = top + lb.height.toInt()
        val y = frame.y
        val ys = frame.yRowStride
        for (row in 0 until n) {
            for (col in 0 until n) {
                val i = row * n + col
                if (row < top || row >= bottom || col < left || col >= right) {
                    out[i] = PAD; out[plane + i] = PAD; out[2 * plane + i] = PAD
                    continue
                }
                val kx = if (swap) row else col
                val ky = if (swap) col else row
                val x0 = x0s[kx]; val x1 = x1s[kx]; val ax = axs[kx]
                val y0 = y0s[ky]; val y1 = y1s[ky]; val ay = ays[ky]
                val r0 = y0 * ys
                val r1 = y1 * ys
                val upper = (y[r0 + x0].toInt() and 0xFF) * (1 - ax) + (y[r0 + x1].toInt() and 0xFF) * ax
                val lower = (y[r1 + x0].toInt() and 0xFF) * (1 - ax) + (y[r1 + x1].toInt() and 0xFF) * ax
                val luma = upper * (1 - ay) + lower * ay
                val ci = (y0 shr 1) * frame.uvRowStride + (x0 shr 1) * frame.uvPixelStride
                val cb = (frame.u[ci].toInt() and 0xFF) - 128f
                val cr = (frame.v[ci].toInt() and 0xFF) - 128f
                // BT.601 full range, as camera YUV_420_888 output is JFIF.
                out[i] = ((luma + 1.402f * cr) / 255f).coerceIn(0f, 1f)
                out[plane + i] = ((luma - 0.344136f * cb - 0.714136f * cr) / 255f).coerceIn(0f, 1f)
                out[2 * plane + i] = ((luma + 1.772f * cb) / 255f).coerceIn(0f, 1f)
            }
        }
        return lb
    }

    /** Same as [preprocess] for packed ARGB pixels (tests, still images). */
    fun preprocessArgb(argb: IntArray, width: Int, height: Int, rotationDeg: Int, out: FloatArray): Letterbox {
        val n = INPUT_SIZE
        val plane = n * n
        val lb = Letterbox.of(width, height, rotationDeg, n)
        for (row in 0 until n) {
            for (col in 0 until n) {
                val s = (col + 0.5f - lb.padX) / lb.width
                val t = (row + 0.5f - lb.padY) / lb.height
                val inside = s in 0f..1f && t in 0f..1f
                val fx = sensorX(s, t, rotationDeg) * width - 0.5f
                val fy = sensorY(s, t, rotationDeg) * height - 0.5f
                val i = row * n + col
                for (c in 0 until 3) {
                    out[c * plane + i] = if (inside) bilinearArgb(argb, width, height, fx, fy, 16 - 8 * c) / 255f else PAD
                }
            }
        }
        return lb
    }

    private fun bilinearArgb(px: IntArray, w: Int, h: Int, x: Float, y: Float, shift: Int): Float {
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val ax = (x - x0).coerceIn(0f, 1f)
        val ay = (y - y0).coerceIn(0f, 1f)
        fun p(a: Int, b: Int) = ((px[b * w + a] ushr shift) and 0xFF).toFloat()
        return (p(x0, y0) * (1 - ax) + p(x1, y0) * ax) * (1 - ay) + (p(x0, y1) * (1 - ax) + p(x1, y1) * ax) * ay
    }

    /**
     * Decodes the model output into detections of [classes] scoring at least [minScore], with
     * boxes normalised to the stream image. The head is one-to-one, so there is no NMS.
     */
    fun postprocess(output: FloatArray, lb: Letterbox, minScore: Float, classes: Set<Int>): List<Detection> {
        val found = ArrayList<Detection>()
        for (r in 0 until ROWS) {
            val o = r * 6
            val score = output[o + 4]
            if (score < minScore) continue
            val classId = COCO_IDS.getOrNull(output[o + 5].toInt()) ?: continue
            if (classId !in classes) continue
            val box = Box(
                (output[o] - lb.padX) / lb.width,
                (output[o + 1] - lb.padY) / lb.height,
                (output[o + 2] - lb.padX) / lb.width,
                (output[o + 3] - lb.padY) / lb.height,
            ).clamped()
            if (box.area > 0f) found.add(Detection(classId, score, box))
        }
        return found
    }
}
