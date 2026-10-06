package app.heat.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** The one JSON configuration used for config, patches and messages. */
val ProtoJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    classDiscriminator = "t"
}

/**
 * RFC 7396-style merge patches over [CameraConfig].
 *
 * A setting change is "the JSON that differs", so adding a new setting needs
 * no new message, no new plumbing and no new place to forget it: it is a
 * field on the data class, and it syncs.
 */
object ConfigPatch {

    /** The minimal patch that turns [from] into [to]. Empty when they are equal. */
    fun diff(from: CameraConfig, to: CameraConfig): JsonObject =
        diffObjects(
            ProtoJson.encodeToJsonElement(from).jsonObject,
            ProtoJson.encodeToJsonElement(to).jsonObject
        )

    /**
     * Applies [patch] to [base]. Returns null if the result is not a valid
     * config (a malformed patch is rejected whole, never half-applied).
     * The result is always [CameraConfig.sanitized].
     */
    fun apply(base: CameraConfig, patch: JsonObject): CameraConfig? = runCatching {
        val merged = merge(ProtoJson.encodeToJsonElement(base).jsonObject, patch)
        ProtoJson.decodeFromJsonElement(CameraConfig.serializer(), merged).sanitized()
    }.getOrNull()

    /**
     * Dotted paths of every leaf the patch touches, e.g. `detect.sensitivity`.
     * "Apply to all cameras" uses this to strip the per-camera ones.
     */
    fun paths(patch: JsonObject, prefix: String = ""): Set<String> = buildSet {
        for ((key, value) in patch) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            // `zone` is a leaf for this purpose even though it is an object.
            if (value is JsonObject && path !in ATOMIC) addAll(paths(value, path)) else add(path)
        }
    }

    /** [patch] with every per-camera path removed. */
    fun withoutPerCamera(patch: JsonObject, prefix: String = ""): JsonObject = buildJsonObject {
        for ((key, value) in patch) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (path in CameraConfig.PER_CAMERA_PATHS) continue
            if (value is JsonObject && path !in ATOMIC) {
                val inner = withoutPerCamera(value, path)
                if (inner.isNotEmpty()) put(key, inner)
            } else {
                put(key, value)
            }
        }
    }

    /** Objects that are replaced as a unit, not merged field by field. */
    private val ATOMIC = setOf("detect.zone")

    private fun diffObjects(a: JsonObject, b: JsonObject, prefix: String = ""): JsonObject = buildJsonObject {
        for ((key, newValue) in b) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val oldValue = a[key]
            if (oldValue == newValue) continue
            if (oldValue is JsonObject && newValue is JsonObject && path !in ATOMIC) {
                val inner = diffObjects(oldValue, newValue, path)
                if (inner.isNotEmpty()) put(key, inner)
            } else {
                put(key, newValue)
            }
        }
    }

    private fun merge(base: JsonObject, patch: JsonObject, prefix: String = ""): JsonObject = buildJsonObject {
        for ((k, v) in base) if (k !in patch) put(k, v)
        for ((k, v) in patch) {
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            val current = base[k]
            when {
                v is JsonNull -> put(k, JsonNull)
                v is JsonObject && current is JsonObject && path !in ATOMIC -> put(k, merge(current, v, path))
                else -> put(k, v as JsonElement)
            }
        }
    }
}
