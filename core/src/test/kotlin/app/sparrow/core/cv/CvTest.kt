package app.sparrow.core.cv

import app.sparrow.core.geo.CameraPose
import app.sparrow.core.klv.Imapb
import app.sparrow.core.klv.LatLon
import app.sparrow.core.klv.St0601Decoder
import app.sparrow.core.klv.St0601Encoder
import app.sparrow.core.klv.Tag
import app.sparrow.core.klv.UasMetadata
import app.sparrow.core.klv.Vmti
import app.sparrow.core.klv.VmtiFrame
import app.sparrow.core.klv.VmtiTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmtiTest {
    @Test
    fun `pixel numbers are 1-based row-major`() {
        assertEquals(1L, Vmti.pixelNumber(0, 0, 1280))
        assertEquals(1280L, Vmti.pixelNumber(1279, 0, 1280))
        assertEquals(1281L, Vmti.pixelNumber(0, 1, 1280))
        assertEquals(1279 to 719, Vmti.pixelFromNumber(Vmti.pixelNumber(1279, 719, 1280), 1280))
    }

    @Test
    fun `IMAPB round trips within one step and clamps`() {
        val lat = Imapb(-90.0, 90.0, 4)
        for (v in listOf(-90.0, -45.123456, 0.0, 12.3456789, 59.9138688, 90.0)) {
            assertEquals(v, lat.decode(lat.encode(v)), lat.step)
        }
        val hae = Vmti.HAE
        assertEquals(123.4, hae.decode(hae.encode(123.4)), hae.step)
        assertEquals(19000.0, hae.decode(hae.encode(25000.0)), hae.step)
        // The value range stays below the reserved upper half of the integer space.
        assertTrue(hae.encode(19000.0) < (1L shl 15))
    }

    @Test
    fun `VMTI frame round trips`() {
        val frame = VmtiFrame(
            frameWidth = 1280, frameHeight = 720, horizontalFov = 63.2, verticalFov = 37.9, totalTargets = 3,
            targets = listOf(
                VmtiTarget(7, 100, 200, 180, 400, 87, 12, "person", LatLon(59.914, 10.7538), 52.0),
                VmtiTarget(300, 600, 300, 900, 500, 64, 1, "car"),
            ),
        )
        val back = Vmti.decode(Vmti.encode(frame))
        assertEquals(1280, back.frameWidth)
        assertEquals(3, back.totalTargets)
        assertEquals(63.2, back.horizontalFov!!, 0.01)
        assertEquals(2, back.targets.size)
        val p = back.targets[0]
        assertEquals(listOf(7, 100, 200, 180, 400, 87, 12), listOf(p.id, p.left, p.top, p.right, p.bottom, p.confidence, p.history))
        assertEquals("person", p.className)
        assertEquals(59.914, p.location!!.lat, 1e-6)
        assertEquals(10.7538, p.location!!.lon, 1e-6)
        assertEquals(52.0, p.locationHae!!, Vmti.HAE.step)
        assertEquals(300, back.targets[1].id) // multi-byte BER-OID id
        assertNull(back.targets[1].location)
    }

    @Test
    fun `VMTI rides in ST 0601 tag 74`() {
        val vmti = Vmti.encode(VmtiFrame(640, 360, null, null, 0, emptyList()))
        val packet = St0601Encoder().encode(UasMetadata(timestampUtcMicros = 1, vmti = vmti))
        val decoded = St0601Decoder.decode(packet)
        assertTrue(decoded.checksumValid)
        val frame = decoded[Tag.VMTI_LOCAL_SET] as VmtiFrame
        assertEquals(640, frame.frameWidth)
        assertEquals(0, frame.targets.size)
    }
}

class TrackerTest {
    private fun det(x: Float, cls: Int = 1, score: Float = 0.9f) = Detection(cls, score, Box(x, 0.4f, x + 0.1f, 0.6f))

    @Test
    fun `track confirmed after two hits and keeps its id while moving`() {
        val t = Tracker()
        t.update(listOf(det(0.10f)), 0)
        assertTrue(t.snapshot(0).isEmpty()) // tentative
        t.update(listOf(det(0.12f)), 250_000)
        val first = t.snapshot(250_000).single()
        for (i in 2..10) t.update(listOf(det(0.10f + 0.02f * i)), i * 250_000L)
        val later = t.snapshot(10 * 250_000L).single()
        assertEquals(first.id, later.id)
        assertEquals(11, later.hits)
        // Moving right at 0.08/s: extrapolation 250 ms ahead moves the box about 0.02.
        val ahead = t.snapshot(10 * 250_000L + 250_000).single()
        assertEquals(later.box.cx + 0.02f, ahead.box.cx, 0.006f)
    }

    @Test
    fun `classes do not merge and stale tracks expire`() {
        val t = Tracker(maxAgeUs = 1_000_000)
        repeat(3) { i -> t.update(listOf(det(0.5f, cls = 1), det(0.5f, cls = 3)), i * 200_000L) }
        assertEquals(setOf(1, 3), t.snapshot(400_000).map { it.classId }.toSet())
        assertEquals(2, t.snapshot(400_000).map { it.id }.distinct().size)
        t.update(emptyList(), 2_000_000)
        assertTrue(t.snapshot(2_000_000).isEmpty())
    }

    @Test
    fun `low-scoring detections extend a track but never start one`() {
        val t = Tracker(highScore = 0.5f)
        t.update(listOf(det(0.10f, score = 0.3f)), 0)
        t.update(listOf(det(0.10f, score = 0.3f)), 250_000)
        assertTrue(t.snapshot(250_000).isEmpty())
        t.update(listOf(det(0.10f)), 500_000)
        t.update(listOf(det(0.12f)), 750_000)
        val confirmed = t.snapshot(750_000).single()
        // Partly occluded: only a weak detection, still the same track and a fresh update.
        t.update(listOf(det(0.14f, score = 0.3f)), 1_000_000)
        val kept = t.snapshot(1_000_000).single()
        assertEquals(confirmed.id, kept.id)
        assertEquals(confirmed.hits + 1, kept.hits)
        assertEquals(0.19f, kept.box.cx, 0.01f)
        // A weak detection does not revive a track that was already unmatched last time.
        t.update(emptyList(), 1_250_000)
        t.update(listOf(det(0.18f, score = 0.3f)), 1_500_000)
        assertEquals(kept.hits, t.snapshot(1_500_000).single().hits)
    }
}

class RfDetrMathTest {
    @Test
    fun `sensor point mapping inverts the clockwise stream rotation`() {
        // A sensor point near the top-left lands top-right after a 90° clockwise turn.
        val (s, t) = 0.9f to 0.1f // stream: top right
        fun check(expected: Pair<Float, Float>, r: Int) {
            val (x, y) = RfDetr.sensorPoint(s, t, r)
            assertEquals(expected.first, x, 1e-6f)
            assertEquals(expected.second, y, 1e-6f)
        }
        check(0.1f to 0.1f, 90)
        check(0.1f to 0.9f, 180)
        check(0.9f to 0.9f, 270)
        check(s to t, 0)
    }

    @Test
    fun `postprocess decodes boxes, filters classes and suppresses duplicates`() {
        val boxes = FloatArray(RfDetr.QUERIES * 4)
        val logits = FloatArray(RfDetr.QUERIES * RfDetr.CLASS_SLOTS) { -10f }
        fun put(q: Int, cls: Int, logit: Float, cx: Float) {
            logits[q * RfDetr.CLASS_SLOTS + cls] = logit
            boxes[q * 4] = cx; boxes[q * 4 + 1] = 0.5f; boxes[q * 4 + 2] = 0.2f; boxes[q * 4 + 3] = 0.4f
        }
        put(0, 1, 3f, 0.3f)    // person, 0.95
        put(1, 1, 2f, 0.301f)  // duplicate person, suppressed
        put(2, 3, 1f, 0.7f)    // car, 0.73
        put(3, 44, 4f, 0.5f)   // bottle, not watched
        put(4, 3, -1f, 0.1f)   // car below threshold
        val d = RfDetr.postprocess(boxes, logits, 0.5f, CocoClasses.DEFAULT_WATCH)
        assertEquals(listOf(1, 3), d.map { it.classId })
        assertEquals(0.9526f, d[0].score, 1e-3f)
        assertEquals(Box(0.2f, 0.3f, 0.4f, 0.7f).x1, d[0].box.x1, 1e-6f)
    }

    @Test
    fun `targets are geolocated at the base of their box`() {
        val pose = CameraPose.fromAngles(0.0, -30.0, 0.0)
        val g = TargetGeometry(LatLon(60.0, 10.0), 50.0, 0.0, 40.0, pose, 60.0, 40.0)
        val near = TargetReport.locate(Box(0.45f, 0.6f, 0.55f, 0.95f), g)!!
        val far = TargetReport.locate(Box(0.45f, 0.3f, 0.55f, 0.5f), g)!!
        assertTrue("lower in the image is closer", near.lat < far.lat)
        assertNull(TargetReport.locate(Box(0.4f, 0.0f, 0.6f, 0.05f), g.copy(pose = CameraPose.fromAngles(0.0, 5.0, 0.0))))
        val frame = TargetReport.vmti(listOf(TrackedObject(4, 1, 0.8f, Box(0.45f, 0.6f, 0.55f, 0.95f), 5)), 2, 1280, 720, g)
        val t = frame.targets.single()
        assertEquals(listOf(576, 432, 703, 683, 80, 4), listOf(t.left, t.top, t.right, t.bottom, t.confidence, t.history))
        assertEquals(40.0, t.locationHae!!, 0.0)
        assertNotNull(t.location)
    }
}
