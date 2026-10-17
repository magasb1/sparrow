package app.sparrow.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sparrow.core.config.Profile
import app.sparrow.core.srt.SrtUrl
import app.sparrow.data.ProfileRepository
import app.sparrow.stream.StreamController
import java.util.UUID

/** Named profiles with JSON export/import (UI-3). Exports never include passphrases. */
@Composable
fun ProfilesScreen(profiles: ProfileRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val all by profiles.profiles.collectAsStateWithLifecycle()
    val activeId by profiles.activeId.collectAsStateWithLifecycle()
    val running = StreamController.status.collectAsStateWithLifecycle().value.running
    var renaming by remember { mutableStateOf<Profile?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(profiles.exportJson().toByteArray()) } }
            .onSuccess { Toast.makeText(context, "Exported ${all.size} profiles (without passphrases)", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)!!.use { profiles.importJson(it.readBytes().decodeToString()) } }
            .onSuccess { Toast.makeText(context, "Imported $it profiles", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().systemBarsPadding().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 680.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Profiles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            all.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p.id == activeId, onClick = { profiles.select(p.id) }, enabled = !running)
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text(java.net.URLDecoder.decode(SrtUrl.format(p.destination), "UTF-8"), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { renaming = p }) { Icon(Icons.Default.Edit, "Rename") }
                        IconButton(onClick = {
                            profiles.save(p.copy(id = UUID.randomUUID().toString(), name = p.name + " copy"))
                        }) { Icon(Icons.Default.ContentCopy, "Duplicate") }
                        IconButton(onClick = { profiles.delete(p.id) }, enabled = !(running && p.id == activeId)) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { profiles.save(Profile(name = "Profile ${all.size + 1}")) }) { Text("New") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) }) { Text("Import") }
                OutlinedButton(onClick = { exporter.launch("sparrow-profiles.json") }) { Text("Export") }
            }
        }
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename profile") },
            text = { TextRow("Name", name, { name = it }) },
            confirmButton = {
                TextButton(onClick = { profiles.save(p.copy(name = name.ifBlank { p.name })); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
