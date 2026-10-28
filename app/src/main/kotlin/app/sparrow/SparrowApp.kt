package app.sparrow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sparrow.data.ProfileRepository
import app.sparrow.log.AppLog
import app.sparrow.remote.BackendSession
import app.sparrow.ui.MainActivity

class SparrowApp : Application() {
    lateinit var profiles: ProfileRepository
        private set

    private val prefs by lazy { getSharedPreferences("app", Context.MODE_PRIVATE) }

    /** Set when the previous run ended without a clean stop; shown once in the UI (BG-6). */
    var interruptedSession = false
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        profiles = ProfileRepository(this)
        BackendSession.init(this)
        createChannels()
        // A fresh process with the "live" flag still set means we were killed while streaming.
        if (prefs.getBoolean(KEY_SESSION_ACTIVE, false)) {
            AppLog.w("Previous streaming session ended unexpectedly")
            interruptedSession = true
            markSessionActive(false)
            notifyInterrupted()
        }
    }

    fun acknowledgeInterruption() {
        interruptedSession = false
    }

    fun markSessionActive(active: Boolean) = prefs.edit().putBoolean(KEY_SESSION_ACTIVE, active).commit()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    fun notifyInterrupted() = alert(
        ALERT_INTERRUPTED,
        "Streaming stopped unexpectedly",
        "Android ended Sparrow while it was live. Open the app to start streaming again.",
    )

    fun notifyFailure(reason: String) = alert(ALERT_FAILED, "Streaming stopped", reason)

    /** After a reboot or process death: Android only lets the visible app re-enter standby. */
    fun notifyRearm() = alert(
        ALERT_REARM,
        "Sparrow standby is off",
        "The server cannot start this phone until Sparrow is opened. Tap to open it and re-arm standby.",
    )

    fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by canNotify()
    private fun alert(id: Int, title: String, text: String) {
        if (!canNotify()) return
        val open = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(id, n) }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STREAM, getString(R.string.channel_stream), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_STREAM = "stream"
        const val CHANNEL_ALERTS = "alerts"
        private const val KEY_SESSION_ACTIVE = "session_active"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val ALERT_INTERRUPTED = 10
        private const val ALERT_FAILED = 11
        private const val ALERT_REARM = 12

        fun get(context: Context) = context.applicationContext as SparrowApp
    }
}
