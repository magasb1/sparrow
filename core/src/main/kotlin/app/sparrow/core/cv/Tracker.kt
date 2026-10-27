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
 * ByteTrack-style multi-object tracker (Zhang et al. 2022), in continuous time because detection
 * runs at a few hertz with uneven intervals:
 *
 * - each track's centre is a constant-velocity Kalman filter per axis, its size a running average;
 * - BYTE association: detections scoring at least [highScore] are matched first (IoU with the
 *   predicted box, greedy, per class); the low-scoring rest are then matched to tracks that were
 *   seen in the previous update and are still unmatched, which keeps ids through partial occlusion
 *   and motion blur. Only detections scoring at least [newTrackScore] start tracks;
 * - a track is confirmed after [minHits] matches and dropped after [maxAgeUs] without one; an
 *   unconfirmed track that misses an update is dropped at once, so one-off false positives never
 *   confirm.
 *
 * [snapshot] extrapolates tracks to any video frame, at most [maxExtrapolationUs] past their last
 * detection.
 */
class Tracker(
    private val highScore: Float = 0.5f,
    /** ByteTrack's det_thresh: a little above [highScore], so weak one-offs do not start tracks. */
    private val newTrackScore: Float = highScore + 0.1f,
    private val iouThreshold: Float = 0.3f,
    /** Low-scoring detections need a tighter match: they are often fragments. */
    private val lowIouThreshold: Float = 0.5f,
    private val minHits: Int = 2,
    private val maxAgeUs: Long = 1_500_000,
    private val maxExtrapolationUs: Long = 500_000,
    private val sizeSmoothing: Float = 0.6f,
) {
    /** Kalman filter for one axis: position and velocity, white-noise acceleration model. */
    private class Axis(z: Float) {
        var p = z
        var v = 0f
        private var p00 = MEASUREMENT_VAR
        private var p01 = 0f
        private var p11 = INITIAL_VELOCITY_VAR

        fun predict(dt: Float) {
            p += v * dt
            val n00 = p00 + 2 * dt * p01 + dt * dt * p11 + ACCEL_VAR * dt * dt * dt / 3
            val n01 = p01 + dt * p11 + ACCEL_VAR * dt * dt / 2
            p11 += ACCEL_VAR * dt
            p00 = n00
            p01 = n01
        }

        fun update(z: Float) {
            val s = p00 + MEASUREMENT_VAR
            val k0 = p00 / s
            val k1 = p01 / s
            val y = z - p
            p += k0 * y
            v += k1 * y
            p11 -= k1 * p01
            p00 *= 1 - k0
            p01 *= 1 - k0
        }
    }

    private class Track(val id: Int, val classId: Int, box: Box, var score: Float, var timeUs: Long) {
        val x = Axis(box.cx)
        val y = Axis(box.cy)
        var w = box.width
        var h = box.height
        var hits = 1
        /** Time the filter state refers to; moves forward in [predictTo]. */
        var stateUs = timeUs

        fun predictTo(atUs: Long, capUs: Long) {
            val dt = ((atUs - stateUs).coerceIn(0, capUs)) / 1e6f
            if (dt > 0) {
                x.predict(dt)
                y.predict(dt)
            }
            stateUs = atUs
        }

        fun boxAt(atUs: Long, capUs: Long): Box {
            val dt = ((atUs - stateUs).coerceIn(0, capUs)) / 1e6f
            return Box.ofCenter(x.p + x.v * dt, y.p + y.v * dt, w, h)
        }
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var lastUpdateUs = Long.MIN_VALUE

    /** Number of detections at or above [highScore] in the latest update. */
    var lastDetectionCount = 0
        private set

    @Synchronized
    fun update(detections: List<Detection>, timeUs: Long) {
        tracks.removeAll { timeUs - it.timeUs > maxAgeUs }
        for (t in tracks) t.predictTo(timeUs, maxExtrapolationUs)
        val (high, low) = detections.partition { it.score >= highScore }
        lastDetectionCount = high.size

        val unmatched = ArrayList(tracks)
        val newTracks = match(unmatched, high, iouThreshold, timeUs)
        // Second association: tracks seen last time that nothing high-scoring claimed.
        val recent = unmatched.filter { it.timeUs == lastUpdateUs }.toMutableList()
        match(recent, low, lowIouThreshold, timeUs)

        tracks.removeAll { it.hits < minHits && it.timeUs != timeUs }
        for (d in newTracks) if (d.score >= newTrackScore) tracks.add(Track(nextId++, d.classId, d.box, d.score, timeUs))
        lastUpdateUs = timeUs
    }

    /**
     * Greedy IoU matching per class; matched tracks are removed from [candidates] and updated.
     * Returns the detections left over.
     */
    // ponytail: greedy matching, Hungarian (lapjv) if scenes get crowded enough for it to matter
    private fun match(candidates: MutableList<Track>, dets: List<Detection>, minIou: Float, timeUs: Long): List<Detection> {
        val pairs = ArrayList<Triple<Float, Track, Detection>>()
        for (t in candidates) {
            val p = t.boxAt(timeUs, maxExtrapolationUs)
            for (d in dets) {
                if (d.classId != t.classId) continue
                val iou = p.iou(d.box)
                if (iou >= minIou) pairs.add(Triple(iou, t, d))
            }
        }
        pairs.sortByDescending { it.first }
        val used = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Detection, Boolean>())
        for ((_, t, d) in pairs) {
            if (t !in candidates || d in used) continue
            candidates.remove(t)
            used.add(d)
            t.x.update(d.box.cx)
            t.y.update(d.box.cy)
            t.w = sizeSmoothing * d.box.width + (1 - sizeSmoothing) * t.w
            t.h = sizeSmoothing * d.box.height + (1 - sizeSmoothing) * t.h
            t.score = d.score
            t.timeUs = timeUs
            t.hits++
        }
        return dets.filter { it !in used }
    }

    /** Confirmed tracks extrapolated to [timeUs], highest score first. */
    @Synchronized
    fun snapshot(timeUs: Long): List<TrackedObject> = tracks
        .filter { it.hits >= minHits && timeUs - it.timeUs <= maxAgeUs }
        .map { TrackedObject(it.id, it.classId, it.score, it.boxAt(timeUs, maxExtrapolationUs).clamped(), it.hits) }
        .filter { it.box.area > 0f }
        .sortedByDescending { it.score }

    @Synchronized
    fun clear() {
        tracks.clear()
        lastDetectionCount = 0
        lastUpdateUs = Long.MIN_VALUE
    }

    private companion object {
        // Normalised image units. Boxes jitter by about 1% of the frame between detections;
        // objects rarely accelerate faster than a frame width per second squared.
        const val MEASUREMENT_VAR = 1e-4f
        const val INITIAL_VELOCITY_VAR = 0.25f
        const val ACCEL_VAR = 1f
    }
}
