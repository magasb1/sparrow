package app.sparrow.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.sparrow.SparrowApp
import app.sparrow.core.config.Destination
import app.sparrow.core.config.MetadataSettings
import app.sparrow.core.config.Profile
import app.sparrow.core.config.SrtMode
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

/**
 * Renders each screen with sample data and writes PNGs to docs/screenshots (see README).
 * Camera preview is a SurfaceView and renders black off-device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-night-xxhdpi")
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
    fun reset() = StreamController.reset()

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

    @Test
    fun mainIdle() = shot("02-main-idle") {
        MainScreen(app.profiles, interrupted = false, onDismissInterrupted = {}, onStart = {}, onStop = {}, onNavigate = {})
    }

    @Test
    fun mainLive() {
        StreamController.update {
            StreamStatus(
                link = LinkState.Live, running = true, profileName = "Field team",
                targetKbps = 4000, sendKbps = 3912.0, rttMs = 48.0, lossPercent = 0.2,
                gnss = GnssStatus(hasFix = true, accuracyM = 3.8f, ageMs = 400, usingGnssTime = true),
                resolution = "1280×720 H264", zoomRange = 1.0..8.0,
            )
        }
        shot("03-main-live") {
            MainScreen(app.profiles, interrupted = false, onDismissInterrupted = {}, onStart = {}, onStop = {}, onNavigate = {})
        }
    }

    @Test
    fun mainNoFix() {
        StreamController.update {
            StreamStatus(
                link = LinkState.Reconnecting(4000, "Connection setup failure: connection timed out"), running = true,
                profileName = "Field team", targetKbps = 4000,
                gnss = GnssStatus(hasFix = false, ageMs = 12_000), resolution = "1280×720 H264",
            )
        }
        shot("04-main-reconnecting") {
            MainScreen(app.profiles, interrupted = true, onDismissInterrupted = {}, onStart = {}, onStop = {}, onNavigate = {})
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h4300dp-night-xxhdpi")
    fun settings() = shot("05-settings") { SettingsScreen(app.profiles, onBack = {}) }

    @Test
    fun profilesScreen() = shot("06-profiles") { ProfilesScreen(app.profiles, onBack = {}) }

    @Test
    @Config(qualifiers = "w400dp-h1560dp-night-xxhdpi")
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
        AppLog.i("Video encoder c2.qti.avc.encoder: H264 1280x720 30 fps 4000 kbit/s")
        AppLog.i("Camera 0 opened")
        AppLog.w("SRT link down (Connection setup failure: connection timed out); retry in 1000 ms")
        AppLog.i("SRT connected to video.example.net/203.0.113.10:8890")
        AppLog.i("Default network changed to 112")
        AppLog.i("Network changed; reconnecting SRT")
        AppLog.i("ABR: video bitrate → 3000 kbit/s")
        shot("08-log") { LogScreen(onBack = {}) }
    }
}
