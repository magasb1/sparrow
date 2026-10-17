package app.sparrow.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sparrow.core.srt.SrtUrl
import app.sparrow.data.ProfileRepository
import app.sparrow.stream.CameraInfo
import app.sparrow.stream.LinkState
import app.sparrow.stream.PreviewTarget
import app.sparrow.stream.StreamController
import app.sparrow.stream.StreamStatus

@Composable
fun MainScreen(
    profiles: ProfileRepository,
    interrupted: Boolean,
    onDismissInterrupted: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val status by StreamController.status.collectAsStateWithLifecycle()
    val allProfiles by profiles.profiles.collectAsStateWithLifecycle()
    val activeId by profiles.activeId.collectAsStateWithLifecycle()
    val active = allProfiles.firstOrNull { it.id == activeId } ?: allProfiles.first()
    var showInterrupted by remember { mutableStateOf(interrupted) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sparrow", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onNavigate(Screen.Inspector) }) { Icon(Icons.Default.DataObject, "KLV inspector") }
                IconButton(onClick = { onNavigate(Screen.Log) }) { Icon(Icons.Default.Article, "Log") }
                IconButton(onClick = { onNavigate(Screen.Profiles) }) { Icon(Icons.Default.FolderOpen, "Profiles") }
                IconButton(onClick = { onNavigate(Screen.Settings) }, enabled = !status.running) {
                    Icon(Icons.Default.Settings, "Settings")
                }
            }

            if (showInterrupted) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("The last stream was stopped by Android", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Sparrow was killed while live. Check the battery settings in onboarding if this repeats.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { showInterrupted = false; onDismissInterrupted() }) { Text("Dismiss") }
                    }
                }
            }

            PreviewPane(status, active.video.width, active.video.height, active.video.cameraId)

            if (status.running && status.zoomRange.endInclusive > status.zoomRange.start) {
                val zoom by StreamController.zoom.collectAsStateWithLifecycle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom %.1f×".format(zoom ?: active.video.zoomRatio), Modifier.width(88.dp))
                    Slider(
                        value = (zoom ?: active.video.zoomRatio).toFloat(),
                        onValueChange = { StreamController.setZoom(it.toDouble()) },
                        valueRange = status.zoomRange.start.toFloat()..minOf(status.zoomRange.endInclusive, 10.0).toFloat(),
                    )
                }
            }

            ProfilePicker(profiles, enabled = !status.running)
            Text(
                SrtUrl.format(active.destination) + if (active.destination.passphrase.isNotEmpty()) "  · encrypted" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (status.running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LiveRed),
                ) { Text("Stop", style = MaterialTheme.typography.titleMedium) }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Start streaming", style = MaterialTheme.typography.titleMedium)
                }
            }

            StatusCard(status)
        }
    }
}

@Composable
private fun PreviewPane(status: StreamStatus, width: Int, height: Int, cameraId: String?) {
    val context = LocalContext.current
    val aspect = width.toFloat() / height
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(Color.Black, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (status.running) {
            val previewSize = remember(cameraId, width, height) {
                val cams = CameraInfo.all(context)
                val info = cams.firstOrNull { it.id == cameraId } ?: cams.firstOrNull()
                info?.let { CameraInfo.chooseSize(it.previewSizes.filter { s -> s.width <= 1920 }, width, height) }
            }
            var holderRef by remember { mutableStateOf<SurfaceHolder?>(null) }
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        previewSize?.let { holder.setFixedSize(it.width, it.height) }
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = Unit
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                                holderRef = h
                                StreamController.attachPreview(PreviewTarget(h.surface, w, hh))
                            }
                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                StreamController.detachPreview(h.surface)
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) {
                onDispose { holderRef?.let { StreamController.detachPreview(it.surface) } }
            }
            LiveBadge(status.link, Modifier.align(Alignment.TopStart).padding(8.dp))
        } else {
            Text("Preview starts with the stream", color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun LiveBadge(link: LinkState, modifier: Modifier) {
    val (label, color) = linkLabel(link)
    Row(
        modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private fun linkLabel(link: LinkState): Pair<String, Color> = when (link) {
    LinkState.Live -> "LIVE" to LiveRed
    LinkState.Connecting -> "Connecting" to WarnAmber
    LinkState.Listening -> "Waiting for receiver" to WarnAmber
    LinkState.Starting -> "Starting" to WarnAmber
    is LinkState.Reconnecting -> "Reconnecting in ${link.retryInMs / 1000} s" to WarnAmber
    is LinkState.Failed -> "Failed" to LiveRed
    LinkState.Idle -> "Idle" to Color.Gray
}

@Composable
private fun StatusCard(s: StreamStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val (label, color) = linkLabel(s.link)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("Link: $label", fontWeight = FontWeight.SemiBold)
            }
            when (val l = s.link) {
                is LinkState.Reconnecting -> Text(l.reason, style = MaterialTheme.typography.bodySmall)
                is LinkState.Failed -> Text(l.reason, style = MaterialTheme.typography.bodySmall, color = LiveRed)
                else -> Unit
            }
            if (s.running) {
                StatRow("Video", s.resolution)
                StatRow("Bitrate", "%.0f kbit/s sent · target %d".format(s.sendKbps, s.targetKbps))
                StatRow("RTT / loss", (s.rttMs?.let { "%.0f ms".format(it) } ?: "–") + " / " + (s.lossPercent?.let { "%.1f %%".format(it) } ?: "–"))
                s.recordingPath?.let { StatRow("Recording", it.substringAfterLast('/')) }
            }
            val g = s.gnss
            StatRow(
                "GNSS",
                when {
                    !s.running -> "–"
                    g.hasFix -> "Fix ±%.0f m".format(g.accuracyM ?: 0f) + if (g.usingGnssTime) " · GNSS time" else ""
                    g.ageMs != null -> "Fix lost ${g.ageMs / 1000} s ago — position tags omitted"
                    else -> "No fix — position tags omitted"
                },
                if (s.running && !g.hasFix) WarnAmber else null,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, color: Color? = null) {
    Row {
        Text(label, Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ProfilePicker(profiles: ProfileRepository, enabled: Boolean) {
    val all by profiles.profiles.collectAsStateWithLifecycle()
    val activeId by profiles.activeId.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text("Profile: " + (all.firstOrNull { it.id == activeId }?.name ?: "?"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            all.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { profiles.select(p.id); open = false })
            }
        }
    }
}
