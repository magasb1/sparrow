package app.sparrow.core.srt

import app.sparrow.core.cv.Box
import kotlin.math.roundToInt

/**
 * Content-adaptive bitrate: a static scene needs far fewer bits than a busy one, so the target
 * falls towards [staticFraction] of the link ceiling when nothing moves and returns to full as
 * soon as motion or camera movement appears. Increases are paced by [next], so a scene that
 * starts moving after a long static spell does not burst onto a link that has not carried it.
 */
class ContentBitrate(
    private val minKbps: Int,
    private val staticFraction: Double = 0.5,
    /** Moving fraction of the frame at which the full bitrate is used. */
    private val busyActivity: Double = 0.03,
    private val quietActivity: Double = 0.002,
) {
    private var smoothed = 1.0
    var fraction = 1.0
        private set

    /** Feeds one motion sample; [cameraMoving] forces the full bitrate. */
    fun onActivity(activity: Float, cameraMoving: Boolean) {
        val instant = if (cameraMoving) 1.0
        else ((activity - quietActivity) / (busyActivity - quietActivity)).coerceIn(0.0, 1.0)
        // Rise fast (quality first when things start moving), fall slowly.
        smoothed = if (instant > smoothed) instant else smoothed * 0.97 + instant * 0.03
        fraction = staticFraction + (1 - staticFraction) * smoothed
    }

    /** Desired encoder target given the link ABR's current ceiling. */
    fun targetKbps(linkCeilingKbps: Int): Int = (linkCeilingKbps * fraction).roundToInt().coerceAtLeast(minKbps)

    /** Full rate again, e.g. when motion samples stop arriving. */
    fun reset() {
        smoothed = 1.0
        fraction = 1.0
    }

    companion object {
        /**
         * One step from [currentKbps] towards [desiredKbps]: down at once, up by at most 25% (plus
         * 50 kbit/s) per call, made about once a second.
         */
        fun next(currentKbps: Int, desiredKbps: Int): Int =
            if (desiredKbps <= currentKbps) desiredKbps else minOf(desiredKbps, (currentKbps * 1.25).roundToInt() + 50)
    }
}

/** A region and its QP offset in encoder pixels; negative offsets mean higher quality. */
data class QpRegion(val left: Int, val top: Int, val right: Int, val bottom: Int, val qpOffset: Int)

/**
 * Region-of-interest encoding (Android 15 `qp-offset-rects`): tracked targets get the most bits,
 * other motion a little more, and the encoder's rate control takes the difference from the
 * static background.
 */
object RoiPlanner {
    const val TRACK_QP_OFFSET = -6
    const val MOTION_QP_OFFSET = -3
    private const val MACROBLOCK = 16

    fun plan(width: Int, height: Int, tracks: List<Box>, motion: List<Box>, maxRegions: Int = 8): List<QpRegion> {
        val regions = ArrayList<QpRegion>()
        // Grow boxes by 10% so motion between updates stays inside the region.
        tracks.take(maxRegions).forEach { regions.add(region(it, width, height, TRACK_QP_OFFSET)) }
        motion.sortedByDescending { it.area }
            .filter { m -> tracks.none { it.iou(m) > 0.3f } }
            .take(maxRegions - regions.size)
            .forEach { regions.add(region(it, width, height, MOTION_QP_OFFSET)) }
        return regions
    }

    private fun region(b: Box, w: Int, h: Int, qp: Int): QpRegion {
        val gx = b.width * 0.1f
        val gy = b.height * 0.1f
        fun down(v: Float, max: Int) = ((v * max).toInt() / MACROBLOCK * MACROBLOCK).coerceIn(0, max - 1)
        fun up(v: Float, max: Int) = (((v * max).toInt() + MACROBLOCK - 1) / MACROBLOCK * MACROBLOCK - 1).coerceIn(0, max - 1)
        return QpRegion(down(b.x1 - gx, w), down(b.y1 - gy, h), up(b.x2 + gx, w), up(b.y2 + gy, h), qp)
    }

    /** MediaCodec PARAMETER_KEY_QP_OFFSET_RECTS format: "top,left-bottom,right=offset;…". */
    fun format(regions: List<QpRegion>): String =
        regions.joinToString(";") { "${it.top},${it.left}-${it.bottom},${it.right}=${it.qpOffset}" }
}
