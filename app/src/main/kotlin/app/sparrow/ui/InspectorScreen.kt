package app.sparrow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sparrow.core.klv.St0601Decoder
import app.sparrow.core.klv.Tag
import app.sparrow.core.klv.VmtiFrame
import app.sparrow.stream.StreamController
import java.time.Instant

/** Shows the latest ST 0601 packet as sent, decoded with the app's own parser (UI-4). */
@Composable
fun InspectorScreen(onBack: () -> Unit) {
    val packet by StreamController.latestKlv.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().systemBarsPadding().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 680.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("KLV inspector", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            val bytes = packet
            if (bytes == null) {
                Text("No metadata sent yet. Start streaming to see the latest packet.")
                return@Column
            }
            val decoded = runCatching { St0601Decoder.decode(bytes) }
            decoded.onFailure { Text("Decode error: ${it.message}", color = MaterialTheme.colorScheme.error) }
            decoded.onSuccess { d ->
                Text(
                    "${bytes.size} bytes · ${d.items.size} items · checksum " + if (d.checksumValid) "valid" else "INVALID",
                    color = if (d.checksumValid) OkGreen else MaterialTheme.colorScheme.error,
                )
                HorizontalDivider()
                d.items.forEach { item ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${item.tagId}", Modifier.width(36.dp), fontFamily = FontFamily.Monospace)
                        Text(item.tag?.label ?: "Unknown", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        val value = when (item.tag) {
                            Tag.PRECISION_TIME_STAMP ->
                                (item.value as? Long)?.let { Instant.ofEpochSecond(it / 1_000_000, (it % 1_000_000) * 1000).toString() }
                            Tag.CHECKSUM -> (item.value as? Long)?.let { "0x%04X".format(it) }
                            else -> item.display
                        }
                        Text(value ?: "", Modifier.weight(1.2f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                (d[Tag.VMTI_LOCAL_SET] as? VmtiFrame)?.takeIf { it.targets.isNotEmpty() }?.let { v ->
                    HorizontalDivider()
                    Text("VMTI targets (ST 0903) · ${v.frameWidth}×${v.frameHeight}", fontWeight = FontWeight.SemiBold)
                    v.targets.forEach { t ->
                        Text(
                            "#${t.id} ${t.className ?: "?"} ${t.confidence}% · box ${t.left},${t.top}–${t.right},${t.bottom}" +
                                (t.location?.let { " · %.6f, %.6f".format(it.lat, it.lon) } ?: ""),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
                Text(
                    bytes.joinToString(" ") { "%02X".format(it) },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
