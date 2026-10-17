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

    internal fun update(block: (StreamStatus) -> StreamStatus) = _status.update(block)

    internal fun publishKlv(packet: ByteArray) {
        _latestKlv.value = packet
    }

    internal fun reset() {
        _status.value = StreamStatus()
    }

    fun attachPreview(target: PreviewTarget) {
        _preview.value = target
    }

    fun detachPreview(surface: Surface) {
        if (_preview.value?.surface == surface) _preview.value = null
    }
}
