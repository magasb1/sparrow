package app.sparrow.protocol

import app.sparrow.core.config.AdvancedSettings
import app.sparrow.core.config.AudioSettings
import app.sparrow.core.config.Destination
import app.sparrow.core.config.FocusMode
import app.sparrow.core.config.MetadataSettings
import app.sparrow.core.config.Profile
import app.sparrow.core.config.RateControl
import app.sparrow.core.config.SrtMode
import app.sparrow.core.config.VideoSettings
import app.sparrow.core.ts.KlvCarriage
import app.sparrow.core.ts.VideoCodec
import app.sparrow.protocol.v1.advancedSettings
import app.sparrow.protocol.v1.audioSettings
import app.sparrow.protocol.v1.destination
import app.sparrow.protocol.v1.metadataSettings
import app.sparrow.protocol.v1.profile
import app.sparrow.protocol.v1.videoSettings
import app.sparrow.protocol.v1.FocusMode as PbFocusMode
import app.sparrow.protocol.v1.KlvCarriage as PbKlvCarriage
import app.sparrow.protocol.v1.Profile as PbProfile
import app.sparrow.protocol.v1.RateControl as PbRateControl
import app.sparrow.protocol.v1.SrtMode as PbSrtMode
import app.sparrow.protocol.v1.VideoCodec as PbVideoCodec

/**
 * Converts between the app's [Profile] and `sparrow.v1.Profile`. Enum value 0 (not set) maps to
 * the Kotlin default. [Profile.managed] is phone-local and not part of the message.
 */
object ProfileMapper {
    fun toProto(p: Profile): PbProfile = profile {
        id = p.id
        name = p.name
        destination = destination {
            mode = when (p.destination.mode) {
                SrtMode.CALLER -> PbSrtMode.SRT_MODE_CALLER
                SrtMode.LISTENER -> PbSrtMode.SRT_MODE_LISTENER
            }
            host = p.destination.host
            port = p.destination.port
            streamId = p.destination.streamId
            latencyMs = p.destination.latencyMs
            passphrase = p.destination.passphrase
            keyLength = p.destination.keyLength
        }
        video = videoSettings {
            p.video.cameraId?.let { cameraId = it }
            codec = when (p.video.codec) {
                VideoCodec.H264 -> PbVideoCodec.VIDEO_CODEC_H264
                VideoCodec.H265 -> PbVideoCodec.VIDEO_CODEC_H265
            }
            width = p.video.width
            height = p.video.height
            fps = p.video.fps
            bitrateKbps = p.video.bitrateKbps
            gopSeconds = p.video.gopSeconds
            rateControl = when (p.video.rateControl) {
                RateControl.VBR -> PbRateControl.RATE_CONTROL_VBR
                RateControl.CBR -> PbRateControl.RATE_CONTROL_CBR
            }
            zoomRatio = p.video.zoomRatio
            focus = when (p.video.focus) {
                FocusMode.CONTINUOUS -> PbFocusMode.FOCUS_MODE_CONTINUOUS
                FocusMode.INFINITY -> PbFocusMode.FOCUS_MODE_INFINITY
            }
            stabilization = p.video.stabilization
        }
        audio = audioSettings {
            enabled = p.audio.enabled
            bitrateKbps = p.audio.bitrateKbps
            sampleRate = p.audio.sampleRate
            channels = p.audio.channels
        }
        metadata = metadataSettings {
            rateHz = p.metadata.rateHz
            enabledTags += p.metadata.enabledTags.sorted()
            missionId = p.metadata.missionId
            platformDesignation = p.metadata.platformDesignation
            imageSourceSensor = p.metadata.imageSourceSensor
            callSign = p.metadata.callSign
            groundElevationM = p.metadata.groundElevationM
            lsVersion = p.metadata.lsVersion
            carriage = when (p.metadata.carriage) {
                KlvCarriage.SYNC -> PbKlvCarriage.KLV_CARRIAGE_SYNC
                KlvCarriage.ASYNC -> PbKlvCarriage.KLV_CARRIAGE_ASYNC
            }
        }
        advanced = advancedSettings {
            adaptiveBitrate = p.advanced.adaptiveBitrate
            minBitrateKbps = p.advanced.minBitrateKbps
            maxBitrateKbps = p.advanced.maxBitrateKbps
            ptsDelayMs = p.advanced.ptsDelayMs
            recordLocally = p.advanced.recordLocally
        }
    }

    fun fromProto(p: PbProfile): Profile {
        val d = p.destination
        val v = p.video
        val a = p.audio
        val m = p.metadata
        val adv = p.advanced
        return Profile(
            id = p.id,
            name = p.name,
            destination = Destination(
                mode = when (d.mode) {
                    PbSrtMode.SRT_MODE_LISTENER -> SrtMode.LISTENER
                    else -> SrtMode.CALLER
                },
                host = d.host,
                port = d.port,
                streamId = d.streamId,
                latencyMs = d.latencyMs,
                passphrase = d.passphrase,
                keyLength = d.keyLength,
            ),
            video = VideoSettings(
                cameraId = if (v.hasCameraId()) v.cameraId else null,
                codec = when (v.codec) {
                    PbVideoCodec.VIDEO_CODEC_H265 -> VideoCodec.H265
                    else -> VideoCodec.H264
                },
                width = v.width,
                height = v.height,
                fps = v.fps,
                bitrateKbps = v.bitrateKbps,
                gopSeconds = v.gopSeconds,
                rateControl = when (v.rateControl) {
                    PbRateControl.RATE_CONTROL_CBR -> RateControl.CBR
                    else -> RateControl.VBR
                },
                zoomRatio = v.zoomRatio,
                focus = when (v.focus) {
                    PbFocusMode.FOCUS_MODE_INFINITY -> FocusMode.INFINITY
                    else -> FocusMode.CONTINUOUS
                },
                stabilization = v.stabilization,
            ),
            audio = AudioSettings(
                enabled = a.enabled,
                bitrateKbps = a.bitrateKbps,
                sampleRate = a.sampleRate,
                channels = a.channels,
            ),
            metadata = MetadataSettings(
                rateHz = m.rateHz,
                enabledTags = m.enabledTagsList.toSet(),
                missionId = m.missionId,
                platformDesignation = m.platformDesignation,
                imageSourceSensor = m.imageSourceSensor,
                callSign = m.callSign,
                groundElevationM = m.groundElevationM,
                lsVersion = m.lsVersion,
                carriage = when (m.carriage) {
                    PbKlvCarriage.KLV_CARRIAGE_ASYNC -> KlvCarriage.ASYNC
                    else -> KlvCarriage.SYNC
                },
            ),
            advanced = AdvancedSettings(
                adaptiveBitrate = adv.adaptiveBitrate,
                minBitrateKbps = adv.minBitrateKbps,
                maxBitrateKbps = adv.maxBitrateKbps,
                ptsDelayMs = adv.ptsDelayMs,
                recordLocally = adv.recordLocally,
            ),
        )
    }
}
