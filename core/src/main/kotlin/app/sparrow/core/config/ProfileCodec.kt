package app.sparrow.core.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProfileBundle(val version: Int = 1, val profiles: List<Profile>)

/** JSON export/import of profiles (UI-3). Exports never contain passphrases. */
object ProfileCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun export(profiles: List<Profile>): String =
        json.encodeToString(ProfileBundle.serializer(), ProfileBundle(profiles = profiles.map { it.withoutSecrets() }))

    /** Parses an export. Imported profiles get fresh ids so they never overwrite existing ones. */
    fun import(text: String, freshIds: Boolean = true): List<Profile> {
        val bundle = json.decodeFromString(ProfileBundle.serializer(), text)
        return bundle.profiles.map { p ->
            val clean = p.withoutSecrets()
            if (freshIds) clean.copy(id = java.util.UUID.randomUUID().toString()) else clean
        }
    }

    /** Storage form (secrets stripped; the app keeps passphrases in the Keystore). */
    fun encodeStored(profiles: List<Profile>): String = export(profiles)

    fun decodeStored(text: String): List<Profile> = import(text, freshIds = false)

    fun Profile.withoutSecrets() = copy(destination = destination.copy(passphrase = ""))
}
