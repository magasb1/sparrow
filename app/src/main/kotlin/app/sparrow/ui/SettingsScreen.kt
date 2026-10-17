package app.sparrow.ui

import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sparrow.core.config.FocusMode
import app.sparrow.core.config.Profile
import app.sparrow.core.config.RateControl
import app.sparrow.core.config.SrtMode
import app.sparrow.core.klv.Tag
import app.sparrow.core.srt.SrtUrl
import app.sparrow.core.ts.KlvCarriage
import app.sparrow.core.ts.VideoCodec
import app.sparrow.data.ProfileRepository
import app.sparrow.stream.CameraInfo
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

private val RESOLUTIONS = listOf(640 to 360, 1280 to 720, 1920 to 1080, 3840 to 2160)

@Composable
fun SettingsScreen(profiles: ProfileRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var p by remember { mutableStateOf(profiles.active) }
    val cameras = remember { CameraInfo.all(context) }
    val errors = p.validate()

    fun applyUrl(url: String) {
        runCatching { SrtUrl.parse(url, p.destination) }
            .onSuccess { p = p.copy(destination = it); Toast.makeText(context, "Destination updated", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
    }

    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { decodeQr(context, it) }?.let { applyUrl(it) }
            ?: uri?.let { Toast.makeText(context, "No srt:// QR code found in the image", Toast.LENGTH_LONG).show() }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().systemBarsPadding().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 680.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Settings · ${p.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }

            Section("Destination") {
                ChoiceRow("SRT mode", SrtMode.entries, p.destination.mode, { p = p.copy(destination = p.destination.copy(mode = it)) }) {
                    if (it == SrtMode.CALLER) "Caller" else "Listener"
                }
                TextRow(
                    if (p.destination.mode == SrtMode.CALLER) "Host" else "Bind address (optional)",
                    p.destination.host, { p = p.copy(destination = p.destination.copy(host = it.trim())) },
                )
                NumberRow("Port", p.destination.port, { v -> v.toIntOrNull()?.let { p = p.copy(destination = p.destination.copy(port = it)) } })
                TextRow("Stream ID", p.destination.streamId, { p = p.copy(destination = p.destination.copy(streamId = it)) })
                NumberRow("Latency", p.destination.latencyMs, { v -> v.toIntOrNull()?.let { p = p.copy(destination = p.destination.copy(latencyMs = it)) } }, unit = "ms")
                TextRow(
                    "Passphrase", p.destination.passphrase, { p = p.copy(destination = p.destination.copy(passphrase = it)) },
                    secret = true, supporting = "10–79 characters; empty = no encryption. Stored in the Android Keystore.",
                )
                ChoiceRow("AES key length", listOf(0, 16, 24, 32), p.destination.keyLength, { p = p.copy(destination = p.destination.copy(keyLength = it)) }) {
                    if (it == 0) "Default" else "${it * 8}-bit"
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text != null) applyUrl(text) else Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    }) { Text("Paste srt:// URL") }
                    OutlinedButton(onClick = {
                        qrPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("QR from image") }
                }
            }

            Section("Video") {
                if (cameras.isNotEmpty()) {
                    val selected = cameras.firstOrNull { it.id == p.video.cameraId }
                    ChoiceRow("Camera", listOf<CameraInfo?>(null) + cameras, selected, { p = p.copy(video = p.video.copy(cameraId = it?.id)) }) {
                        it?.label ?: "Default back camera"
                    }
                }
                ChoiceRow("Codec", VideoCodec.entries, p.video.codec, { p = p.copy(video = p.video.copy(codec = it)) })
                ChoiceRow("Resolution", RESOLUTIONS, p.video.width to p.video.height, { (w, h) -> p = p.copy(video = p.video.copy(width = w, height = h)) }) {
                    "${it.first}×${it.second}"
                }
                NumberRow("Frame rate", p.video.fps, { v -> v.toIntOrNull()?.let { p = p.copy(video = p.video.copy(fps = it)) } }, unit = "fps")
                NumberRow("Bitrate", p.video.bitrateKbps, { v -> v.toIntOrNull()?.let { p = p.copy(video = p.video.copy(bitrateKbps = it)) } }, unit = "kbit/s")
                ChoiceRow("Rate control", RateControl.entries, p.video.rateControl, { p = p.copy(video = p.video.copy(rateControl = it)) }) {
                    if (it == RateControl.VBR) "Variable (VBR)" else "Constant (CBR)"
                }
                NumberRow("Keyframe interval (GOP)", p.video.gopSeconds, { v -> v.toDoubleOrNull()?.let { p = p.copy(video = p.video.copy(gopSeconds = it)) } }, decimal = true, unit = "s")
                NumberRow("Initial zoom", p.video.zoomRatio, { v -> v.toDoubleOrNull()?.let { p = p.copy(video = p.video.copy(zoomRatio = it)) } }, decimal = true, unit = "×")
                ChoiceRow("Focus", FocusMode.entries, p.video.focus, { p = p.copy(video = p.video.copy(focus = it)) }) {
                    if (it == FocusMode.CONTINUOUS) "Continuous" else "Infinity"
                }
                SwitchRow("Stabilisation", p.video.stabilization, { p = p.copy(video = p.video.copy(stabilization = it)) })
            }

            Section("Audio") {
                SwitchRow("AAC audio", p.audio.enabled, { p = p.copy(audio = p.audio.copy(enabled = it)) })
                if (p.audio.enabled) {
                    NumberRow("Bitrate", p.audio.bitrateKbps, { v -> v.toIntOrNull()?.let { p = p.copy(audio = p.audio.copy(bitrateKbps = it)) } }, unit = "kbit/s")
                    ChoiceRow("Sample rate", listOf(44100, 48000), p.audio.sampleRate, { p = p.copy(audio = p.audio.copy(sampleRate = it)) }) { "${it / 1000.0} kHz" }
                    ChoiceRow("Channels", listOf(1, 2), p.audio.channels, { p = p.copy(audio = p.audio.copy(channels = it)) }) { if (it == 1) "Mono" else "Stereo" }
                }
            }

            Section("Metadata (ST 0601)") {
                NumberRow("Rate", p.metadata.rateHz, { v -> v.toDoubleOrNull()?.let { p = p.copy(metadata = p.metadata.copy(rateHz = it)) } }, decimal = true, unit = "Hz")
                TextRow("Mission ID (tag 3)", p.metadata.missionId, { p = p.copy(metadata = p.metadata.copy(missionId = it)) })
                TextRow("Platform designation (tag 10)", p.metadata.platformDesignation, { p = p.copy(metadata = p.metadata.copy(platformDesignation = it)) })
                TextRow("Image source sensor (tag 11)", p.metadata.imageSourceSensor, { p = p.copy(metadata = p.metadata.copy(imageSourceSensor = it)) })
                TextRow("Call sign (tag 59)", p.metadata.callSign, { p = p.copy(metadata = p.metadata.copy(callSign = it)) })
                NumberRow("Ground elevation (MSL)", p.metadata.groundElevationM, { v -> v.toDoubleOrNull()?.let { p = p.copy(metadata = p.metadata.copy(groundElevationM = it)) } }, decimal = true, unit = "m")
                NumberRow("LS version (tag 65)", p.metadata.lsVersion, { v -> v.toIntOrNull()?.let { p = p.copy(metadata = p.metadata.copy(lsVersion = it)) } })
                ChoiceRow("KLV carriage", KlvCarriage.entries, p.metadata.carriage, { p = p.copy(metadata = p.metadata.copy(carriage = it)) }) {
                    if (it == KlvCarriage.SYNC) "Synchronous (0x15)" else "Asynchronous (0x06)"
                }
                Text("Tags", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Tag.optionalTags.forEach { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = tag.id in p.metadata.enabledTags,
                            onCheckedChange = { on ->
                                val tags = if (on) p.metadata.enabledTags + tag.id else p.metadata.enabledTags - tag.id
                                p = p.copy(metadata = p.metadata.copy(enabledTags = tags))
                            },
                        )
                        Text("${tag.id} · ${tag.label}")
                    }
                }
            }

            Section("Advanced") {
                SwitchRow("Adaptive bitrate", p.advanced.adaptiveBitrate, { p = p.copy(advanced = p.advanced.copy(adaptiveBitrate = it)) },
                    supporting = "Lowers the video bitrate when SRT reports loss or a growing send buffer.")
                if (p.advanced.adaptiveBitrate) {
                    NumberRow("Minimum bitrate", p.advanced.minBitrateKbps, { v -> v.toIntOrNull()?.let { p = p.copy(advanced = p.advanced.copy(minBitrateKbps = it)) } }, unit = "kbit/s")
                    NumberRow("Maximum bitrate", p.advanced.maxBitrateKbps, { v -> v.toIntOrNull()?.let { p = p.copy(advanced = p.advanced.copy(maxBitrateKbps = it)) } }, unit = "kbit/s")
                }
                NumberRow("Mux delay (PTS − PCR)", p.advanced.ptsDelayMs, { v -> v.toIntOrNull()?.let { p = p.copy(advanced = p.advanced.copy(ptsDelayMs = it)) } }, unit = "ms")
                SwitchRow("Record locally", p.advanced.recordLocally, { p = p.copy(advanced = p.advanced.copy(recordLocally = it)) },
                    supporting = "Saves the sent transport stream to the app's Movies folder.")
            }

            errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    profiles.save(p)
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                enabled = errors.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

private fun decodeQr(context: android.content.Context, uri: Uri): String? = runCatching {
    val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } ?: return null
    val scale = minOf(1.0, 1600.0 / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1.0) {
        android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    } else bitmap
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    val source = RGBLuminanceSource(scaled.width, scaled.height, pixels)
    MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text.takeIf { it.startsWith("srt://", ignoreCase = true) }
}.getOrNull()
