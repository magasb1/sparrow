package app.sparrow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sparrow.core.cv.CocoClasses
import app.sparrow.core.cv.TrackedObject

private val PersonColor = Color(0xFFF2A541)
private val VehicleColor = Color(0xFF6FC3DF)

/** Track boxes over the viewfinder. Boxes are in the stream's normalised coordinates, which match the frame. */
@Composable
fun DetectionOverlay(tracks: List<TrackedObject>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Canvas(modifier.fillMaxSize()) {
        val stroke = 2.dp.toPx()
        val radius = CornerRadius(4.dp.toPx())
        val pad = 4.dp.toPx()
        fun colorOf(t: TrackedObject) = when (t.classId) {
            1 -> PersonColor
            in 2..9 -> VehicleColor
            else -> Color.White
        }
        // Boxes first, then labels from the most confident down, so labels never hide each other.
        for (t in tracks) {
            drawRoundRect(
                colorOf(t), Offset(t.box.x1 * size.width, t.box.y1 * size.height),
                Size(t.box.width * size.width, t.box.height * size.height), radius, style = Stroke(stroke),
            )
        }
        val placed = ArrayList<Rect>()
        for (t in tracks.sortedByDescending { it.score }) {
            val layout = measurer.measure("${CocoClasses.displayName(t.classId)} ${(t.score * 100).toInt()}%  #${t.id}", label)
            val w = layout.size.width + 2 * pad
            val h = layout.size.height + pad
            val left = (t.box.x1 * size.width).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
            val top = t.box.y1 * size.height
            // Above the box, else just inside its top edge, else inside its bottom edge.
            val spot = listOf(top - h, top, t.box.y2 * size.height - h)
                .filter { it >= 0 && it + h <= size.height }
                .map { Rect(left, it, left + w, it + h) }
                .firstOrNull { r -> placed.none { it.overlaps(r) } } ?: continue
            placed.add(spot)
            drawRoundRect(colorOf(t), spot.topLeft, spot.size, CornerRadius(3.dp.toPx()))
            drawText(layout, topLeft = Offset(spot.left + pad, spot.top + pad / 2))
        }
    }
}
