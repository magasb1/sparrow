package app.sparrow.stream

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface LinkState {
    data object Idle : LinkState
    data object Starting : LinkState
    data object Connecting : LinkState
    data object Listening : LinkState
    data object Live : LinkState
    data class Reconnecting(val retryInMs: Long, val reason: String) : LinkState
    data class Failed(val reason: String) : LinkState
}

data class GnssStatus(
    val hasFix: Boolean = false,
    val accuracyM: Float? = null,
    val ageMs: Long? = null,
    val usingGnssTime: Boolean = false,
)

data class StreamStatus(
    val link: LinkState = LinkState.Idle,
    /** True from Start until the service has fully stopped. */
    val running: Boolean = false,
    val profileName: String = "",
    val targetKbps: Int = 0,
    val sendKbps: Double = 0.0,
    val rttMs: Double? = null,
    val lossPercent: Double? = null,
    val gnss: GnssStatus = GnssStatus(),
    val recordingPath: String? = null,
    val resolution: String = "",
    val zoomRange: ClosedFloatingPointRange<Double> = 1.0..1.0,
    /** elapsedRealtime (ms) when the link last went live; null while not live. */
    val liveSinceMs: Long? = null,
    /** Display rotation the stream was started in; the UI stays locked to it while running. */
    val displayRotationDeg: Int? = null,
    /** Detector figures while detection runs; null when it is off. */
    val detection: app.sparrow.cv.DetectionStats? = null,
    /** This camera cannot run the extra analysis stream, so detection is off for the session. */
    val detectionUnavailable: Boolean = false,
    /** Smart-encoding view of the scene; null until the first motion sample. */
    val scene: SceneStatus? = null,
)

data class SceneStatus(
    /** Moving fraction of the frame (MOG2). */
    val activity: Float,
    /** Content-adaptive share of the configured bitrate; null when that is off. */
    val bitrateFraction: Double?,
    val cameraMoving: Boolean,
    /** Region-of-interest encoding: null = off, false = encoder cannot, true = active. */
    val roi: Boolean?,
)

data class PreviewTarget(val surface: Surface, val width: Int, val height: Int)

/**
 * Process-wide bridge between the streaming service and the UI. State lives in the process, so
 * after the process dies the UI starts from Idle and never shows a stale "live" state (BG-6).
 */
object StreamController {
    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    private val _latestKlv = MutableStateFlow<ByteArray?>(null)
    val latestKlv: StateFlow<ByteArray?> = _latestKlv.asStateFlow()

    private val _preview = MutableStateFlow<PreviewTarget?>(null)
    val preview: StateFlow<PreviewTarget?> = _preview.asStateFlow()

    private val _zoom = MutableStateFlow<Double?>(null)
    val zoom: StateFlow<Double?> = _zoom.asStateFlow()

    /** Zoom ratio requested from the UI; applied live and reflected in the FOV tags (VID-4). */
    fun setZoom(ratio: Double) {
        _zoom.value = ratio
    }

    private val _tracks = MutableStateFlow<List<app.sparrow.core.cv.TrackedObject>>(emptyList())
    /** Confirmed tracks at the latest video frame, in normalised stream-image coordinates. */
    val tracks: StateFlow<List<app.sparrow.core.cv.TrackedObject>> = _tracks.asStateFlow()

    private val _detectionToggles = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    /** Detection switched on or off from the UI during a stream (the profile is saved too). */
    val detectionToggles: kotlinx.coroutines.flow.SharedFlow<Boolean> = _detectionToggles

    fun setDetectionEnabled(on: Boolean) {
        _detectionToggles.tryEmit(on)
    }

    internal fun publishTracks(tracks: List<app.sparrow.core.cv.TrackedObject>) {
        if (tracks.isNotEmpty() || _tracks.value.isNotEmpty()) _tracks.value = tracks
    }

    internal fun update(block: (StreamStatus) -> StreamStatus) = _status.update(block)

    internal fun publishKlv(packet: ByteArray) {
        _latestKlv.value = packet
    }

    internal fun reset() {
        _status.value = StreamStatus()
        _tracks.value = emptyList()
    }

    fun attachPreview(target: PreviewTarget) {
        _preview.value = target
    }

    fun detachPreview(surface: Surface) {
        if (_preview.value?.surface == surface) _preview.value = null
    }
}
