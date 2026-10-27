package app.sparrow.protocol

import app.sparrow.core.config.AdvancedSettings
import app.sparrow.core.config.AudioSettings
import app.sparrow.core.config.Destination
import app.sparrow.core.config.DetectionSettings
import app.sparrow.core.config.FocusMode
import app.sparrow.core.config.MetadataSettings
import app.sparrow.core.config.OrientationLock
import app.sparrow.core.config.Profile
import app.sparrow.core.config.RateControl
import app.sparrow.core.config.SrtMode
import app.sparrow.core.config.VideoSettings
import app.sparrow.core.ts.KlvCarriage
import app.sparrow.core.ts.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier
import app.sparrow.protocol.v1.Profile as PbProfile

class ProfileMapperTest {
    /** Every field differs from its default, so a field the mapper drops shows up as a mismatch. */
    private val full = Profile(
        id = "p-1",
        name = "Field team",
        destination = Destination(SrtMode.LISTENER, "10.0.0.5", 9001, "#!::r=cam-01", 800, "secret-passphrase", 32),
        video = VideoSettings("2", VideoCodec.H264, 1920, 1080, 30, 4000, 1.5, RateControl.VBR, 2.5, FocusMode.INFINITY, false, OrientationLock.LANDSCAPE),
        audio = AudioSettings(true, 128, 44100, 2),
        metadata = MetadataSettings(5.0, setOf(13, 14, 15), "M1", "Drone", "IR", "SPARROW1", 12.5, 3.5, 18, KlvCarriage.ASYNC),
        detection = DetectionSettings(true, 8.0, 0.35, setOf(1, 3), false),
        advanced = AdvancedSettings(false, 300, 5000, 150, true),
    )

    @Test
    fun roundTrip() {
        assertEquals(full, ProfileMapper.fromProto(PbProfile.parseFrom(ProfileMapper.toProto(full).toByteArray())))
        assertEquals(Profile(id = "d"), ProfileMapper.fromProto(ProfileMapper.toProto(Profile(id = "d"))))
    }

    @Test
    fun unsetEnumsMapToDefaults() {
        val p = ProfileMapper.fromProto(PbProfile.getDefaultInstance())
        assertEquals(SrtMode.CALLER, p.destination.mode)
        assertEquals(VideoCodec.H265, p.video.codec)
        assertEquals(RateControl.CBR, p.video.rateControl)
        assertEquals(FocusMode.CONTINUOUS, p.video.focus)
        assertEquals(KlvCarriage.SYNC, p.metadata.carriage)
        assertEquals(null, p.video.cameraId)
    }

    /** Fails when a field is added to the Kotlin model; update the mapper, the proto and this list. */
    @Test
    fun mapperCoversEveryField() {
        fun fields(c: Class<*>) = c.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("id", "name", "destination", "video", "audio", "metadata", "detection", "advanced", "managed"), fields(Profile::class.java))
        assertEquals(setOf("mode", "host", "port", "streamId", "latencyMs", "passphrase", "keyLength"), fields(Destination::class.java))
        assertEquals(
            setOf("cameraId", "codec", "width", "height", "fps", "bitrateKbps", "gopSeconds", "rateControl", "zoomRatio", "focus", "stabilization", "orientation"),
            fields(VideoSettings::class.java),
        )
        assertEquals(setOf("enabled", "bitrateKbps", "sampleRate", "channels"), fields(AudioSettings::class.java))
        assertEquals(
            setOf("rateHz", "enabledTags", "missionId", "platformDesignation", "imageSourceSensor", "callSign", "groundElevationM", "headingOffsetDeg", "lsVersion", "carriage"),
            fields(MetadataSettings::class.java),
        )
        assertEquals(setOf("enabled", "rateHz", "minConfidence", "classes", "sendVmti"), fields(DetectionSettings::class.java))
        assertEquals(setOf("adaptiveBitrate", "minBitrateKbps", "maxBitrateKbps", "ptsDelayMs", "recordLocally"), fields(AdvancedSettings::class.java))
    }
}
