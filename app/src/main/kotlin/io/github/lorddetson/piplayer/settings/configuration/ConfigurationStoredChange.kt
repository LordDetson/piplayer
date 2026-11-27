package io.github.lorddetson.piplayer.settings.configuration

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.CustomTheme
import io.github.lorddetson.piplayer.settings.CustomThemesSettings
import io.github.lorddetson.piplayer.settings.HiddenFiles
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A checked preference edit. JSON-backed theme maps use UUIDs and canvas surfaces as independent entries. */
internal class ConfigurationStoredChange(
    private val key: Preferences.Key<*>,
    private val before: Any?,
    private val after: Any?,
    private val entries: List<JsonEntryChange>?,
) {
    fun matches(preferences: Preferences, undo: Boolean): Boolean {
        val current = preferences.asMap()[key]
        val expected = if (undo) after else before
        if (current == expected) return true
        val diff = entries ?: return false
        val currentJson = rawObject(current) ?: return false
        return diff.all { entry -> currentJson.valueAt(entry.path) == if (undo) entry.after else entry.before }
    }

    fun write(preferences: MutablePreferences, undo: Boolean) {
        val current = preferences.asMap()[key]
        val expected = if (undo) after else before
        val target = if (undo) before else after
        val value = if (current == expected || entries == null) {
            target
        } else {
            val currentJson = checkNotNull(rawObject(current))
            val updated = entries.fold(currentJson) { obj, entry ->
                obj.withValueAt(entry.path, if (undo) entry.before else entry.after)
            }
            updated.takeIf { it.isNotEmpty() }?.toString()
        }
        // The value came from the same preference key or a string-valued JSON key.
        @Suppress("UNCHECKED_CAST")
        val typed = key as Preferences.Key<Any>
        if (value == null) preferences.remove(typed) else preferences[typed] = value
    }

    val changesHiddenFiles: Boolean
        get() = key in hiddenKeys

    companion object {
        private val hiddenKeys = setOf(
            HiddenFiles.SHOW_SYSTEM_SOUNDS,
            HiddenFiles.SHOW_RECORDINGS,
            HiddenFiles.MIN_LENGTH_SECONDS,
        )

        fun between(before: Preferences, after: Preferences): List<ConfigurationStoredChange> {
            val old = before.asMap()
            val new = after.asMap()
            return (old.keys + new.keys).mapNotNull { key ->
                val from = old[key]
                val to = new[key]
                if (from == to) null else ConfigurationStoredChange(key, from, to, entries(key, from, to))
            }
        }

        private fun entries(key: Preferences.Key<*>, before: Any?, after: Any?): List<JsonEntryChange>? {
            val canvas = key.name.startsWith("ui_canvas_")
            if (key != CustomThemesSettings.THEMES && !canvas) return null
            val from = rawObject(before) ?: return null
            val to = rawObject(after) ?: return null
            return jsonDiff(from, to) { path -> canvas && path == listOf("panels") }.takeIf { it.isNotEmpty() }
        }
    }
}

/** References a theme's existence when selecting it, and checks newer uses before removing an imported theme. */
internal class ConfigurationThemeDependencies private constructor(
    private val previousRawSelection: String?,
    private val beforeSelection: String?,
    private val afterSelection: String?,
    private val addedLayouts: Map<String, Map<Preferences.Key<String>, String?>>,
    private val legacyStates: Map<String, ThemeValidationState>,
) {
    fun matches(current: Preferences, candidate: Preferences, undo: Boolean): Boolean {
        if (undo) {
            val selected = candidate[CustomThemesSettings.SELECTED]
            for ((id, expectedLayout) in addedLayouts) {
                if (selected == id && previousRawSelection != id) return false
                if (expectedLayout.any { (key, expected) -> current[key] != expected }) return false
            }
        }
        val selection = if (undo) beforeSelection else afterSelection
        return selection == null || CustomThemesSettings.selectedTheme(candidate)?.id == selection
    }

    fun restoresLegacyState(candidate: Preferences, themeId: String): Boolean {
        val original = legacyStates[themeId] ?: return false
        return original == ThemeValidationState.read(candidate, themeId)
    }

    companion object {
        fun between(
            before: Preferences,
            after: Preferences,
            rememberLegacyState: Boolean = false,
        ): ConfigurationThemeDependencies {
            val old = CustomThemesSettings.stateOf(before)
            val new = CustomThemesSettings.stateOf(after)
            val priorIds = old.themes.mapTo(mutableSetOf(), CustomTheme::id)
            val addedLayouts = new.themes.asSequence().map(CustomTheme::id).filterNot(priorIds::contains).associateWith { id ->
                ConfigurationThemes.layoutKeys(id).associateWith { key -> after[key] }
            }
            val selectionChanged = old.selectedId != new.selectedId
            // Check the Undo direction: reactivating a previous theme can require validation on its own.
            val legacyStates = if (rememberLegacyState) {
                ConfigurationThemes.affectedThemeIds(after, before)
                    .filterNot { ConfigurationThemes.isValid(before, it) }
                    .associateWith { ThemeValidationState.read(before, it) }
            } else emptyMap()
            return ConfigurationThemeDependencies(
                before[CustomThemesSettings.SELECTED],
                old.selectedId.takeIf { selectionChanged },
                new.selectedId.takeIf { selectionChanged },
                addedLayouts,
                legacyStates,
            )
        }
    }

    private data class ThemeValidationState(
        val theme: JsonElement?,
        val layouts: Map<Preferences.Key<String>, String?>,
        val active: Boolean,
        val fallback: Boolean,
    ) {
        companion object {
            fun read(preferences: Preferences, themeId: String) = ThemeValidationState(
                CustomThemesSettings.storedThemes(preferences)[themeId],
                ConfigurationThemes.layoutKeys(themeId).associateWith { key -> preferences[key] },
                AppearanceSettings.panelThemeId(preferences) == themeId,
                AppearanceSettings.builtInTheme(preferences).stored == themeId,
            )
        }
    }
}

internal data class JsonEntryChange(val path: List<String>, val before: JsonElement?, val after: JsonElement?)

internal fun jsonDiff(
    before: JsonObject,
    after: JsonObject,
    descend: (List<String>) -> Boolean,
): List<JsonEntryChange> = buildList {
    fun differences(branch: List<String>, from: JsonObject, to: JsonObject) {
        for (key in (from.keys + to.keys).sorted()) {
            val old = from[key]
            val new = to[key]
            if (old == new) continue
            val path = branch + key
            val objectsOrAbsent = (old == null || old is JsonObject) && (new == null || new is JsonObject)
            if (descend(path) && objectsOrAbsent) {
                differences(path, old ?: JsonObject(emptyMap()), new ?: JsonObject(emptyMap()))
            } else {
                add(JsonEntryChange(path, old, new))
            }
        }
    }
    differences(emptyList(), before, after)
}

private fun rawObject(value: Any?): JsonObject? = when (value) {
    null -> JsonObject(emptyMap())
    is String -> try {
        Json.parseToJsonElement(value) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
    else -> null
}

private fun JsonObject.valueAt(path: List<String>): JsonElement? {
    val value = get(path.first())
    return if (path.size == 1) value else (value as? JsonObject)?.valueAt(path.drop(1))
}

private fun JsonObject.withValueAt(path: List<String>, value: JsonElement?): JsonObject {
    val key = path.first()
    val replacement = if (path.size == 1) {
        value
    } else {
        val branch = this[key] as? JsonObject ?: JsonObject(emptyMap())
        branch.withValueAt(path.drop(1), value).takeIf { it.isNotEmpty() }
    }
    return JsonObject(if (replacement == null) this - key else this + (key to replacement))
}
