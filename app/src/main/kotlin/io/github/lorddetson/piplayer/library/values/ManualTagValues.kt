package io.github.lorddetson.piplayer.library.values

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Local-only tag properties outside the identification fields. Empty lists explicitly clear a property. */
internal object ManualTagValues {
    fun encode(properties: Map<String, List<String>>): String = Json.encodeToString(properties)

    fun decode(value: String?): Map<String, List<String>> {
        if (value == null) return emptyMap()
        return try {
            Json.decodeFromString(value)
        } catch (_: SerializationException) {
            emptyMap()
        }
    }
}
