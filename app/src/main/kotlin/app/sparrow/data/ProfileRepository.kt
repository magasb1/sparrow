package app.sparrow.data

import android.content.Context
import app.sparrow.core.config.Profile
import app.sparrow.core.config.ProfileCodec
import app.sparrow.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Named profiles (UI-3). Profiles are stored as JSON without secrets; passphrases live in the
 * [SecretStore] and are merged back in memory.
 */
class ProfileRepository(context: Context) {
    private val file = File(context.filesDir, "profiles.json")
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null) ?: _profiles.value.first().id)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    val active: Profile get() = _profiles.value.firstOrNull { it.id == _activeId.value } ?: _profiles.value.first()

    private fun load(): List<Profile> {
        val stored = runCatching { if (file.exists()) ProfileCodec.decodeStored(file.readText()) else emptyList() }
            .onFailure { AppLog.e("Could not read profiles", it) }
            .getOrDefault(emptyList())
        val list = stored.ifEmpty { listOf(Profile()) }
        return list.map { p ->
            val pass = secrets.get(p.id)
            AppLog.addSecret(pass)
            p.copy(destination = p.destination.copy(passphrase = pass))
        }
    }

    private fun persist(list: List<Profile>) {
        _profiles.value = list
        file.writeText(ProfileCodec.encodeStored(list))
    }

    @Synchronized
    fun save(profile: Profile) {
        secrets.put(profile.id, profile.destination.passphrase)
        AppLog.addSecret(profile.destination.passphrase)
        val list = _profiles.value
        persist(if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile)
    }

    @Synchronized
    fun delete(id: String) {
        val remaining = _profiles.value.filterNot { it.id == id }.ifEmpty { listOf(Profile()) }
        secrets.remove(id)
        persist(remaining)
        if (_activeId.value == id) select(remaining.first().id)
    }

    fun select(id: String) {
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /**
     * Makes [profile] the backend-managed profile, and selects it when [select] is set. Any other
     * managed profile is removed; local profiles are left alone.
     */
    @Synchronized
    fun applyManaged(profile: Profile, select: Boolean) {
        val managed = profile.copy(managed = true)
        secrets.put(managed.id, managed.destination.passphrase)
        AppLog.addSecret(managed.destination.passphrase)
        val list = _profiles.value
        list.filter { it.managed && it.id != managed.id }.forEach { secrets.remove(it.id) }
        val kept = list.filterNot { it.managed && it.id != managed.id }
        persist(if (kept.any { it.id == managed.id }) kept.map { if (it.id == managed.id) managed else it } else kept + managed)
        if (select) select(managed.id)
    }

    /** Removes managed profiles, e.g. after unregistering from the backend. */
    @Synchronized
    fun removeManaged() {
        val gone = _profiles.value.filter { it.managed }
        if (gone.isEmpty()) return
        gone.forEach { secrets.remove(it.id) }
        val remaining = _profiles.value.filterNot { it.managed }.ifEmpty { listOf(Profile()) }
        persist(remaining)
        if (gone.any { it.id == _activeId.value }) select(remaining.first().id)
    }

    fun exportJson(): String = ProfileCodec.export(_profiles.value)

    /** Imports profiles from JSON; returns how many were added. */
    @Synchronized
    fun importJson(json: String): Int {
        val imported = ProfileCodec.import(json)
        persist(_profiles.value + imported)
        return imported.size
    }

    private companion object {
        const val KEY_ACTIVE = "active"
    }
}
