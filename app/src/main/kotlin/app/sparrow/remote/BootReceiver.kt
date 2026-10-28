package app.sparrow.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sparrow.SparrowApp

/**
 * After a reboot a registered phone is not in standby, and Android does not let a camera service
 * start from boot. One tap on the notification opens the app, which re-arms.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (BackendSession.state.value.registered) SparrowApp.get(context).notifyRearm()
    }
}
