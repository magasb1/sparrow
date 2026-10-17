package app.sparrow.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LiveRed = Color(0xFFE5484D)
val OkGreen = Color(0xFF3FB27F)
val WarnAmber = Color(0xFFF2A541)

private val Dark = darkColorScheme(
    primary = Color(0xFF6FC3DF),
    onPrimary = Color(0xFF00222E),
    secondary = Color(0xFFF2A541),
    background = Color(0xFF101418),
    surface = Color(0xFF161B21),
    surfaceVariant = Color(0xFF222A32),
    onSurface = Color(0xFFE8EEF2),
    onSurfaceVariant = Color(0xFFB5C0C8),
    error = LiveRed,
)

private val Light = lightColorScheme(
    primary = Color(0xFF00677F),
    secondary = Color(0xFF8A5100),
    error = Color(0xFFBA1A1A),
)

@Composable
fun SparrowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
