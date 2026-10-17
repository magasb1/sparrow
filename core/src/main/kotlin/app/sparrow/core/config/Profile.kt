package app.sparrow.core.config

import app.sparrow.core.klv.St0601
import app.sparrow.core.klv.Tag
import app.sparrow.core.ts.KlvCarriage
import app.sparrow.core.ts.VideoCodec
import kotlinx.serialization.Serializable
import java.util.UUID

enum class SrtMode { CALLER, LISTENER }

enum class FocusMode { CONTINUOUS, INFINITY }

/** Encoder rate control. VBR spends bits on motion and saves them on static scenes. */
enum class RateControl { VBR, CBR }

@Serializable
data class Destination(
    val mode: SrtMode = SrtMode.CALLER,
    /** Remote host in caller mode; optional local bind address in listener mode. */
    val host: String = "",
    val port: Int = 9000,
    val streamId: String = "",
    val latencyMs: Int = 500,
    /** Empty = unencrypted. Kept out of exported JSON and stored via the Keystore. */
    val passphrase: String = "",
    /** AES key length in bytes: 0 (default, 16), 16, 24 or 32. */
    val keyLength: Int = 0,
)

@Serializable
data class VideoSettings(
    /** Camera2 id; null = first back camera. */
    val cameraId: String? = null,
    val codec: VideoCodec = VideoCodec.H264,
    val width: Int = 1280,
    val height: Int = 720,
    // Defaults favour low bandwidth: 720p at 15 fps is about 1.2 Mbit/s with H.264 High profile.
    val fps: Int = 15,
    val bitrateKbps: Int = 1200,
    /** A receiver joining mid-stream gets an IDR on connect, so a longer GOP costs no join time. */
    val gopSeconds: Double = 2.0,
    val rateControl: RateControl = RateControl.VBR,
    val zoomRatio: Double = 1.0,
    val focus: FocusMode = FocusMode.CONTINUOUS,
    val stabilization: Boolean = true,
)

@Serializable
data class AudioSettings(
    val enabled: Boolean = false,
    val bitrateKbps: Int = 96,
    val sampleRate: Int = 48000,
    val channels: Int = 1,
)

@Serializable
data class MetadataSettings(
    val rateHz: Double = 10.0,
    /** ST 0601 tag numbers of the optional tags to send. */
    val enabledTags: Set<Int> = Tag.optionalTags.map { it.id }.toSet(),
    val missionId: String = "",
    val platformDesignation: String = "Sparrow",
    val imageSourceSensor: String = "EO",
    val callSign: String = "",
    val groundElevationM: Double = 0.0,
    val lsVersion: Int = St0601.DEFAULT_VERSION,
    val carriage: KlvCarriage = KlvCarriage.SYNC,
)

/** On-device object detection (RF-DETR Nano) and ST 0903 VMTI reporting. */
@Serializable
data class DetectionSettings(
    val enabled: Boolean = false,
    /** Inferences per second; the phone's CPU sets the real ceiling (about 3–8 Hz). */
    val rateHz: Double = 4.0,
    val minConfidence: Double = 0.5,
    /** COCO category ids to detect. */
    val classes: Set<Int> = app.sparrow.core.cv.CocoClasses.DEFAULT_WATCH,
    /** Send tracks as VMTI in KLV Tag 74. */
    val sendVmti: Boolean = true,
    /** Run the model only when MOG2 sees motion (plus a slow keep-alive), saving battery. */
    val motionGating: Boolean = true,
)

/** Bandwidth savings driven by scene content (MOG2 motion and detections). */
@Serializable
data class SmartEncodingSettings(
    /** Lower the bitrate towards [staticFraction] while nothing moves. */
    val contentAdaptive: Boolean = true,
    val staticFraction: Double = 0.5,
    /** Give tracked objects and motion more bits (Android 15+, encoder permitting). */
    val regionOfInterest: Boolean = true,
)

@Serializable
data class AdvancedSettings(
    val adaptiveBitrate: Boolean = true,
    val minBitrateKbps: Int = 400,
    /** Equal to the video bitrate by default, so ABR only ever lowers it. */
    val maxBitrateKbps: Int = 1200,
    val ptsDelayMs: Int = 250,
    val recordLocally: Boolean = false,
)

@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default",
    val destination: Destination = Destination(),
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val metadata: MetadataSettings = MetadataSettings(),
    val detection: DetectionSettings = DetectionSettings(),
    val smartEncoding: SmartEncodingSettings = SmartEncodingSettings(),
    val advanced: AdvancedSettings = AdvancedSettings(),
) {
    /** Problems that block streaming, as human-readable messages. Empty = valid. */
    fun validate(): List<String> = buildList {
        val d = destination
        if (d.mode == SrtMode.CALLER && d.host.isBlank()) add("Destination host is required in caller mode")
        if (d.port !in 1..65535) add("Port must be 1–65535")
        if (d.latencyMs !in 20..8000) add("SRT latency must be 20–8000 ms")
        if (d.passphrase.isNotEmpty() && d.passphrase.length !in 10..79) add("Passphrase must be 10–79 characters")
        if (d.keyLength !in setOf(0, 16, 24, 32)) add("AES key length must be 16, 24 or 32 bytes")
        if (d.streamId.length > 512) add("Stream ID must be at most 512 characters")
        val v = video
        if (v.width !in 160..3840 || v.height !in 120..2160) add("Resolution out of range")
        if (v.fps !in 1..60) add("Frame rate must be 1–60 fps")
        if (v.bitrateKbps !in 100..50_000) add("Video bitrate must be 100–50000 kbit/s")
        if (v.gopSeconds !in 0.1..10.0) add("GOP must be 0.1–10 s")
        val m = metadata
        if (m.rateHz < 1.0 || m.rateHz > v.fps) add("Metadata rate must be 1 Hz to the frame rate")
        if (m.lsVersion !in 1..255) add("LS version must be 1–255")
        listOf(m.missionId, m.platformDesignation, m.imageSourceSensor, m.callSign).forEach {
            if (it.toByteArray().size > St0601.MAX_STRING_BYTES) add("Metadata text fields are limited to 127 bytes")
        }
        val det = detection
        if (det.rateHz !in 0.5..15.0) add("Detection rate must be 0.5–15 Hz")
        if (det.minConfidence !in 0.05..0.99) add("Detection confidence must be 5–99 %")
        if (det.enabled && det.classes.isEmpty()) add("Choose at least one object class to detect")
        if (smartEncoding.staticFraction !in 0.2..1.0) add("Static-scene bitrate must be 20–100 %")
        val a = advanced
        if (a.adaptiveBitrate && a.minBitrateKbps > a.maxBitrateKbps) add("ABR minimum exceeds maximum")
    }
}
