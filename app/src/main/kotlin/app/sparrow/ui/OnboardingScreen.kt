package app.sparrow.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * First-run guide (BG-5): permissions, battery-optimisation exemption and the OEM background
 * killers that otherwise stop long screen-off streams.
 */
@SuppressLint("BatteryLife")
@Composable
fun OnboardingScreen(onRequestPermissions: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().systemBarsPadding().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 680.dp).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Set up Sparrow", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Sparrow streams for hours with the screen off. Three things make that reliable.")

            Section("1. Permissions") {
                Text("Camera and precise location are required. Notifications show the stream state and the Stop button.")
                OutlinedButton(onClick = onRequestPermissions) { Text("Grant permissions") }
            }

            Section("2. Battery optimisation") {
                Text(
                    if (exempt) "Sparrow is exempt from battery optimisation." else
                        "Allow Sparrow to run without battery restrictions so Android does not pause it.",
                    color = if (exempt) OkGreen else MaterialTheme.colorScheme.onSurface,
                )
                if (!exempt) {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        )
                    }) { Text("Remove restriction") }
                }
            }

            Section("3. Manufacturer settings") {
                Text(oemAdvice())
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/" + Build.MANUFACTURER.lowercase())))
                }) { Text("Instructions for ${Build.MANUFACTURER}") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Open app settings") }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }
    }
}

private fun oemAdvice(): String = when (Build.MANUFACTURER.lowercase()) {
    "samsung" -> "Samsung: Settings › Battery › Background usage limits — add Sparrow to “Never sleeping apps”."
    "xiaomi", "redmi", "poco" -> "Xiaomi: enable Autostart and set Battery saver to “No restrictions” for Sparrow."
    "huawei", "honor" -> "Huawei/Honor: Battery › App launch — set Sparrow to “Manage manually” with all toggles on."
    "oneplus", "oppo", "realme" -> "OnePlus/Oppo/Realme: Battery › allow background activity and disable “Optimise battery use”."
    else -> "Some manufacturers stop background apps beyond standard Android rules. Check that nothing restricts Sparrow."
}
