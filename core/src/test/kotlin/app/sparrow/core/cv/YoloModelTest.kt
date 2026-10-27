package app.sparrow.core.cv

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.FloatBuffer
import javax.imageio.ImageIO

/**
 * Runs the shipped YOLO26n model on COCO val2017 images with the app's own Kotlin pre- and
 * post-processing, through both the ARGB and the YUV path. Needs network access for the images
 * (they are not committed); skipped otherwise.
 */
class YoloModelTest {
    private val model = File("../app/src/main/assets/${Yolo.ASSET}")

    private fun image(id: String): File? = runCatching {
        val f = File(System.getProperty("java.io.tmpdir"), "sparrow-coco-$id.jpg")
        if (!f.exists()) URI("http://images.cocodataset.org/val2017/$id.jpg").toURL().openStream().use { s -> f.outputStream().use { s.copyTo(it) } }
        f
    }.getOrNull()

    private fun run(input: FloatArray, lb: Letterbox): List<Detection> {
        val env = OrtEnvironment.getEnvironment()
        env.createSession(model.readBytes()).use { session ->
            val n = Yolo.INPUT_SIZE.toLong()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, n, n)).use { tensor ->
                session.run(mapOf(Yolo.INPUT_NAME to tensor)).use { r ->
                    val rows = (r.get(Yolo.OUTPUT_NAME).get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    return Yolo.postprocess(rows, lb, 0.5f, CocoClasses.names.keys)
                }
            }
        }
    }

    /** Converts ARGB to an NV21-like YUV_420_888 layout (pixel stride 2) the way Android cameras deliver it. */
    private fun toYuv(argb: IntArray, w: Int, h: Int): YuvFrame {
        val y = ByteArray(w * h)
        val uv = ByteArray(w * h / 2 + w)
        for (j in 0 until h) for (i in 0 until w) {
            val p = argb[j * w + i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            y[j * w + i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                val o = (j / 2) * w + i
                uv[o] = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).toInt().coerceIn(0, 255).toByte()     // U
                uv[o + 1] = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).toInt().coerceIn(0, 255).toByte() // V
            }
        }
        // U and V views into one interleaved plane, as on most devices.
        return YuvFrame(w, h, y, w, uv, uv.copyOfRange(1, uv.size) + 0, w, 2)
    }

    @Test
    fun `detects the two cats and remotes on both input paths`() {
        assumeTrue(model.exists())
        val file = image("000000039769")
        assumeTrue("COCO image not reachable", file != null)
        val img = ImageIO.read(file)
        val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val input = FloatArray(3 * Yolo.INPUT_SIZE * Yolo.INPUT_SIZE)

        val fromArgb = run(input, Yolo.preprocessArgb(argb, img.width, img.height, 0, input))
        assertEquals(2, fromArgb.count { it.classId == 17 && it.score > 0.8f })
        assertTrue(fromArgb.any { it.classId == 75 })

        val fromYuv = run(input, Yolo.preprocess(toYuv(argb, img.width, img.height), 0, input))
        assertEquals(2, fromYuv.count { it.classId == 17 && it.score > 0.8f })
        // Both paths agree on the cat boxes.
        val a = fromArgb.filter { it.classId == 17 }.sortedBy { it.box.cx }
        val b = fromYuv.filter { it.classId == 17 }.sortedBy { it.box.cx }
        a.zip(b).forEach { (p, q) -> assertTrue(p.box.iou(q.box) > 0.85f) }
    }

    @Test
    fun `rotation path finds the person in an image turned on its side`() {
        assumeTrue(model.exists())
        val file = image("000000000785")
        assumeTrue("COCO image not reachable", file != null)
        val img = ImageIO.read(file)
        val w = img.width; val h = img.height
        val upright = img.getRGB(0, 0, w, h, null, 0, w)
        // Simulate a sensor image that must be turned 90° clockwise to be upright: rotate 90° CCW.
        val sensor = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) sensor[(w - 1 - x) * h + y] = upright[y * w + x]
        val input = FloatArray(3 * Yolo.INPUT_SIZE * Yolo.INPUT_SIZE)
        val person = run(input, Yolo.preprocessArgb(sensor, h, w, 90, input)).filter { it.classId == 1 }
        assertTrue("person found after rotation: $person", person.any { it.score > 0.8f })
    }
}
