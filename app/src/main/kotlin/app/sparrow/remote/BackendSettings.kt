package app.sparrow.remote

import android.content.Context
import app.sparrow.data.SecretStore

/** The phone's registration with a backend. */
data class Registration(
    val serverUrl: String,
    /** Server key pin from the enrollment link; null = system CAs. */
    val pin: String?,
    val deviceId: String,
    val alias: String,
    val token: String,
)

/** Registration and sync state, in preferences; the device token is in the Keystore-backed [SecretStore]. */
class BackendSettings(context: Context) {
    private val prefs = context.getSharedPreferences("backend", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    val registration: Registration?
        get() {
            val url = prefs.getString(KEY_URL, null) ?: return null
            val token = secrets.get(TOKEN_ID).ifEmpty { return null }
            return Registration(
                serverUrl = url,
                pin = prefs.getString(KEY_PIN, null),
                deviceId = prefs.getString(KEY_DEVICE, "") ?: "",
                alias = prefs.getString(KEY_ALIAS, "") ?: "",
                token = token,
            )
        }

    fun save(r: Registration) {
        secrets.put(TOKEN_ID, r.token)
        prefs.edit()
            .putString(KEY_URL, r.serverUrl)
            .putString(KEY_PIN, r.pin)
            .putString(KEY_DEVICE, r.deviceId)
            .putString(KEY_ALIAS, r.alias)
            .putLong(KEY_REVISION, 0)
            .commit()
    }

    fun clear() {
        secrets.remove(TOKEN_ID)
        prefs.edit().clear().commit()
    }

    /** Config revision from the backend that the phone has applied. */
    var appliedRevision: Long
        get() = prefs.getLong(KEY_REVISION, 0)
        set(value) {
            prefs.edit().putLong(KEY_REVISION, value).apply()
        }

    private companion object {
        /** Key in [SecretStore], which is otherwise keyed by profile id (UUIDs). */
        const val TOKEN_ID = "backend-device-token"
        const val KEY_URL = "server_url"
        const val KEY_PIN = "pin"
        const val KEY_DEVICE = "device_id"
        const val KEY_ALIAS = "alias"
        const val KEY_REVISION = "applied_revision"
    }
}
