package app.sparrow.core.cv

import kotlin.math.exp
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
 * RF-DETR Nano model contract (see tools/export_rfdetr.py): 384×384 RGB input, normalised with
 * ImageNet statistics; 300 queries of (cx, cy, w, h) boxes and 91 COCO-id logits.
 */
object RfDetr {
    const val INPUT_SIZE = 384
    const val QUERIES = 300
    const val CLASS_SLOTS = 91
    const val INPUT_NAME = "input"
    const val BOXES_OUTPUT = "dets"
    const val LOGITS_OUTPUT = "labels"
    const val ASSET = "models/rfdetr-nano-int8.onnx"

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

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
     * Fills [out] (3×384×384, CHW) from a YUV frame in sensor orientation, rotated into stream
     * orientation and stretched to the square input. Luma is sampled bilinearly, chroma nearest.
     */
    fun preprocess(frame: YuvFrame, rotationDeg: Int, out: FloatArray, n: Int = INPUT_SIZE) {
        val plane = n * n
        val w = frame.width
        val h = frame.height
        // Sample positions per output index, computed once instead of per pixel (147k of them):
        // sensorX/Y read only s or only t for a given rotation, so one value serves both. With a
        // 90/270° rotation the output row drives the sensor x and the column the sensor y.
        val x0s = IntArray(n); val x1s = IntArray(n); val axs = FloatArray(n)
        val y0s = IntArray(n); val y1s = IntArray(n); val ays = FloatArray(n)
        for (k in 0 until n) {
            val s = (k + 0.5f) / n
            val fx = sensorX(s, s, rotationDeg) * w - 0.5f
            val fy = sensorY(s, s, rotationDeg) * h - 0.5f
            x0s[k] = fx.toInt().coerceIn(0, w - 1); x1s[k] = min(x0s[k] + 1, w - 1); axs[k] = (fx - x0s[k]).coerceIn(0f, 1f)
            y0s[k] = fy.toInt().coerceIn(0, h - 1); y1s[k] = min(y0s[k] + 1, h - 1); ays[k] = (fy - y0s[k]).coerceIn(0f, 1f)
        }
        val swap = rotationDeg % 180 != 0
        val y = frame.y
        val ys = frame.yRowStride
        for (row in 0 until n) {
            for (col in 0 until n) {
                val kx = if (swap) row else col
                val ky = if (swap) col else row
                val x0 = x0s[kx]; val x1 = x1s[kx]; val ax = axs[kx]
                val y0 = y0s[ky]; val y1 = y1s[ky]; val ay = ays[ky]
                val r0 = y0 * ys
                val r1 = y1 * ys
                val top = (y[r0 + x0].toInt() and 0xFF) * (1 - ax) + (y[r0 + x1].toInt() and 0xFF) * ax
                val bottom = (y[r1 + x0].toInt() and 0xFF) * (1 - ax) + (y[r1 + x1].toInt() and 0xFF) * ax
                val luma = top * (1 - ay) + bottom * ay
                val ci = (y0 shr 1) * frame.uvRowStride + (x0 shr 1) * frame.uvPixelStride
                val cb = (frame.u[ci].toInt() and 0xFF) - 128f
                val cr = (frame.v[ci].toInt() and 0xFF) - 128f
                // BT.601 full range, as camera YUV_420_888 output is JFIF.
                val r = (luma + 1.402f * cr) / 255f
                val g = (luma - 0.344136f * cb - 0.714136f * cr) / 255f
                val b = (luma + 1.772f * cb) / 255f
                val i = row * n + col
                out[i] = (r.coerceIn(0f, 1f) - MEAN[0]) / STD[0]
                out[plane + i] = (g.coerceIn(0f, 1f) - MEAN[1]) / STD[1]
                out[2 * plane + i] = (b.coerceIn(0f, 1f) - MEAN[2]) / STD[2]
            }
        }
    }

    /** Same as [preprocess] for packed ARGB pixels (tests, still images). */
    fun preprocessArgb(argb: IntArray, width: Int, height: Int, rotationDeg: Int, out: FloatArray) {
        val n = INPUT_SIZE
        val plane = n * n
        for (row in 0 until n) {
            for (col in 0 until n) {
                val s = (col + 0.5f) / n
                val t = (row + 0.5f) / n
                val sx = sensorX(s, t, rotationDeg)
                val sy = sensorY(s, t, rotationDeg)
                val fx = sx * width - 0.5f
                val fy = sy * height - 0.5f
                val i = row * n + col
                for (c in 0 until 3) {
                    val shift = 16 - 8 * c
                    val v = bilinearArgb(argb, width, height, fx, fy, shift) / 255f
                    out[c * plane + i] = (v - MEAN[c]) / STD[c]
                }
            }
        }
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
     * Decodes model outputs into detections of [classes] scoring at least [minScore], with a
     * light per-class NMS against the occasional duplicate query.
     */
    fun postprocess(boxes: FloatArray, logits: FloatArray, minScore: Float, classes: Set<Int>): List<Detection> {
        val p = minScore.coerceIn(0.01f, 0.99f)
        val minLogit = -kotlin.math.ln(1 / p - 1)
        val found = ArrayList<Detection>()
        for (q in 0 until QUERIES) {
            var best = 1
            var bestLogit = logits[q * CLASS_SLOTS + 1]
            for (c in 2 until CLASS_SLOTS) {
                val l = logits[q * CLASS_SLOTS + c]
                if (l > bestLogit) {
                    bestLogit = l; best = c
                }
            }
            if (bestLogit < minLogit || best !in classes) continue
            val b = q * 4
            val box = Box.ofCenter(boxes[b], boxes[b + 1], boxes[b + 2], boxes[b + 3]).clamped()
            if (box.area <= 0f) continue
            found.add(Detection(best, sigmoid(bestLogit), box))
        }
        found.sortByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in found) {
            if (kept.none { it.classId == d.classId && it.box.iou(d.box) > 0.7f }) kept.add(d)
        }
        return kept
    }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))
}
