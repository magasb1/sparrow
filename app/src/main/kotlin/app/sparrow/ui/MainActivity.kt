package app.sparrow.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.sparrow.SparrowApp
import app.sparrow.stream.StreamingService

enum class Screen { Main, Settings, Profiles, Inspector, Log, Onboarding }

class MainActivity : ComponentActivity() {
    private val app get() = SparrowApp.get(this)
    private var pendingStart = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (pendingStart) {
            pendingStart = false
            val denied = result.filterValues { !it }.keys
            if (Manifest.permission.CAMERA in denied || Manifest.permission.ACCESS_FINE_LOCATION in denied) {
                Toast.makeText(this, "Camera and precise location are required to stream", Toast.LENGTH_LONG).show()
            } else {
                StreamingService.start(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SparrowTheme {
                var screen by rememberSaveable {
                    mutableStateOf(if (app.onboardingDone) Screen.Main else Screen.Onboarding)
                }
                BackHandler(enabled = screen != Screen.Main && screen != Screen.Onboarding) { screen = Screen.Main }
                when (screen) {
                    Screen.Onboarding -> OnboardingScreen(
                        onRequestPermissions = { requestPermissions(start = false) },
                        onDone = {
                            app.onboardingDone = true
                            screen = Screen.Main
                        },
                    )
                    Screen.Main -> MainScreen(
                        profiles = app.profiles,
                        interrupted = app.interruptedSession,
                        onDismissInterrupted = { app.acknowledgeInterruption() },
                        onStart = { requestPermissions(start = true) },
                        onStop = { StreamingService.stop(this) },
                        onNavigate = { screen = it },
                    )
                    Screen.Settings -> SettingsScreen(app.profiles, onBack = { screen = Screen.Main })
                    Screen.Profiles -> ProfilesScreen(app.profiles, onBack = { screen = Screen.Main })
                    Screen.Inspector -> InspectorScreen(onBack = { screen = Screen.Main })
                    Screen.Log -> LogScreen(onBack = { screen = Screen.Main })
                }
            }
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (app.profiles.active.audio.enabled) add(Manifest.permission.RECORD_AUDIO)
    }

    /** Asks for missing permissions; when [start] is set, starts streaming once granted. */
    private fun requestPermissions(start: Boolean) {
        val profile = app.profiles.active
        if (start) {
            val problems = profile.validate()
            if (problems.isNotEmpty()) {
                Toast.makeText(this, problems.first(), Toast.LENGTH_LONG).show()
                return
            }
        }
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            if (start) StreamingService.start(this)
        } else {
            pendingStart = start
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
