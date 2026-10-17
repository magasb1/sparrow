package app.sparrow.core.cv

/** A confirmed track at one moment, in normalised stream-image coordinates. */
data class TrackedObject(
    val id: Int,
    val classId: Int,
    val score: Float,
    val box: Box,
    /** Number of detections matched so far, including the first. */
    val hits: Int,
)

/**
 * Lightweight multi-object tracker: constant-velocity prediction, greedy IoU matching per class,
 * confirmation after [minHits] detections, removal after [maxAgeUs] without one. Detection runs
 * at a few hertz, so [snapshot] extrapolates tracks to each video frame.
 */
class Tracker(
    private val iouThreshold: Float = 0.3f,
    private val minHits: Int = 2,
    private val maxAgeUs: Long = 1_500_000,
    private val maxExtrapolationUs: Long = 500_000,
    private val smoothing: Float = 0.6f,
) {
    private class Track(
        val id: Int,
        val classId: Int,
        var box: Box,
        var score: Float,
        var timeUs: Long,
        var vx: Float = 0f, // centre velocity, normalised units per second
        var vy: Float = 0f,
        var hits: Int = 1,
    ) {
        /** Last raw detection centre: velocity is measured between detections, not smoothed boxes. */
        var rawCx = box.cx
        var rawCy = box.cy

        fun predicted(atUs: Long, capUs: Long): Box {
            val dt = ((atUs - timeUs).coerceIn(0, capUs)) / 1e6f
            return Box(box.x1 + vx * dt, box.y1 + vy * dt, box.x2 + vx * dt, box.y2 + vy * dt)
        }
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1

    /** Number of detections in the latest update, confirmed or not. */
    var lastDetectionCount = 0
        private set

    @Synchronized
    fun update(detections: List<Detection>, timeUs: Long) {
        lastDetectionCount = detections.size
        tracks.removeAll { timeUs - it.timeUs > maxAgeUs }
        val pairs = ArrayList<Triple<Float, Track, Detection>>()
        for (t in tracks) {
            val p = t.predicted(timeUs, maxExtrapolationUs)
            for (d in detections) {
                if (d.classId != t.classId) continue
                val iou = p.iou(d.box)
                if (iou >= iouThreshold) pairs.add(Triple(iou, t, d))
            }
        }
        pairs.sortByDescending { it.first }
        val usedTracks = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Track, Boolean>())
        val usedDetections = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Detection, Boolean>())
        for ((_, t, d) in pairs) {
            if (t in usedTracks || d in usedDetections) continue
            usedTracks.add(t)
            usedDetections.add(d)
            val dt = (timeUs - t.timeUs) / 1e6f
            if (dt > 0) {
                val a = smoothing
                t.vx = a * ((d.box.cx - t.rawCx) / dt) + (1 - a) * t.vx
                t.vy = a * ((d.box.cy - t.rawCy) / dt) + (1 - a) * t.vy
            }
            t.rawCx = d.box.cx
            t.rawCy = d.box.cy
            val a = smoothing
            t.box = Box(
                a * d.box.x1 + (1 - a) * t.box.x1, a * d.box.y1 + (1 - a) * t.box.y1,
                a * d.box.x2 + (1 - a) * t.box.x2, a * d.box.y2 + (1 - a) * t.box.y2,
            )
            t.score = d.score
            t.timeUs = timeUs
            t.hits++
        }
        for (d in detections) {
            if (d !in usedDetections) tracks.add(Track(nextId++, d.classId, d.box, d.score, timeUs))
        }
    }

    /** Confirmed tracks extrapolated to [timeUs], highest score first. */
    @Synchronized
    fun snapshot(timeUs: Long): List<TrackedObject> = tracks
        .filter { it.hits >= minHits && timeUs - it.timeUs <= maxAgeUs }
        .map { TrackedObject(it.id, it.classId, it.score, it.predicted(timeUs, maxExtrapolationUs).clamped(), it.hits) }
        .filter { it.box.area > 0f }
        .sortedByDescending { it.score }

    @Synchronized
    fun clear() {
        tracks.clear()
        lastDetectionCount = 0
    }
}
