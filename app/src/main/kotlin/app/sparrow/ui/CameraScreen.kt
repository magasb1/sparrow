package app.sparrow.ui

import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sparrow.core.config.Profile
import app.sparrow.core.config.SrtMode
import app.sparrow.core.config.VideoSettings
import app.sparrow.data.ProfileRepository
import app.sparrow.stream.CameraInfo
import app.sparrow.stream.CameraSource
import app.sparrow.stream.LinkState
import app.sparrow.stream.PreviewTarget
import app.sparrow.stream.StreamController
import app.sparrow.stream.StreamStatus
import kotlinx.coroutines.delay

private val Scrim = Color.Black.copy(alpha = 0.45f)
private val ScrimStrong = Color.Black.copy(alpha = 0.62f)
private val Muted = Color.White.copy(alpha = 0.72f)

/**
 * Main screen, laid out like a camera app in landscape: the full viewfinder shows exactly the
 * streamed frame, with the record button, settings and menu on a rail at the right (UI-1).
 */
@Composable
fun CameraScreen(
    profiles: ProfileRepository,
    hasCameraPermission: Boolean,
    interrupted: Boolean,
    onDismissInterrupted: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNavigate: (Screen) -> Unit,
    zoomRangeOverride: ClosedFloatingPointRange<Double>? = null,
    initialShowStats: Boolean = false,
    viewfinder: @Composable (profile: Profile, idlePreview: Boolean) -> Unit = { p, idle -> CameraViewfinder(p.video, idle) },
) {
    val status by StreamController.status.collectAsStateWithLifecycle()
    val all by profiles.profiles.collectAsStateWithLifecycle()
    val activeId by profiles.activeId.collectAsStateWithLifecycle()
    val profile = all.firstOrNull { it.id == activeId } ?: all.first()
    val context = LocalContext.current
    val zoomRange = zoomRangeOverride ?: remember(profile.video.cameraId) {
        CameraInfo.all(context).let { cams -> (cams.firstOrNull { it.id == profile.video.cameraId } ?: cams.firstOrNull())?.zoomRange }
            ?: 1.0..1.0
    }
    val requestedZoom by StreamController.zoom.collectAsStateWithLifecycle()
    val tracks by StreamController.tracks.collectAsStateWithLifecycle()
    val detectionOn = profile.detection.enabled
    val zoom = (requestedZoom ?: profile.video.zoomRatio).coerceIn(zoomRange)

    var startPending by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(initialShowStats) }
    var showBanner by remember { mutableStateOf(interrupted) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val idlePreview = !status.running && !startPending && hasCameraPermission && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // The idle preview owns the camera until Start; release it, then hand over to the service.
    LaunchedEffect(startPending) {
        if (startPending) {
            delay(150)
            onStart()
            delay(2500)
            startPending = false
        }
    }

    // Camera-app behaviour: keep the screen on and hide the system bars (swipe to reveal).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val portrait = maxHeight > maxWidth
        // The stream is upright for the way the phone is held, so the frame follows the screen.
        val frameAspect = if (portrait) profile.video.height.toFloat() / profile.video.width else profile.video.width.toFloat() / profile.video.height

        val frame: @Composable (Modifier) -> Unit = { m ->
            Box(
                m.clip(RoundedCornerShape(4.dp)).pointerInput(zoomRange) {
                    detectTransformGestures { _, _, scale, _ ->
                        val current = StreamController.zoom.value ?: profile.video.zoomRatio
                        StreamController.setZoom((current * scale).coerceIn(zoomRange))
                    }
                },
            ) {
                if (hasCameraPermission) {
                    viewfinder(profile, idlePreview)
                    if (detectionOn && status.running) DetectionOverlay(tracks)
                } else {
                    PermissionPrompt(onRequestPermissions, Modifier.align(Alignment.Center).padding(24.dp))
                }
                // Top and bottom scrims keep overlays legible on bright scenes.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Scrim, 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to Scrim)))

                Box(Modifier.fillMaxSize().padding(14.dp)) {
                    TopLeftCluster(status, profile, all, enabled = !status.running, onSelect = { profiles.select(it) }, onNavigate = onNavigate)
                    InfoChips(status, profile, tracks.size.takeIf { detectionOn }, stacked = portrait, modifier = Modifier.align(Alignment.TopEnd))
                    if (hasCameraPermission && zoomRange.endInclusive > zoomRange.start + 0.01 && !(portrait && showStats)) {
                        ZoomPresets(
                            zoom, zoomRange, onZoom = { StreamController.setZoom(it) },
                            // Portrait is too narrow for the zoom pill beside the two buttons.
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (portrait) 62.dp else 0.dp),
                        )
                    }
                    Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimatedVisibility(showStats, enter = fadeIn(), exit = fadeOut()) { StatsPanel(status, profile) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RailButton(Icons.Outlined.Info, "Stream details", selected = showStats) { showStats = !showStats }
                            RailButton(Icons.Outlined.CenterFocusStrong, if (detectionOn) "Turn object detection off" else "Turn object detection on", selected = detectionOn) {
                                val on = !detectionOn
                                profiles.save(profile.copy(detection = profile.detection.copy(enabled = on)))
                                StreamController.setDetectionEnabled(on)
                            }
                        }
                    }
                    Banner(
                        status, showBanner, onDismiss = { showBanner = false; onDismissInterrupted() },
                        Modifier.align(Alignment.TopCenter).padding(top = if (portrait) 112.dp else 56.dp),
                    )
                }
            }
        }

        val controls: @Composable (Modifier) -> Unit = { m ->
            ControlBar(
                vertical = !portrait,
                status = status,
                pending = startPending,
                onRecord = {
                    if (status.running) onStop() else if (!startPending) {
                        if (hasCameraPermission) startPending = true else onRequestPermissions()
                    }
                },
                onSettings = { onNavigate(Screen.Settings) },
                onNavigate = onNavigate,
                modifier = m,
            )
        }

        if (portrait) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    frame(Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(frameAspect))
                }
                controls(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    frame(Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(frameAspect, matchHeightConstraintsFirst = true))
                }
                controls(Modifier.fillMaxHeight().width(104.dp).padding(vertical = 16.dp))
            }
        }
    }
}

/** Live camera surface. While idle it runs a preview-only camera session; while streaming the service feeds it. */
@Composable
fun CameraViewfinder(video: VideoSettings, idlePreview: Boolean) {
    val context = LocalContext.current
    val info = remember(video.cameraId) {
        CameraInfo.all(context).let { cams -> cams.firstOrNull { it.id == video.cameraId } ?: cams.firstOrNull() }
    }
    val previewSize = remember(info, video.width, video.height) {
        info?.let { CameraInfo.chooseSize(it.previewSizes.filter { s -> s.width <= 1920 }, video.width, video.height) }
    }
    var holder by remember { mutableStateOf<SurfaceHolder?>(null) }
    val target by StreamController.preview.collectAsStateWithLifecycle()
    val zoom by StreamController.zoom.collectAsStateWithLifecycle()
    var idleCamera by remember { mutableStateOf<CameraSource?>(null) }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                previewSize?.let { this.holder.setFixedSize(it.width, it.height) }
                this.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = Unit
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                        holder = h
                        StreamController.attachPreview(PreviewTarget(h.surface, w, hh))
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        idleCamera?.setPreview(null)
                        StreamController.detachPreview(h.surface)
                    }
                })
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    LaunchedEffect(idlePreview, target, info) {
        val t = target
        if (idlePreview && t != null && info != null) {
            val cam = idleCamera ?: CameraSource(context, info, video) { }.also {
                it.start(null)
                idleCamera = it
            }
            cam.setPreview(t)
        } else {
            idleCamera?.close()
            idleCamera = null
        }
    }
    LaunchedEffect(zoom) { zoom?.let { idleCamera?.setZoom(it) } }
    DisposableEffect(Unit) {
        onDispose {
            idleCamera?.close()
            holder?.let { StreamController.detachPreview(it.surface) }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Videocam, null, tint = Muted, modifier = Modifier.size(40.dp))
        Text("Sparrow needs the camera and precise location", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onRequest).padding(horizontal = 20.dp, vertical = 10.dp),
        ) { Text("Allow access", color = Color.Black, fontWeight = FontWeight.SemiBold) }
    }
}

// --- Top left: status and profile -------------------------------------------------------------

@Composable
private fun BoxScope.TopLeftCluster(
    status: StreamStatus,
    profile: Profile,
    all: List<Profile>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    Column(Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(status)
        var open by remember { mutableStateOf(false) }
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { open = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(profile.name, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text(destinationLabel(profile), color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                }
                if (enabled) Icon(Icons.Default.ArrowDropDown, "Change profile", tint = Muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                all.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { onSelect(p.id); open = false },
                        trailingIcon = { if (p.id == profile.id) Text("✓") },
                    )
                }
                DropdownMenuItem(text = { Text("Manage profiles…") }, onClick = { open = false; onNavigate(Screen.Profiles) })
            }
        }
    }
}

private fun destinationLabel(p: Profile): String {
    val d = p.destination
    val target = if (d.mode == SrtMode.LISTENER) "Listening on :${d.port}" else "${d.host.ifBlank { "No host set" }}:${d.port}"
    return target + if (d.passphrase.isNotEmpty()) " · AES" else ""
}

@Composable
private fun StatusPill(status: StreamStatus) {
    val (label, color) = when (val l = status.link) {
        LinkState.Live -> "LIVE" to LiveRed
        LinkState.Connecting, LinkState.Starting -> "Connecting" to WarnAmber
        LinkState.Listening -> "Waiting for receiver" to WarnAmber
        is LinkState.Reconnecting -> "Reconnecting in ${(l.retryInMs / 1000).coerceAtLeast(1)} s" to WarnAmber
        is LinkState.Failed -> "Stopped" to LiveRed
        LinkState.Idle -> "Ready" to OkGreen
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by pulse.animateFloat(1f, if (status.link == LinkState.Live) 0.35f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(status.liveSinceMs) {
        while (status.liveSinceMs != null) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val live = status.link == LinkState.Live
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (live) LiveRed else ScrimStrong).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(dotAlpha).background(if (live) Color.White else color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, letterSpacing = if (live) 1.sp else 0.sp)
        status.liveSinceMs?.let {
            Spacer(Modifier.width(10.dp))
            Text(formatDuration(now - it), color = Color.White, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

// --- Top right: compact readouts ---------------------------------------------------------------

@Composable
private fun InfoChips(status: StreamStatus, profile: Profile, targets: Int?, stacked: Boolean, modifier: Modifier) {
    val content: @Composable () -> Unit = {
        if (status.running) {
            if (targets != null && status.detection != null) {
                Chip(if (targets == 1) "1 target" else "$targets targets", icon = Icons.Outlined.CenterFocusStrong)
            }
            if (status.link == LinkState.Live) {
                Chip(formatRate(status.sendKbps))
                status.rttMs?.let { Chip("RTT %.0f ms".format(it)) }
            }
            val g = status.gnss
            Chip(
                if (g.hasFix) "±%.0f m".format(g.accuracyM ?: 0f) else "No fix",
                icon = if (g.hasFix) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                tint = if (g.hasFix) Color.White else WarnAmber,
            )
        } else {
            val v = profile.video
            Chip("${v.height}p${v.fps} · ${if (v.codec.name == "H265") "HEVC" else "H.264"} · ${formatRate(v.bitrateKbps.toDouble())}")
        }
    }
    if (stacked) {
        Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

private fun formatRate(kbps: Double) = if (kbps >= 1000) "%.1f Mb/s".format(kbps / 1000) else "%.0f kb/s".format(kbps)

@Composable
private fun Chip(text: String, icon: ImageVector? = null, tint: Color = Color.White) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Scrim).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = tint, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
    }
}

// --- Bottom: zoom -------------------------------------------------------------------------------

@Composable
private fun ZoomPresets(zoom: Double, range: ClosedFloatingPointRange<Double>, onZoom: (Double) -> Unit, modifier: Modifier) {
    val presets = listOf(0.5, 1.0, 2.0, 5.0, 10.0).filter { it in range }.take(4)
    val nearest = presets.minByOrNull { kotlin.math.abs(kotlin.math.ln(it / zoom)) }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Scrim).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { p ->
            val selected = p == nearest
            val label = if (selected) formatZoom(zoom) + "×" else formatZoom(p)
            Box(
                Modifier
                    .size(if (selected) 40.dp else 34.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onZoom(p) }
                    .semantics { contentDescription = "Zoom ${formatZoom(p)}×" },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected) MaterialTheme.colorScheme.secondary else Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun formatZoom(z: Double): String = if (z < 1 || z % 1.0 > 0.05) "%.1f".format(z).trimEnd('0').trimEnd('.') else "%.0f".format(z)

// --- Right rail: settings · record · menu -------------------------------------------------------

/** Settings · record · menu: a vertical rail in landscape, a bottom bar in portrait. */
@Composable
private fun ControlBar(
    vertical: Boolean,
    status: StreamStatus,
    pending: Boolean,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier,
) {
    val settings: @Composable () -> Unit = {
        RailButton(Icons.Outlined.Settings, if (status.running) "Settings (stop the stream to edit)" else "Settings", enabled = !status.running, onClick = onSettings)
    }
    val record: @Composable () -> Unit = { RecordButton(status, pending, onRecord) }
    val menu: @Composable () -> Unit = {
        var open by remember { mutableStateOf(false) }
        Box {
            RailButton(Icons.Default.MoreVert, "More") { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Profiles") }, onClick = { open = false; onNavigate(Screen.Profiles) })
                DropdownMenuItem(text = { Text("KLV inspector") }, onClick = { open = false; onNavigate(Screen.Inspector) })
                DropdownMenuItem(text = { Text("Log") }, onClick = { open = false; onNavigate(Screen.Log) })
                DropdownMenuItem(text = { Text("Setup guide") }, onClick = { open = false; onNavigate(Screen.Onboarding) })
            }
        }
    }
    if (vertical) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            settings(); record(); menu()
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            settings(); record(); menu()
        }
    }
}

@Composable
private fun RecordButton(status: StreamStatus, pending: Boolean, onClick: () -> Unit) {
    val running = status.running
    val inner by animateDpAsState(if (running) 26.dp else 56.dp, label = "inner")
    val corner by animateDpAsState(if (running) 6.dp else 28.dp, label = "corner")
    val connecting = (running && status.link != LinkState.Live) || pending
    val ring = rememberInfiniteTransition(label = "ring")
    val ringAlpha by ring.animateFloat(1f, if (connecting) 0.3f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ringAlpha")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .border(4.dp, Color.White.copy(alpha = ringAlpha), CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = if (running) "Stop streaming" else "Start streaming" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(LiveRed))
        }
        Spacer(Modifier.height(6.dp))
        Text(if (running) "Stop" else if (pending) "Starting" else "Go live", color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RailButton(icon: ImageVector, description: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Scrim)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = (if (selected) Color.Black else Color.White).copy(alpha = if (enabled) 1f else 0.35f))
    }
}

// --- Overlays -----------------------------------------------------------------------------------

@Composable
private fun Banner(status: StreamStatus, interrupted: Boolean, onDismiss: () -> Unit, modifier: Modifier) {
    val text = when {
        status.link is LinkState.Failed && !status.running -> "Stream stopped: ${(status.link as LinkState.Failed).reason}"
        status.link is LinkState.Reconnecting -> (status.link as LinkState.Reconnecting).reason
        interrupted -> "Android stopped the last stream. Check the setup guide if this repeats."
        else -> null
    } ?: return
    Row(
        modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(12.dp)).background(ScrimStrong).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (interrupted && status.link !is LinkState.Reconnecting) {
            Spacer(Modifier.width(8.dp))
            Text("Dismiss", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(8.dp))
        }
    }
}

@Composable
private fun StatsPanel(status: StreamStatus, profile: Profile) {
    val v = profile.video
    Column(
        Modifier.widthIn(min = 240.dp, max = 300.dp).clip(RoundedCornerShape(14.dp)).background(ScrimStrong).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Stream", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        StatLine("Video", status.resolution.ifEmpty { "${v.width}×${v.height} ${v.codec}" } + " · ${v.fps} fps")
        StatLine("Bitrate", if (status.running) "${formatRate(status.sendKbps)} of ${formatRate(status.targetKbps.toDouble())}" else formatRate(v.bitrateKbps.toDouble()))
        StatLine("RTT / loss", if (status.running) (status.rttMs?.let { "%.0f ms".format(it) } ?: "–") + " / " + (status.lossPercent?.let { "%.1f %%".format(it) } ?: "–") else "–")
        StatLine("SRT latency", "${profile.destination.latencyMs} ms" + if (profile.destination.passphrase.isNotEmpty()) " · AES" else "")
        StatLine(
            "GNSS",
            when {
                !status.running -> "Starts with the stream"
                status.gnss.hasFix -> "±%.0f m".format(status.gnss.accuracyM ?: 0f) + if (status.gnss.usingGnssTime) " · GNSS time" else ""
                else -> "No fix · position omitted"
            },
            if (status.running && !status.gnss.hasFix) WarnAmber else Color.White,
        )
        status.detection?.let { d ->
            StatLine("Detection", "RF-DETR Nano · %.1f Hz · %d ms".format(d.rateHz, d.inferenceMs))
        }
        StatLine("Metadata", "ST 0601.${profile.metadata.lsVersion} · ${"%.0f".format(profile.metadata.rateHz)} Hz · ${if (profile.metadata.carriage.name == "SYNC") "sync" else "async"}")
        status.recordingPath?.let { StatLine("Recording", it.substringAfterLast('/')) }
    }
}

@Composable
private fun StatLine(label: String, value: String, color: Color = Color.White) {
    Row {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
        Text(value, color = color, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
    }
}
