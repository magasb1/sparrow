package app.sparrow.core.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
        val bundle = json.decodeFromJsonElement(ProfileBundle.serializer(), migrate(json.parseToJsonElement(text)))
        return bundle.profiles.map { p ->
            val clean = p.withoutSecrets()
            if (freshIds) clean.copy(id = java.util.UUID.randomUUID().toString()) else clean
        }
    }

    /** Storage form (secrets stripped; the app keeps passphrases in the Keystore). */
    fun encodeStored(profiles: List<Profile>): String = export(profiles)

    fun decodeStored(text: String): List<Profile> = import(text, freshIds = false)

    fun Profile.withoutSecrets() = copy(destination = destination.copy(passphrase = ""))

    /**
     * Older files: video.stabilization (boolean, the camera's own EIS) becomes
     * video.stabilizationMode = CAMERA or OFF, so nobody silently gets the gyro crop.
     */
    private fun migrate(root: JsonElement): JsonElement {
        val obj = root as? JsonObject ?: return root
        val profiles = obj["profiles"] as? JsonArray ?: return root
        return JsonObject(obj + ("profiles" to JsonArray(profiles.map { p ->
            val po = p as? JsonObject ?: return@map p
            val video = po["video"] as? JsonObject ?: return@map p
            val old = (video["stabilization"] as? JsonPrimitive)?.booleanOrNull
            if (old == null || "stabilizationMode" in video) return@map p
            val mode = if (old) StabilizationMode.CAMERA else StabilizationMode.OFF
            JsonObject(po + ("video" to JsonObject(video - "stabilization" + ("stabilizationMode" to JsonPrimitive(mode.name)))))
        })))
    }
}
