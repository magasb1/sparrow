package app.sparrow.core.cv

import app.sparrow.core.srt.ContentBitrate
import app.sparrow.core.srt.RoiPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class Mog2Test {
    private val w = 160
    private val h = 90
    private val rnd = Random(42)

    /** Textured static background with sensor noise, optionally a bright square at (x, y). */
    private fun frame(squareX: Int? = null, squareY: Int = 40, size: Int = 12): ByteArray = ByteArray(w * h) { p ->
        val x = p % w
        val y = p / w
        val bg = 60 + (x * 3 + y * 5) % 50 + rnd.nextInt(-3, 4)
        val inSquare = squareX != null && x in squareX until squareX + size && y in squareY until squareY + size
        (if (inSquare) 230 else bg).toByte()
    }

    @Test
    fun `static noisy scene settles to no motion`() {
        val m = MotionAnalyzer(w, h)
        var last: MotionResult? = null
        repeat(80) { last = m.analyzeGray(frame()) }
        assertFalse(last!!.learning)
        assertTrue("activity ${last!!.activity}", last!!.activity < 0.001f)
        assertTrue(last!!.blobs.isEmpty())
    }

    @Test
    fun `moving object is found as one blob where it is`() {
        val m = MotionAnalyzer(w, h)
        repeat(80) { m.analyzeGray(frame()) }
        var result: MotionResult? = null
        for (i in 0 until 10) result = m.analyzeGray(frame(squareX = 20 + i * 6))
        val r = result!!
        assertEquals(1, r.blobs.size)
        val b = r.blobs.single()
        // Square at x 74..85, y 40..51 in the last frame.
        assertTrue("blob $b", b.x1 * w in 70f..76f && b.x2 * w in 84f..90f && b.y1 * h in 38f..42f && b.y2 * h in 50f..54f)
        assertTrue(r.activity in 0.005f..0.02f)
    }

    @Test
    fun `a stopped object becomes background`() {
        val m = MotionAnalyzer(w, h)
        repeat(80) { m.analyzeGray(frame()) }
        var r: MotionResult? = null
        repeat(400) { r = m.analyzeGray(frame(squareX = 100)) }
        assertTrue("activity ${r!!.activity}", r!!.activity < 0.001f)
    }
}

class SchedulerTest {
    private fun motion(activity: Float, blobs: List<Box> = emptyList()) = MotionResult(activity, blobs, learning = false)

    @Test
    fun `static scene runs only the keep-alive check`() {
        val s = DetectionScheduler(activeIntervalUs = 250_000, keepAliveUs = 2_000_000, holdUs = 1_000_000)
        val runs = (0 until 100).count { i -> s.shouldRun(i * 66_666L, motion(0f), false, emptyList()) }
        // 6.6 s of static frames: the first run plus keep-alives every 2 s.
        assertEquals(4, runs)
    }

    @Test
    fun `motion runs at the active rate and new blobs run at once`() {
        val s = DetectionScheduler(activeIntervalUs = 250_000)
        assertTrue(s.shouldRun(0, motion(0.01f), false, emptyList()))
        assertFalse(s.shouldRun(66_666, motion(0.01f), false, emptyList()))
        assertTrue(s.shouldRun(266_666, motion(0.01f), false, emptyList()))
        // An unexplained blob 133 ms later triggers immediately (past the 100 ms floor).
        val blob = Box(0.1f, 0.1f, 0.2f, 0.2f)
        assertTrue(s.shouldRun(400_000, motion(0.01f, listOf(blob)), false, emptyList()))
        // The same blob covered by a track does not.
        val track = TrackedObject(1, 1, 0.9f, Box(0.08f, 0.08f, 0.22f, 0.22f), 3)
        assertFalse(s.shouldRun(533_333, motion(0.01f, listOf(blob)), false, listOf(track)))
    }

    @Test
    fun `camera movement keeps the detector at its active rate`() {
        val s = DetectionScheduler(activeIntervalUs = 250_000, keepAliveUs = 2_000_000)
        val runs = (0 until 30).count { i -> s.shouldRun(i * 66_666L, MotionResult(0f, emptyList(), learning = true), true, emptyList()) }
        assertEquals(8, runs)
    }
}

class ContentBitrateTest {
    @Test
    fun `static scenes fall to the floor slowly and motion restores at once`() {
        val c = ContentBitrate(baseKbps = 1200, minKbps = 400, staticFraction = 0.5)
        assertEquals(1200, c.targetKbps(1200))
        repeat(150) { c.onActivity(0f, false) } // ~10 s at 15 Hz
        assertTrue("fraction ${c.fraction}", c.fraction < 0.52)
        assertEquals(600, c.targetKbps(1200), 10)
        c.onActivity(0.05f, false)
        assertEquals(1200, c.targetKbps(1200))
        // Never above the link ceiling, never below the floor.
        assertEquals(900, c.targetKbps(900))
        repeat(300) { c.onActivity(0f, false) }
        assertEquals(400, c.targetKbps(300))
    }

    @Test
    fun `camera movement means full bitrate`() {
        val c = ContentBitrate(1200, 400)
        repeat(200) { c.onActivity(0f, false) }
        c.onActivity(0f, cameraMoving = true)
        assertEquals(1.0, c.fraction, 1e-9)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected±$tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
}

class RoiPlannerTest {
    @Test
    fun `regions are macroblock aligned and formatted for MediaCodec`() {
        val track = Box(0.30f, 0.50f, 0.36f, 0.68f)
        val motionNearTrack = Box(0.30f, 0.50f, 0.35f, 0.67f)
        val otherMotion = Box(0.80f, 0.10f, 0.90f, 0.20f)
        val r = RoiPlanner.plan(1280, 720, listOf(track), listOf(motionNearTrack, otherMotion))
        assertEquals(2, r.size) // motion already covered by the track is dropped
        val t = r[0]
        assertEquals(RoiPlanner.TRACK_QP_OFFSET, t.qpOffset)
        listOf(t.left, t.top).forEach { assertEquals(0, it % 16) }
        listOf(t.right, t.bottom).forEach { assertEquals(15, it % 16) }
        assertTrue(t.left <= 384 && t.right >= 460 && t.top <= 360 && t.bottom >= 489)
        assertEquals(RoiPlanner.MOTION_QP_OFFSET, r[1].qpOffset)
        assertEquals("${t.top},${t.left}-${t.bottom},${t.right}=-6;${r[1].top},${r[1].left}-${r[1].bottom},${r[1].right}=-3", RoiPlanner.format(r))
    }
}
