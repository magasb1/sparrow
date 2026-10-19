package app.sparrow.ui

import android.content.ClipboardManager
import android.net.Uri
import android.security.NetworkSecurityPolicy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sparrow.protocol.EnrollLink
import app.sparrow.remote.BackendSession
import kotlinx.coroutines.delay

/** Register with a backend, or see and end the current registration. */
@Composable
fun BackendScreen(initialLink: String?, onBack: () -> Unit) {
    val state by BackendSession.state.collectAsStateWithLifecycle()
    BackendContent(
        state = state,
        initialLink = initialLink,
        onBack = onBack,
        onRegister = BackendSession::register,
        onUnregister = BackendSession::unregister,
    )
}

@Composable
fun BackendContent(
    state: BackendSession.State,
    initialLink: String?,
    onBack: () -> Unit,
    onRegister: (EnrollLink) -> Unit,
    onUnregister: () -> Unit,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().systemBarsPadding().verticalScroll(rememberScrollState())
                .wrapContentWidth().widthIn(max = 680.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Backend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (state.registered) Registered(state, onUnregister, nowMs) else Unregistered(state, initialLink, onRegister)
        }
    }
}

@Composable
private fun Unregistered(
    state: BackendSession.State,
    initialLink: String?,
    onRegister: (EnrollLink) -> Unit,
) {
    val context = LocalContext.current
    var text by rememberSaveable(initialLink) { mutableStateOf(initialLink ?: "") }
    val parsed = remember(text) {
        runCatching {
            EnrollLink.parse(text).also {
                // Release builds forbid cleartext; say so instead of failing with a network error.
                if (it.insecure && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted) {
                    throw EnrollLink.ParseException("This build of Sparrow only connects to servers over HTTPS")
                }
            }
        }
    }
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { decodeQrImage(context, it) }?.let { text = it }
    }
    Text(
        "Connect this phone to a Sparrow server. The server then sees the phone's status, stream statistics, " +
            "position and battery, and can send it a profile. Nothing is sent anywhere until you register.",
    )
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("On the server, open Add phone and scan the QR code, or paste the link.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Enrollment link") },
        placeholder = { Text("sparrow://enroll?…") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it.trim() }
        }) { Text("Paste") }
        OutlinedButton(onClick = {
            qrPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text("QR from image") }
    }
    parsed.getOrNull()?.let { link ->
        Text("Server: ${link.serverUrl}", fontWeight = FontWeight.SemiBold)
        if (link.insecure) {
            Text("This server uses plain HTTP: the connection is not encrypted. Use it only for testing.", color = MaterialTheme.colorScheme.error)
        } else if (link.pin == null) {
            Text("The link has no key pin, so the server needs a certificate this phone already trusts.")
        }
    }
    if (text.isNotBlank()) parsed.exceptionOrNull()?.let { Text(it.message ?: "Invalid link", color = MaterialTheme.colorScheme.error) }
    state.registerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = { parsed.getOrNull()?.let(onRegister) },
        enabled = !state.registering && parsed.isSuccess,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (state.registering) "Registering…" else "Register") }
}

@Composable
private fun Registered(state: BackendSession.State, onUnregister: () -> Unit, nowMs: () -> Long) {
    var confirm by remember { mutableStateOf(false) }
    // Re-render every few seconds so "last report" stays current.
    var tick by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tick = nowMs()
        }
    }
    val (label, color) = when (state.link) {
        BackendSession.Link.CONNECTED -> "Connected" to Color(0xFF66BB6A)
        BackendSession.Link.CONNECTING -> "Connecting…" to Color(0xFFFFB74D)
        else -> "Offline" to MaterialTheme.colorScheme.error
    }
    Text(label, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    if (state.link != BackendSession.Link.CONNECTED) state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    InfoLine("Server", state.serverUrl)
    InfoLine("Stream name", state.alias)
    InfoLine("Last report", state.lastReportAtMs?.let { ago(tick - it) } ?: "none yet")
    InfoLine("Config from server", if (state.appliedRevision == 0L) "none" else "revision ${state.appliedRevision}")
    state.configError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.insecure) Text("Plain HTTP: the connection is not encrypted.", color = MaterialTheme.colorScheme.error)
    Text(
        "While the app is open or streaming, the phone reports its state, bitrate, position and battery to the server. " +
            "A profile from the server shows up under Profiles as managed.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Unregister") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Unregister from the server?") },
            text = { Text("The phone stops reporting and its managed profile is removed. To come back it needs a new enrollment link.") },
            confirmButton = { TextButton(onClick = { confirm = false; onUnregister() }) { Text("Unregister") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.6f))
    }
}

private fun ago(ms: Long): String = when {
    ms < 10_000 -> "just now"
    ms < 60_000 -> "${ms / 1000} s ago"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}
