package app.sparrow.remote

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.sparrow.SparrowApp
import app.sparrow.core.srt.Backoff
import app.sparrow.log.AppLog
import app.sparrow.protocol.ConnectException
import app.sparrow.protocol.DeviceClient
import app.sparrow.protocol.EnrollLink
import app.sparrow.protocol.ProfileMapper
import app.sparrow.protocol.v1.Command
import app.sparrow.protocol.v1.ReportRequest
import app.sparrow.protocol.v1.StreamState
import app.sparrow.protocol.v1.SubscribeResponse
import app.sparrow.protocol.v1.commandDoneRequest
import app.sparrow.protocol.v1.deviceInfo
import app.sparrow.protocol.v1.position
import app.sparrow.protocol.v1.registerRequest
import app.sparrow.protocol.v1.reportRequest
import app.sparrow.stream.CameraInfo
import app.sparrow.stream.LinkState
import app.sparrow.stream.StreamController
import app.sparrow.stream.StreamStatus
import com.google.protobuf.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The phone's link to the backend. While at least one holder (the visible app, the streaming
 * service) needs it and the phone is registered, it keeps the Subscribe stream open, sends a
 * report every 5 s while streaming (30 s otherwise), and applies config the backend pushes.
 * Nothing is sent before the user registers (NFR-5).
 *
 * Every registration change bumps [generation]; work started for an older generation (a
 * cancelled run whose network call already returned) checks it and writes nothing.
 */
object BackendSession {
    enum class Link { NOT_REGISTERED, OFFLINE, CONNECTING, CONNECTED }

    data class State(
        val registered: Boolean = false,
        val serverUrl: String = "",
        val alias: String = "",
        val insecure: Boolean = false,
        val link: Link = Link.NOT_REGISTERED,
        /** Why the link is down, or why the phone is no longer registered. */
        val message: String? = null,
        /** The last config from the server the phone refused. */
        val configError: String? = null,
        /** Wall-clock time of the last accepted report. */
        val lastReportAtMs: Long? = null,
        val appliedRevision: Long = 0,
        val registering: Boolean = false,
        val registerError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var app: SparrowApp
    private lateinit var settings: BackendSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val holders = mutableSetOf<String>()
    private var job: Job? = null
    /** In memory, so acquire/release on the main thread never touch the Keystore. */
    private var registration: Registration? = null
    private var generation = 0L
    private val configLock = Mutex()
    /** A revision the phone refused; not fetched again until the backend changes it. */
    @Volatile private var rejectedRevision = -1L

    fun init(app: SparrowApp) {
        this.app = app
        settings = BackendSettings(app)
        registration = settings.registration?.also { AppLog.addSecret(it.token) }
        publishRegistration()
    }

    /** [holder] needs the link (e.g. "ui", "service"). */
    @Synchronized
    fun acquire(holder: String) {
        holders += holder
        reconcile()
    }

    @Synchronized
    fun release(holder: String) {
        holders -= holder
        reconcile()
    }

    @Synchronized
    private fun reconcile() {
        val reg = registration
        if (holders.isNotEmpty() && reg != null) {
            if (job?.isActive != true) {
                val gen = generation
                job = scope.launch { run(reg, gen) }
            }
        } else {
            job?.cancel()
            job = null
            if (reg != null) _state.update { it.copy(link = Link.OFFLINE) }
        }
    }

    /** Must hold the lock. */
    private fun publishRegistration() {
        val reg = registration
        val previous = _state.value
        _state.value = if (reg == null) State(message = previous.message) else State(
            registered = true,
            serverUrl = reg.serverUrl,
            alias = reg.alias,
            insecure = reg.serverUrl.startsWith("http://", ignoreCase = true),
            link = Link.OFFLINE,
            appliedRevision = settings.appliedRevision,
        )
    }

    /**
     * Registers with the server in [link]. Runs in the session's own scope, so leaving or
     * rotating the screen cannot cancel it after the server has used up the token. Progress and
     * errors are in [state].
     */
    fun register(link: EnrollLink) {
        synchronized(this) {
            if (_state.value.registering || registration != null) return
            _state.update { it.copy(registering = true, registerError = null, message = null) }
        }
        scope.launch {
            try {
                val response = DeviceClient(link.serverUrl, link.pin, null).register(
                    registerRequest {
                        enrollmentToken = link.token
                        device = describeDevice()
                    },
                )
                AppLog.addSecret(response.deviceToken)
                val reg = Registration(link.serverUrl, link.pin, response.deviceId, response.alias, response.deviceToken)
                synchronized(this@BackendSession) {
                    generation++
                    job?.cancel()
                    job = null
                    settings.save(reg)
                    registration = reg
                    rejectedRevision = -1
                    publishRegistration()
                    reconcile()
                }
                AppLog.i("Registered with ${link.serverUrl} as ${response.alias}")
            } catch (e: Exception) {
                AppLog.w("Registration with ${link.serverUrl} failed", e)
                _state.update { it.copy(registering = false, registerError = describeRegisterError(e, link)) }
            }
        }
    }

    /** Forgets the registration at once, then tells the server (best effort). */
    fun unregister() {
        val reg = synchronized(this) { registration } ?: return
        forget(generation = null, logLine = "Unregistered from ${reg.serverUrl}")
        scope.launch {
            runCatching { withTimeout(5_000) { DeviceClient(reg.serverUrl, reg.pin, reg.token).unregister() } }
                .onFailure { AppLog.w("Could not tell the server about unregistering", it) }
        }
    }

    /**
     * Drops the registration and managed profile. With [generation] set, only if that
     * registration is still the current one.
     */
    private fun forget(generation: Long?, logLine: String, message: String? = null) {
        synchronized(this) {
            if (generation != null && generation != this.generation) return
            this.generation++
            job?.cancel()
            job = null
            registration = null
            settings.clear()
            app.profiles.removeManaged()
            _state.update { it.copy(message = message) }
            publishRegistration()
        }
        AppLog.i(logLine)
    }

    private suspend fun run(reg: Registration, gen: Long) = coroutineScope {
        val client = DeviceClient(reg.serverUrl, reg.pin, reg.token)
        launch { subscribeLoop(client, gen) }
        launch { reportLoop(client, gen) }
    }

    /** The server answered "unauthenticated": the phone was removed there. */
    private fun revoked(gen: Long) = forget(
        gen,
        "The backend no longer knows this phone; registration removed",
        "This phone was removed on the server. Register again to reconnect.",
    )

    /** Updates state only while [gen] is the current registration. */
    private fun updateIf(gen: Long, block: (State) -> State) = synchronized(this) {
        if (gen == generation) _state.update(block)
    }

    private suspend fun subscribeLoop(client: DeviceClient, gen: Long) {
        val backoff = Backoff(initialMs = 1_000, maxMs = 30_000)
        while (true) {
            updateIf(gen) { if (it.link != Link.CONNECTED) it.copy(link = Link.CONNECTING) else it }
            try {
                client.subscribe().collect { event ->
                    if (_state.value.link != Link.CONNECTED) {
                        updateIf(gen) { it.copy(link = Link.CONNECTED, message = null) }
                        backoff.reset()
                        AppLog.i("Backend link up")
                    }
                    handle(client, gen, event)
                }
                offline(gen, "The server closed the connection")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectException) {
                if (e.code == "unauthenticated") return revoked(gen)
                offline(gen, e.serverMessage)
            } catch (e: Exception) {
                offline(gen, e.message ?: e.javaClass.simpleName)
            }
            delay(backoff.nextDelayMs())
        }
    }

    private fun offline(gen: Long, reason: String) {
        if (_state.value.link == Link.CONNECTED) AppLog.w("Backend link down: $reason")
        updateIf(gen) { it.copy(link = Link.OFFLINE, message = reason) }
    }

    private suspend fun handle(client: DeviceClient, gen: Long, event: SubscribeResponse) {
        when (event.eventCase) {
            SubscribeResponse.EventCase.CONFIG_CHANGED -> syncConfig(client, gen, event.configChanged)
            SubscribeResponse.EventCase.COMMAND -> answer(client, event.command)
            else -> Unit // heartbeat
        }
    }

    /** Commands arrive with milestone 2; until then the phone says it cannot run them. */
    private suspend fun answer(client: DeviceClient, command: Command) {
        AppLog.i("Backend command ${command.kindCase} not supported by this app version")
        try {
            client.commandDone(
                commandDoneRequest {
                    id = command.id
                    ok = false
                    message = "Not supported by this app version"
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Could not answer backend command", e)
        }
    }

    private suspend fun reportLoop(client: DeviceClient, gen: Long) {
        while (true) {
            val status = StreamController.status.value
            try {
                val response = client.report(buildReport(status))
                updateIf(gen) { it.copy(lastReportAtMs = System.currentTimeMillis()) }
                syncConfig(client, gen, response.latestConfigRevision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectException) {
                if (e.code == "unauthenticated") return revoked(gen)
            } catch (_: Exception) {
                // The subscribe loop shows the link state.
            }
            val interval = if (status.running) LIVE_REPORT_MS else IDLE_REPORT_MS
            // Report early when the stream starts, stops or changes state.
            withTimeoutOrNull(interval) {
                StreamController.status.map { streamState(it) }.distinctUntilChanged().drop(1).first()
            }
        }
    }

    /** Fetches and applies the backend's config when [hint] differs from what is applied. */
    private suspend fun syncConfig(client: DeviceClient, gen: Long, hint: Long) = configLock.withLock {
        if (hint == settings.appliedRevision || hint == rejectedRevision) return@withLock
        val config = try {
            client.getConfig().config
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectException) {
            if (e.code == "unauthenticated") revoked(gen) else AppLog.w("Could not fetch backend config", e)
            return@withLock
        } catch (e: Exception) {
            AppLog.w("Could not fetch backend config", e)
            return@withLock
        }
        synchronized(this) {
            if (gen != generation || config.revision == settings.appliedRevision) return@withLock
            if (config.revision == 0L || !config.hasProfile()) {
                app.profiles.removeManaged()
            } else {
                // A fixed id: a server-chosen one could collide with a local profile or a secret key.
                val profile = ProfileMapper.fromProto(config.profile).copy(id = MANAGED_ID)
                val problems = profile.validate()
                if (problems.isNotEmpty()) {
                    rejectedRevision = config.revision
                    AppLog.w("Rejected backend config revision ${config.revision}: ${problems.joinToString("; ")}")
                    _state.update { it.copy(configError = "Revision ${config.revision} was rejected: ${problems.first()}") }
                    return@withLock
                }
                // Selecting a profile mid-stream would make the UI show a profile that is not
                // the one being sent; it takes effect from the next start instead.
                app.profiles.applyManaged(profile, select = !StreamController.status.value.running)
            }
            settings.appliedRevision = config.revision
            _state.update { it.copy(appliedRevision = config.revision, configError = null) }
        }
        AppLog.i("Applied backend config revision ${config.revision}")
    }

    private fun buildReport(status: StreamStatus): ReportRequest {
        val now = System.currentTimeMillis()
        val profile = app.profiles.active
        val live = status.link == LinkState.Live
        val pos = status.position?.let { p ->
            position {
                lat = p.lat
                lon = p.lon
                p.altMsl?.let { altMsl = it }
                p.accuracyM?.let { accuracyM = it }
                p.headingDeg?.let { headingDeg = it }
                p.elevationDeg?.let { elevationDeg = it }
            }
        }
        val battery = batteryStatus()
        return reportRequest {
            time = Timestamp.newBuilder().setSeconds(now / 1000).setNanos(((now % 1000) * 1_000_000).toInt()).build()
            state = streamState(status)
            reason = when (val l = status.link) {
                is LinkState.Reconnecting -> l.reason
                is LinkState.Failed -> l.reason
                else -> ""
            }
            configRevision = settings.appliedRevision
            profileName = status.profileName.ifEmpty { profile.name }
            if (live) {
                sendKbps = status.sendKbps
                targetKbps = status.targetKbps
                status.rttMs?.let { rttMs = it }
                status.lossPercent?.let { lossPercent = it }
            }
            pos?.let { position = it }
            zoom = StreamController.zoom.value ?: profile.video.zoomRatio
            resolution = status.resolution
            battery.first?.let { batteryPercent = it }
            charging = battery.second
            thermalStatus = app.getSystemService(PowerManager::class.java).currentThermalStatus
        }
    }

    private fun streamState(s: StreamStatus): StreamState = when (s.link) {
        LinkState.Idle -> StreamState.STREAM_STATE_IDLE
        LinkState.Starting -> StreamState.STREAM_STATE_STARTING
        LinkState.Connecting -> StreamState.STREAM_STATE_CONNECTING
        LinkState.Listening -> StreamState.STREAM_STATE_LISTENING
        LinkState.Live -> StreamState.STREAM_STATE_LIVE
        is LinkState.Reconnecting -> StreamState.STREAM_STATE_RECONNECTING
        is LinkState.Failed -> StreamState.STREAM_STATE_FAILED
    }

    /** (percent, charging) from the sticky battery broadcast. */
    private fun batteryStatus(): Pair<Int?, Boolean> {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return (if (level >= 0 && scale > 0) level * 100 / scale else null) to plugged
    }

    private fun describeDevice() = deviceInfo {
        manufacturer = Build.MANUFACTURER
        model = Build.MODEL
        androidSdk = Build.VERSION.SDK_INT
        appVersion = appVersion(app)
        val cameras = runCatching { CameraInfo.all(app) }.getOrDefault(emptyList())
        val camera = cameras.firstOrNull { it.id == app.profiles.active.video.cameraId } ?: cameras.firstOrNull()
        zoomMin = camera?.zoomRange?.start ?: 1.0
        zoomMax = camera?.zoomRange?.endInclusive ?: 1.0
    }

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

    private fun describeRegisterError(e: Throwable, link: EnrollLink): String {
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            e is ConnectException && e.code == "permission_denied" ->
                "The server did not accept this link: it was already used or has expired. Make a new one on the server."
            e is ConnectException -> "The server refused: ${e.serverMessage}"
            chain.any { it.message?.contains("does not match the enrolled pin") == true } ->
                "The server's key does not match the link. Check that the link comes from this server."
            chain.any { it is SSLException } && link.pin == null ->
                "The server's certificate is not trusted, and the link has no key pin to check it with."
            chain.any { it is SSLException } -> "Secure connection to the server failed: ${e.message}"
            chain.any { it is UnknownHostException || it is java.net.ConnectException || it is SocketTimeoutException } ->
                "Cannot reach the server: ${e.message}"
            e is IOException -> "Connection failed: ${e.message}"
            else -> "Registration failed: ${e.message}"
        }
    }

    private const val LIVE_REPORT_MS = 5_000L
    private const val IDLE_REPORT_MS = 30_000L
    private const val MANAGED_ID = "backend-managed"
}
