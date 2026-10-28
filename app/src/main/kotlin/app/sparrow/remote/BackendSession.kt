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
import app.sparrow.stream.StreamingService
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
            StreamingService.disarm(app)
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

    /** Runs a backend command and reports the outcome; the server waits up to 10 s for it. */
    private suspend fun answer(client: DeviceClient, command: Command) {
        val (ok, text) = try {
            execute(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Backend command ${command.kindCase} failed", e)
            false to (e.message ?: "failed")
        }
        AppLog.i("Backend command ${command.kindCase}: ${if (ok) "ok" else "refused"}, $text")
        try {
            client.commandDone(
                commandDoneRequest {
                    id = command.id
                    this.ok = ok
                    message = text
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Could not answer backend command", e)
        }
    }

    private suspend fun execute(command: Command): Pair<Boolean, String> {
        val status = StreamController.status.value
        return when (command.kindCase) {
            Command.KindCase.START -> {
                if (status.running) return true to "Already streaming (${describe(status.link)})"
                useManagedProfile()?.let { return false to it }
                when {
                    StreamingService.remote(StreamingService.ACTION_START) -> Unit
                    // Not armed, but the app is on screen, so Android lets it start the service.
                    uiVisible() -> StreamingService.start(app)
                    else -> return false to "Not in standby. Open Sparrow on the phone to arm it."
                }
                awaitStarted()
            }
            Command.KindCase.STOP -> {
                if (!status.running) return false to "Not streaming"
                if (!StreamingService.remote(StreamingService.ACTION_STOP)) StreamingService.stop(app)
                val stopped = withTimeoutOrNull(COMMAND_WAIT_MS) { StreamController.status.first { !it.running } } != null
                stopped to (if (stopped) "Stopped" else "Still stopping")
            }
            Command.KindCase.RESTART -> {
                if (!status.running) return false to "Not streaming"
                useManagedProfile()?.let { return false to it }
                if (!StreamingService.remote(StreamingService.ACTION_RESTART)) {
                    app.startService(Intent(app, StreamingService::class.java).setAction(StreamingService.ACTION_RESTART))
                }
                awaitStarted()
            }
            Command.KindCase.ZOOM -> {
                val range = if (status.running) status.zoomRange else cameraZoomRange()
                val z = command.zoom.ratio.coerceIn(range.start, range.endInclusive)
                StreamController.setZoom(z)
                true to "Zoom %.1f×%s".format(z, if (status.running) "" else ", from the next start")
            }
            else -> false to "Not supported by this app version"
        }
    }

    /** Selects the backend's profile, so a remote start sends what the server configured. Returns why it cannot start. */
    private fun useManagedProfile(): String? {
        val managed = app.profiles.profiles.value.firstOrNull { it.managed }
        managed?.let { app.profiles.select(it.id) }
        return app.profiles.active.validate().firstOrNull()?.let { "Profile '${app.profiles.active.name}' is invalid: $it" }
    }

    /** Waits until the stream is past Starting; a failure there is a failed command. */
    private suspend fun awaitStarted(): Pair<Boolean, String> {
        val s = withTimeoutOrNull(COMMAND_WAIT_MS) {
            StreamController.status.first { it.link is LinkState.Failed || it.running && it.link != LinkState.Starting }
        } ?: return true to "Starting"
        return (s.link !is LinkState.Failed) to describe(s.link)
    }

    private fun describe(link: LinkState): String = when (link) {
        LinkState.Idle -> "idle"
        LinkState.Starting -> "starting"
        LinkState.Connecting -> "connecting"
        LinkState.Listening -> "waiting for a receiver"
        LinkState.Live -> "live"
        is LinkState.Reconnecting -> "reconnecting: ${link.reason}"
        is LinkState.Failed -> "failed: ${link.reason}"
    }

    private fun cameraZoomRange(): ClosedFloatingPointRange<Double> {
        val cameras = runCatching { CameraInfo.all(app) }.getOrDefault(emptyList())
        val camera = cameras.firstOrNull { it.id == app.profiles.active.video.cameraId } ?: cameras.firstOrNull()
        return camera?.zoomRange ?: 1.0..1.0
    }

    @Synchronized
    private fun uiVisible() = "ui" in holders

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
                combine(StreamController.status, StreamController.armed) { s, armed -> streamState(s, armed) }
                    .distinctUntilChanged().drop(1).first()
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

    private fun streamState(s: StreamStatus, armed: Boolean = StreamController.armed.value): StreamState = when (s.link) {
        LinkState.Idle -> if (armed && !s.running) StreamState.STREAM_STATE_STANDBY else StreamState.STREAM_STATE_IDLE
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
    /** Below the server's 10 s wait, so the answer arrives in time. */
    private const val COMMAND_WAIT_MS = 7_000L
}
