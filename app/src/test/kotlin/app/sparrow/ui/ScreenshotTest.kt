package app.sparrow.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.sparrow.SparrowApp
import app.sparrow.core.config.Destination
import app.sparrow.core.config.MetadataSettings
import app.sparrow.core.config.Profile
import app.sparrow.core.config.SrtMode
import app.sparrow.core.cv.Box
import app.sparrow.core.cv.TrackedObject
import app.sparrow.cv.DetectionStats
import app.sparrow.core.klv.LatLon
import app.sparrow.core.klv.St0601Encoder
import app.sparrow.core.klv.UasMetadata
import app.sparrow.log.AppLog
import app.sparrow.stream.GnssStatus
import app.sparrow.stream.LinkState
import app.sparrow.stream.StreamController
import app.sparrow.stream.StreamStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import java.io.File
import android.os.SystemClock

/**
 * Renders each screen with sample data and writes PNGs to docs/screenshots (see README).
 * Camera preview is a SurfaceView and renders black off-device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w880dp-h400dp-land-night-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: SparrowApp get() = ApplicationProvider.getApplicationContext()
    private val outDir = File(System.getProperty("sparrow.screenshots.dir") ?: "build/screenshots")

    @Before
    fun profiles() {
        val repo = app.profiles
        val field = Profile(
            name = "Field team",
            destination = Destination(host = "video.example.net", port = 8890, streamId = "publish:sparrow1"),
            metadata = MetadataSettings(missionId = "EXERCISE 07", callSign = "SPARROW1", groundElevationM = 12.0),
        )
        repo.save(field)
        repo.save(Profile(name = "Local listener", destination = Destination(mode = SrtMode.LISTENER, port = 9000)))
        repo.profiles.value.filter { it.name == "Default" }.forEach { repo.delete(it.id) }
        repo.select(field.id)
    }

    @After
    fun reset() {
        StreamController.reset()
        StreamController.setZoom(1.0)
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SparrowTheme { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }

    @Test
    fun onboarding() {
        ShadowBuild.setManufacturer("samsung")
        shot("01-onboarding") { OnboardingScreen(onRequestPermissions = {}, onDone = {}) }
    }

    @Composable
    private fun Camera(interrupted: Boolean = false, permission: Boolean = true, stats: Boolean = false) = CameraScreen(
        profiles = app.profiles,
        hasCameraPermission = permission,
        interrupted = interrupted,
        onDismissInterrupted = {},
        onRequestPermissions = {},
        onStart = {},
        onStop = {},
        onNavigate = {},
        zoomRangeOverride = 0.6..10.0,
        initialShowStats = stats,
        viewfinder = { _, _ -> SampleScene() },
    )

    @Test
    fun mainIdle() = shot("02-main-idle") { Camera() }

    @Test
    fun mainNoPermission() = shot("09-main-permission") { Camera(permission = false) }

    private fun setLive(resolution: String = "1280×720 H264") {
        StreamController.update {
            StreamStatus(
                link = LinkState.Live, running = true, profileName = "Field team",
                targetKbps = 1200, sendKbps = 1164.0, rttMs = 48.0, lossPercent = 0.2,
                gnss = GnssStatus(hasFix = true, accuracyM = 3.8f, ageMs = 400, usingGnssTime = true),
                resolution = resolution, zoomRange = 1.0..8.0,
                liveSinceMs = SystemClock.elapsedRealtime() - 754_000,
            )
        }
        StreamController.setZoom(2.0)
    }

    @Test
    fun mainLive() {
        setLive()
        shot("03-main-live") { Camera() }
    }

    @Test
    fun mainDetails() {
        setLive()
        shot("10-main-details") { Camera(stats = true) }
    }

    @Test
    @Config(qualifiers = "w400dp-h880dp-port-night-xxhdpi")
    fun mainIdlePortrait() = shot("11-portrait-idle") { Camera() }

    @Test
    @Config(qualifiers = "w400dp-h880dp-port-night-xxhdpi")
    fun mainLivePortrait() {
        setLive("720×1280 H264")
        shot("12-portrait-live") { Camera() }
    }

    @Test
    @Config(qualifiers = "w400dp-h880dp-port-night-xxhdpi")
    fun mainDetailsPortrait() {
        setLive("720×1280 H264")
        shot("13-portrait-details") { Camera(stats = true) }
    }

    private fun setDetections() {
        val repo = app.profiles
        repo.save(repo.active.copy(detection = repo.active.detection.copy(enabled = true)))
        StreamController.update { it.copy(detection = DetectionStats(inferenceMs = 182, rateHz = 4.0, detections = 4)) }
        StreamController.publishTracks(
            listOf(
                TrackedObject(12, 1, 0.91f, Box(0.30f, 0.50f, 0.36f, 0.68f), 40),
                TrackedObject(14, 1, 0.84f, Box(0.38f, 0.52f, 0.43f, 0.67f), 22),
                TrackedObject(9, 3, 0.88f, Box(0.55f, 0.56f, 0.70f, 0.66f), 61),
                TrackedObject(3, 9, 0.73f, Box(0.12f, 0.70f, 0.26f, 0.80f), 95),
            ),
        )
    }

    @Test
    fun mainDetections() {
        setLive()
        setDetections()
        shot("14-detections") { Camera() }
    }

    @Test
    @Config(qualifiers = "w400dp-h880dp-port-night-xxhdpi")
    fun mainDetectionsPortrait() {
        setLive("720×1280 H264")
        setDetections()
        shot("15-detections-portrait") { Camera(stats = true) }
    }

    @Test
    fun mainNoFix() {
        StreamController.update {
            StreamStatus(
                link = LinkState.Reconnecting(4000, "Connection setup failure: connection timed out"), running = true,
                profileName = "Field team", targetKbps = 1200,
                gnss = GnssStatus(hasFix = false, ageMs = 12_000), resolution = "1280×720 H264",
            )
        }
        shot("04-main-reconnecting") { Camera() }
    }

    @Test
    @Config(qualifiers = "w880dp-h4300dp-land-night-xxhdpi")
    fun settings() = shot("05-settings") { SettingsScreen(app.profiles, onBack = {}) }

    @Test
    fun profilesScreen() = shot("06-profiles") { ProfilesScreen(app.profiles, onBack = {}) }

    @Test
    @Config(qualifiers = "w880dp-h1300dp-land-night-xxhdpi")
    fun inspector() {
        val packet = St0601Encoder().encode(
            UasMetadata(
                timestampUtcMicros = 1_792_241_234_567_000L,
                missionId = "EXERCISE 07", platformDesignation = "Sparrow", imageSourceSensor = "EO", callSign = "SPARROW1",
                platformHeading = 87.4, platformPitch = 0.0, platformRoll = 0.0,
                sensorLatitude = 59.9138688, sensorLongitude = 10.7522454, sensorTrueAltitude = 42.0, sensorEllipsoidHeight = 81.3,
                horizontalFov = 63.2, verticalFov = 37.9,
                sensorRelativeAzimuth = 0.0, sensorRelativeElevation = -18.5, sensorRelativeRoll = 1.2,
                slantRange = 94.7, frameCenter = LatLon(59.9140, 10.7538), frameCenterElevation = 12.0,
                corners = listOf(LatLon(59.9149, 10.7551), LatLon(59.9134, 10.7562), LatLon(59.9137, 10.7529), LatLon(59.9142, 10.7524)),
            ),
        )
        StreamController.publishKlv(packet)
        shot("07-klv-inspector") { InspectorScreen(onBack = {}) }
    }

    @Test
    fun log() {
        AppLog.i("Starting 'Field team' with Back camera 0 (4.4 mm, 66° HFOV) at 1280x720")
        AppLog.i("Video encoder c2.qti.avc.encoder: H264 1280x720 15 fps 1200 kbit/s")
        AppLog.i("Camera 0 opened")
        AppLog.w("SRT link down (Connection setup failure: connection timed out); retry in 1000 ms")
        AppLog.i("SRT connected to video.example.net/203.0.113.10:8890")
        AppLog.i("Default network changed to 112")
        AppLog.i("Network changed; reconnecting SRT")
        AppLog.i("ABR: video bitrate → 900 kbit/s")
        shot("08-log") { LogScreen(onBack = {}) }
    }
}

/** Stand-in for the camera feed off-device: a dusk sky over a harbour skyline. */
@Composable
private fun SampleScene() {
    androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to androidx.compose.ui.graphics.Color(0xFF3B5876),
                0.55f to androidx.compose.ui.graphics.Color(0xFFD9A47A),
                0.62f to androidx.compose.ui.graphics.Color(0xFF6E5A57),
                1f to androidx.compose.ui.graphics.Color(0xFF1E2A30),
            ),
        )
        val skyline = androidx.compose.ui.graphics.Color(0xFF26313A)
        val buildings = listOf(0.00 to 0.40, 0.06 to 0.33, 0.11 to 0.45, 0.17 to 0.30, 0.24 to 0.38, 0.30 to 0.27, 0.37 to 0.42,
            0.44 to 0.35, 0.52 to 0.24, 0.57 to 0.36, 0.64 to 0.31, 0.71 to 0.44, 0.78 to 0.29, 0.85 to 0.39, 0.92 to 0.33)
        buildings.forEach { (x, top) ->
            drawRect(skyline, topLeft = androidx.compose.ui.geometry.Offset((x * w).toFloat(), (top * h + 0.18f * h).toFloat()),
                size = androidx.compose.ui.geometry.Size((0.065f * w), (0.62f - top.toFloat() - 0.18f) * h + 1))
        }
        drawRect(androidx.compose.ui.graphics.Color(0xFF203039), topLeft = androidx.compose.ui.geometry.Offset(0f, 0.62f * h),
            size = androidx.compose.ui.geometry.Size(w, 0.38f * h))
        repeat(9) { i ->
            drawRect(androidx.compose.ui.graphics.Color(0x33F2C58F), topLeft = androidx.compose.ui.geometry.Offset(w * (0.08f + i * 0.1f), h * (0.66f + (i % 3) * 0.05f)),
                size = androidx.compose.ui.geometry.Size(w * 0.05f, h * 0.006f))
        }
    }
}
