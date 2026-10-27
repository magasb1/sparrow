package app.sparrow.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import android.content.Intent
import app.sparrow.SparrowApp
import app.sparrow.core.config.OrientationLock
import app.sparrow.remote.BackendSession
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sparrow.stream.StreamController
import app.sparrow.stream.StreamingService

enum class Screen { Main, Settings, Profiles, Inspector, Log, Onboarding, Backend }

class MainActivity : ComponentActivity() {
    private val app get() = SparrowApp.get(this)
    private var pendingStart = false
    private var cameraGranted by mutableStateOf(false)
    /** An enrollment link opened from outside the app (sparrow://enroll?…), waiting for the user to confirm. */
    private var enrollLink by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refreshPermissions()
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
        // Only on a fresh start: after rotation the link has already been handled.
        if (savedInstanceState == null) enrollLink = enrollLinkOf(intent)
        setContent {
            SparrowTheme {
                var screen by rememberSaveable {
                    mutableStateOf(if (app.onboardingDone) Screen.Main else Screen.Onboarding)
                }
                // The profile's orientation while idle; locked to the stream's orientation while live,
                // because a stream cannot change resolution mid-flight.
                val status by StreamController.status.collectAsStateWithLifecycle()
                val profileList by app.profiles.profiles.collectAsStateWithLifecycle()
                val activeId by app.profiles.activeId.collectAsStateWithLifecycle()
                val lock = profileList.firstOrNull { it.id == activeId }?.video?.orientation ?: OrientationLock.AUTO
                LaunchedEffect(status.running, status.displayRotationDeg, lock) {
                    requestedOrientation = status.displayRotationDeg?.takeIf { status.running }?.let(::lockFor) ?: when (lock) {
                        OrientationLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
                // The link the Backend screen was opened with; cleared when it is left by any route.
                var backendLink by rememberSaveable { mutableStateOf<String?>(null) }
                BackHandler(enabled = screen != Screen.Main && screen != Screen.Onboarding) {
                    backendLink = null
                    screen = Screen.Main
                }
                LaunchedEffect(enrollLink) {
                    enrollLink?.let {
                        backendLink = it
                        enrollLink = null
                        screen = Screen.Backend
                    }
                }
                when (screen) {
                    Screen.Onboarding -> OnboardingScreen(
                        onRequestPermissions = { requestPermissions(start = false) },
                        onDone = {
                            app.onboardingDone = true
                            screen = Screen.Main
                        },
                    )
                    Screen.Main -> CameraScreen(
                        profiles = app.profiles,
                        hasCameraPermission = cameraGranted,
                        interrupted = app.interruptedSession,
                        onDismissInterrupted = { app.acknowledgeInterruption() },
                        onRequestPermissions = { requestPermissions(start = false) },
                        onStart = { requestPermissions(start = true) },
                        onStop = { StreamingService.stop(this) },
                        onNavigate = { screen = it },
                    )
                    Screen.Settings -> SettingsScreen(app.profiles, onBack = { screen = Screen.Main })
                    Screen.Profiles -> ProfilesScreen(app.profiles, onBack = { screen = Screen.Main })
                    Screen.Inspector -> InspectorScreen(onBack = { screen = Screen.Main })
                    Screen.Log -> LogScreen(onBack = { screen = Screen.Main })
                    Screen.Backend -> BackendScreen(initialLink = backendLink, onBack = {
                        backendLink = null
                        screen = Screen.Main
                    })
                }
            }
        }
    }

    /** Phone mapping from display rotation to a fixed activity orientation. */
    private fun lockFor(rotationDeg: Int) = when (rotationDeg) {
        90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enrollLinkOf(intent)?.let { enrollLink = it }
    }

    /** The enrollment link in [intent], unless the app was reopened from Recents with an old one. */
    private fun enrollLinkOf(intent: Intent?): String? {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return intent.data?.takeIf { it.scheme == "sparrow" && it.host == "enroll" }?.toString()
    }

    override fun onStart() {
        super.onStart()
        BackendSession.acquire("ui")
    }

    override fun onStop() {
        // A rotation restarts the activity at once; keep the link up through it.
        if (!isChangingConfigurations) BackendSession.release("ui")
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
