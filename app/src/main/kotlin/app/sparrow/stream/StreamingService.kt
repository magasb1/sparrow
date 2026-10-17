package app.sparrow.stream

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.sparrow.R
import app.sparrow.SparrowApp
import app.sparrow.core.config.Profile
import app.sparrow.log.AppLog
import app.sparrow.remote.BackendSession
import app.sparrow.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the streaming pipeline (BG-1..4). It keeps running when the UI is
 * gone or the screen is off, holding a partial wake lock and a low-latency Wi-Fi lock while live.
 */
class StreamingService : LifecycleService() {
    private var pipeline: StreamPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiHighPerfLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var notificationJob: Job? = null
    private var previewJob: Job? = null
    private var zoomJob: Job? = null
    private var detectionJob: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private var restarts = ArrayDeque<Long>()
    private var stopping = false
    private var streamRotationDeg = 0
    /**
     * The profile the session started with. Automatic restarts reuse it, so a profile selected
     * or pushed by the backend mid-stream never redirects a running stream.
     */
    private var sessionProfile: Profile? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startStreaming()
            ACTION_STOP -> stopStreaming(userRequested = true)
            null -> {
                // Restarted by the system after the process was killed. Android does not allow
                // reopening the camera from here, so tell the user instead of pretending (BG-6).
                AppLog.w("Service restarted after process death")
                SparrowApp.get(this).notifyInterrupted()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ServiceCompat drops service types the running API level does not know.
    @SuppressLint("InlinedApi")
    private fun startStreaming() {
        if (pipeline != null) return
        stopping = false
        // Fixed for the whole session, including automatic restarts with the screen off.
        streamRotationDeg = displayRotationDeg()
        val app = SparrowApp.get(this)
        val profile = app.profiles.active
        sessionProfile = profile
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            (if (profile.audio.enabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        StreamController.update { it.copy(running = true, link = LinkState.Starting, profileName = profile.name) }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } catch (e: Exception) {
            AppLog.e("Could not enter foreground", e)
            StreamController.update { it.copy(running = false, link = LinkState.Failed("Could not start: ${e.message}")) }
            stopSelf()
            return
        }
        app.markSessionActive(true)
        // Keep reporting to the backend while streaming, also with the screen off.
        BackendSession.acquire("service")
        acquireLocks()
        registerNetworkCallback()
        launchPipeline()
        notificationJob = lifecycleScope.launch {
            while (true) {
                delay(2000)
                updateNotification()
            }
        }
        previewJob = lifecycleScope.launch {
            StreamController.preview.collect { pipeline?.setPreview(it) }
        }
        zoomJob = lifecycleScope.launch {
            StreamController.zoom.collect { z -> z?.let { pipeline?.setZoom(it) } }
        }
        detectionJob = lifecycleScope.launch {
            StreamController.detectionToggles.collect { on -> pipeline?.setDetectionEnabled(on) }
        }
    }

    private fun launchPipeline() {
        val profile = sessionProfile ?: SparrowApp.get(this).profiles.active
        var self: StreamPipeline? = null
        try {
            // Failures are only honoured from the current pipeline, so one fault reported by
            // several components (or by an old pipeline) costs a single restart.
            val p = StreamPipeline(this, profile, streamRotationDeg) { reason ->
                main.post { if (self != null && pipeline === self) onPipelineFailure(reason) }
            }
            self = p
            pipeline = p
            p.start()
            p.setPreview(StreamController.preview.value)
            StreamController.zoom.value?.let { z -> p.setZoom(z) }
            StreamController.update { st -> st.copy(zoomRange = p.zoomRange) }
        } catch (e: Exception) {
            AppLog.e("Pipeline start failed", e)
            // onPipelineFailure stops the half-started pipeline, if one was created.
            onPipelineFailure(e.message ?: "start failed")
        }
    }

    /** Restarts the pipeline after camera/encoder failures, up to 5 times a minute. */
    private fun onPipelineFailure(reason: String) {
        if (stopping) return
        pipeline?.stop()
        pipeline = null
        val now = System.currentTimeMillis()
        restarts.addLast(now)
        while (restarts.isNotEmpty() && now - restarts.first() > 60_000) restarts.removeFirst()
        if (restarts.size > 5) {
            AppLog.e("Giving up after repeated failures: $reason")
            StreamController.update { it.copy(link = LinkState.Failed(reason)) }
            SparrowApp.get(this).notifyFailure(reason)
            stopStreaming(userRequested = false)
            return
        }
        StreamController.update { it.copy(link = LinkState.Reconnecting(3000, reason)) }
        main.postDelayed({ if (!stopping && pipeline == null) launchPipeline() }, 3000)
    }

    /** Current rotation of the built-in display, in degrees. */
    private fun displayRotationDeg(): Int {
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        return (display?.rotation ?: android.view.Surface.ROTATION_0) * 90
    }

    private fun stopStreaming(userRequested: Boolean) {
        stopping = true
        notificationJob?.cancel()
        previewJob?.cancel()
        zoomJob?.cancel()
        detectionJob?.cancel()
        main.removeCallbacksAndMessages(null)
        pipeline?.stop()
        pipeline = null
        sessionProfile = null
        unregisterNetworkCallback()
        releaseLocks()
        BackendSession.release("service")
        SparrowApp.get(this).markSessionActive(false)
        val failed = StreamController.status.value.link as? LinkState.Failed
        StreamController.reset()
        if (!userRequested && failed != null) StreamController.update { it.copy(link = failed) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) stopStreaming(userRequested = false)
        super.onDestroy()
    }

    // Held for the whole stream, which may last many hours; released in stopStreaming().
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sparrow:stream").apply { acquire() }
        val wifi = getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "sparrow:stream").apply { acquire() }
        // The low-latency lock only applies with the screen on; below API 34 the high-performance
        // lock keeps Wi-Fi power save off while the screen is off.
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            wifiHighPerfLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sparrow:stream-hp").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiHighPerfLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        wifiHighPerfLock = null
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        var current: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (current != null && current != network) {
                    AppLog.i("Default network changed to $network")
                    pipeline?.onNetworkChanged()
                }
                current = network
            }
        }
        cm.registerDefaultNetworkCallback(callback, main)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun buildNotification(): Notification {
        val s = StreamController.status.value
        val state = when (val l = s.link) {
            LinkState.Live -> "Live"
            LinkState.Connecting -> "Connecting"
            LinkState.Listening -> "Waiting for receiver"
            is LinkState.Reconnecting -> "Reconnecting"
            is LinkState.Failed -> "Failed: ${l.reason}"
            else -> "Starting"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = if (s.link == LinkState.Live) "%.0f kbit/s · %s".format(s.sendKbps, s.profileName) else s.profileName
        return NotificationCompat.Builder(this, SparrowApp.CHANNEL_STREAM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sparrow · $state")
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    @SuppressLint("MissingPermission") // guarded by canNotify()
    private fun updateNotification() {
        if (!SparrowApp.get(this).canNotify()) return
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        const val ACTION_START = "app.sparrow.START"
        const val ACTION_STOP = "app.sparrow.STOP"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, StreamingService::class.java).setAction(ACTION_START),
        )

        fun stop(context: Context) = context.startService(
            Intent(context, StreamingService::class.java).setAction(ACTION_STOP),
        )
    }
}
