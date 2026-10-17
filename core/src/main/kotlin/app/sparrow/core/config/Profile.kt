package app.sparrow.core.config

import app.sparrow.core.klv.St0601
import app.sparrow.core.klv.Tag
import app.sparrow.core.ts.KlvCarriage
import app.sparrow.core.ts.VideoCodec
import kotlinx.serialization.Serializable
import java.util.UUID

enum class SrtMode { CALLER, LISTENER }

enum class FocusMode { CONTINUOUS, INFINITY }

/** OFF; CAMERA = the device's own electronic stabilisation; GYRO = Sparrow's, with levelling. */
enum class StabilizationMode { OFF, CAMERA, GYRO }

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
    /** Replaces the earlier boolean `stabilization` key, which old profiles may still carry. */
    val stabilizationMode: StabilizationMode = StabilizationMode.GYRO,
    /** Keep the horizon level in the stream (GYRO only; crops 20 % instead of 10 %). */
    val levelHorizon: Boolean = false,
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
        val a = advanced
        if (a.adaptiveBitrate && a.minBitrateKbps > a.maxBitrateKbps) add("ABR minimum exceeds maximum")
    }
}
