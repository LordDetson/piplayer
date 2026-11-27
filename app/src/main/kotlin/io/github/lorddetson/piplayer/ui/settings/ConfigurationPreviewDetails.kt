package io.github.lorddetson.piplayer.ui.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal data class ConfigurationLeafChange(val path: String, val before: JsonElement?, val after: JsonElement?)

internal fun configurationLeafChanges(before: JsonElement?, after: JsonElement?): Sequence<ConfigurationLeafChange> = sequence {
    appendConfigurationChanges("", before, after)
}

private suspend fun SequenceScope<ConfigurationLeafChange>.appendConfigurationChanges(
    path: String,
    before: JsonElement?,
    after: JsonElement?,
) {
    if (before == after) return
    val oldObject = before as? JsonObject
    val newObject = after as? JsonObject
    if ((oldObject != null || newObject != null) &&
        (oldObject != null || before == null || before == JsonNull) &&
        (newObject != null || after == null || after == JsonNull)
    ) {
        val keys = (oldObject.orEmpty().keys + newObject.orEmpty().keys).sorted()
        if (keys.isNotEmpty()) {
            for (key in keys) {
                val child = if (path.isEmpty()) key else "$path.$key"
                appendConfigurationChanges(child, oldObject?.get(key), newObject?.get(key))
            }
            return
        }
    }
    val oldArray = before as? JsonArray
    val newArray = after as? JsonArray
    if ((oldArray != null || newArray != null) &&
        (oldArray != null || before == null || before == JsonNull) &&
        (newArray != null || after == null || after == JsonNull)
    ) {
        val size = maxOf(oldArray.orEmpty().size, newArray.orEmpty().size)
        if (size > 0) {
            for (index in 0 until size) {
                appendConfigurationChanges("$path[${index + 1}]", oldArray?.getOrNull(index), newArray?.getOrNull(index))
            }
            return
        }
    }
    yield(ConfigurationLeafChange(path, before, after))
}

internal fun String.previewSnippet(firstDifference: Int = 0): String {
    val limit = 160
    if (length <= limit) return this
    val start = (firstDifference - limit / 2).coerceIn(0, length - limit)
    val end = start + limit
    val safeStart = if (start > 0 && this[start].isLowSurrogate()) start - 1 else start
    val safeEnd = if (end < length && this[end].isLowSurrogate()) end + 1 else end
    return buildString {
        if (safeStart > 0) append('…')
        append(this@previewSnippet, safeStart, safeEnd)
        if (safeEnd < length) append('…')
    }
}
