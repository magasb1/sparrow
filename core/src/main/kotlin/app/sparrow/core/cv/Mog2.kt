package app.sparrow.core.cv

/**
 * MOG2 background subtractor (Zivkovic 2004/2006, as in OpenCV's BackgroundSubtractorMOG2) for
 * 8-bit grayscale frames: each pixel keeps up to [maxModes] Gaussians, the heaviest of which
 * explain the background. Shadow detection is not needed for luma-only input and is left out.
 */
class Mog2(
    val width: Int,
    val height: Int,
    private val history: Int = 300,
    private val maxModes: Int = 4,
    /** Squared Mahalanobis distance for "background" (OpenCV varThreshold). */
    private val varThreshold: Float = 16f,
    /** Squared distance for a sample to update an existing mode (OpenCV varThresholdGen). */
    private val varThresholdGen: Float = 9f,
    private val backgroundRatio: Float = 0.9f,
    private val varInit: Float = 15f,
    private val varMin: Float = 4f,
    private val varMax: Float = 75f,
    /** Complexity reduction prior: modes whose weight falls below this disappear. */
    private val complexityReduction: Float = 0.05f,
) {
    private val n = width * height
    private val weight = FloatArray(n * maxModes)
    private val mean = FloatArray(n * maxModes)
    private val variance = FloatArray(n * maxModes)
    private val modes = IntArray(n)
    var frames = 0
        private set

    fun reset() {
        modes.fill(0)
        frames = 0
    }

    /**
     * Updates the model with [gray] (row-major, width × height) and writes the foreground mask
     * (255 = moving, 0 = background) into [mask].
     */
    fun apply(gray: ByteArray, mask: ByteArray) {
        frames++
        // Fast initial learning, then the configured history (OpenCV: 1 / min(2n, history)).
        val alpha = 1f / minOf(2 * frames, history)
        val prune = -alpha * complexityReduction
        val oneMinus = 1f - alpha
        for (p in 0 until n) {
            val x = (gray[p].toInt() and 0xFF).toFloat()
            val base = p * maxModes
            var count = modes[p]
            var fits = false
            var background = false
            var total = 0f
            var k = 0
            while (k < count) {
                val i = base + k
                var w = oneMinus * weight[i] + prune
                if (!fits) {
                    val d = x - mean[i]
                    val dist2 = d * d
                    val v = variance[i]
                    if (total < backgroundRatio && dist2 < varThreshold * v) background = true
                    if (dist2 < varThresholdGen * v) {
                        fits = true
                        w += alpha
                        val step = alpha / w
                        mean[i] += step * d
                        variance[i] = (v + step * (dist2 - v)).coerceIn(varMin, varMax)
                        // Keep modes sorted by weight, heaviest first.
                        var j = k
                        while (j > 0 && w > weight[base + j - 1]) {
                            swap(base + j, base + j - 1)
                            j--
                        }
                        weight[base + j] = w
                        total += w
                        k++
                        continue
                    }
                }
                if (w < -prune) {
                    // Negligible mode: drop it by moving the last one into its place.
                    count--
                    if (k < count) copy(base + count, i)
                    continue
                }
                weight[i] = w
                total += w
                k++
            }
            // Renormalise first, then insert a new mode the way OpenCV does.
            if (total > 0f) {
                val inv = 1f / total
                for (m in 0 until count) weight[base + m] *= inv
            }
            if (!fits) {
                // Replace the weakest mode when full, else add one.
                val slot = if (count == maxModes) maxModes - 1 else count++
                val i = base + slot
                if (count == 1) {
                    weight[i] = 1f
                } else {
                    weight[i] = alpha
                    for (m in 0 until count) if (m != slot) weight[base + m] *= oneMinus
                }
                mean[i] = x
                variance[i] = varInit
                // Move the new mode up to its place in weight order.
                var j = slot
                while (j > 0 && alpha >= weight[base + j - 1]) {
                    swap(base + j, base + j - 1)
                    j--
                }
            }
            modes[p] = count
            mask[p] = if (background) 0 else 0xFF.toByte()
        }
    }

    private fun swap(a: Int, b: Int) {
        val w = weight[a]; weight[a] = weight[b]; weight[b] = w
        val m = mean[a]; mean[a] = mean[b]; mean[b] = m
        val v = variance[a]; variance[a] = variance[b]; variance[b] = v
    }

    private fun copy(from: Int, to: Int) {
        weight[to] = weight[from]; mean[to] = mean[from]; variance[to] = variance[from]
    }
}

/** Morphological opening (3×3 erode then dilate) and 4-connected blobs of a binary mask. */
object MaskOps {
    fun open(mask: ByteArray, width: Int, height: Int, scratch: ByteArray) {
        morph(mask, scratch, width, height, erode = true)
        morph(scratch, mask, width, height, erode = false)
    }

    private fun morph(src: ByteArray, dst: ByteArray, w: Int, h: Int, erode: Boolean) {
        for (y in 0 until h) for (x in 0 until w) {
            var hit = erode
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                val on = xx in 0 until w && yy in 0 until h && src[yy * w + xx].toInt() != 0
                if (erode && !on) { hit = false; break@loop }
                if (!erode && on) { hit = true; break@loop }
            }
            dst[y * w + x] = if (hit) 0xFF.toByte() else 0
        }
    }

    /** Bounding boxes (normalised) of connected foreground regions of at least [minPixels]. */
    fun blobs(mask: ByteArray, width: Int, height: Int, minPixels: Int, labels: IntArray): List<Box> {
        labels.fill(0)
        val out = ArrayList<Box>()
        val stack = IntArray(width * height)
        var next = 1
        for (start in 0 until width * height) {
            if (mask[start].toInt() == 0 || labels[start] != 0) continue
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            var minX = width; var minY = height; var maxX = -1; var maxY = -1; var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % width
                val y = p / width
                count++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                fun visit(q: Int) {
                    if (mask[q].toInt() != 0 && labels[q] == 0) { labels[q] = next; stack[sp++] = q }
                }
                if (x > 0) visit(p - 1)
                if (x < width - 1) visit(p + 1)
                if (y > 0) visit(p - width)
                if (y < height - 1) visit(p + width)
            }
            next++
            if (count >= minPixels) {
                out.add(Box(minX.toFloat() / width, minY.toFloat() / height, (maxX + 1).toFloat() / width, (maxY + 1).toFloat() / height))
            }
        }
        return out
    }
}
