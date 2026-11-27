package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
sealed interface ThemeFontSelection {
    @Serializable
    @SerialName("system")
    data object System : ThemeFontSelection

    @Serializable
    @SerialName("built_in")
    data class BuiltIn(val id: String) : ThemeFontSelection

    @Serializable
    @SerialName("imported")
    data class Imported(val fileName: String, val displayName: String) : ThemeFontSelection
}

data class CustomTheme(
    val name: String,
    val base: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette(),
    val font: ThemeFontSelection = ThemeFontSelection.System,
    val id: String = UUID.randomUUID().toString(),
)

internal data class CustomThemesState(val themes: List<CustomTheme> = emptyList(), val selectedId: String? = null) {
    val selected: CustomTheme? get() = themes.firstOrNull { it.id == selectedId }
}

internal enum class CustomThemeWrite { STORED, INVALID_THEME, UNKNOWN_THEME, WRITE_FAILED }

/** Named themes and their active ID in the backed-up settings file. */
internal class CustomThemesSettings(private val file: DataStore<Preferences>) {
    constructor(context: Context) : this(SettingsFiles.settings(context))

    val state: Flow<CustomThemesState> = file.data.map(::stateOf).distinctUntilChanged()

    /** Stores one theme and, when requested, makes it active in the same commit. */
    suspend fun save(theme: CustomTheme, select: Boolean = false): CustomThemeWrite {
        if (!isValid(theme)) return CustomThemeWrite.INVALID_THEME
        return update { preferences ->
            storeTheme(preferences, theme, select)
            CustomThemeWrite.STORED
        }
    }

    /** A null [themeId] returns to the stored built-in theme. */
    suspend fun select(themeId: String?): CustomThemeWrite = update { preferences ->
        if (selectTheme(preferences, themeId)) CustomThemeWrite.STORED else CustomThemeWrite.UNKNOWN_THEME
    }

    private suspend fun update(change: (MutablePreferences) -> CustomThemeWrite): CustomThemeWrite = try {
        var result = CustomThemeWrite.WRITE_FAILED
        file.edit { result = change(it) }
        result
    } catch (_: IOException) {
        CustomThemeWrite.WRITE_FAILED
    }

    companion object {
        val THEMES = stringPreferencesKey("custom_themes")
        val SELECTED = stringPreferencesKey("selected_custom_theme_id")
        private val ID = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val BUILT_IN_FONTS = setOf("inter", "pt_serif", "ibm_plex_mono")
        private val FONT_EXTENSIONS = setOf("ttf", "otf")
        private val JSON = Json { ignoreUnknownKeys = true }

        fun stateOf(preferences: Preferences): CustomThemesState {
            val themes = storedThemes(preferences).mapNotNull { (id, entry) -> themeOf(id, entry) }
            val selected = preferences[SELECTED]?.takeIf { id -> themes.any { it.id == id } }
            return CustomThemesState(themes, selected)
        }

        fun selectedTheme(preferences: Preferences): CustomTheme? {
            val id = preferences[SELECTED] ?: return null
            return themeOf(id, storedThemes(preferences)[id])
        }

        fun isValid(theme: CustomTheme): Boolean = isValidId(theme.id) && theme.name.isNotBlank() &&
            theme.palette.hasValidColors && isValidFont(theme.font)

        fun isValidId(id: String): Boolean = ID.matches(id)

        /** Stores a validated theme inside the caller's appearance transaction. */
        fun storeTheme(
            preferences: MutablePreferences,
            theme: CustomTheme,
            select: Boolean,
            replaceExisting: Boolean = true,
        ): Boolean {
            val themes = storedThemes(preferences)
            if (!replaceExisting && theme.id in themes) return false

            // Keep entries this version could not interpret until the user edits that specific theme.
            storeThemeEntries(preferences, themes + (theme.id to themeEntry(themes[theme.id], theme)))
            if (select) preferences[SELECTED] = theme.id
            return true
        }

        fun selectTheme(preferences: MutablePreferences, id: String?): Boolean {
            if (id == null) {
                preferences.remove(SELECTED)
            } else {
                if (themeOf(id, storedThemes(preferences)[id]) == null) return false
                preferences[SELECTED] = id
            }
            return true
        }

        fun storedThemes(preferences: Preferences): Map<String, JsonElement> = try {
            (preferences[THEMES]?.let(JSON::parseToJsonElement) as? JsonObject).orEmpty()
        } catch (_: SerializationException) {
            emptyMap()
        }

        fun storeThemeEntries(preferences: MutablePreferences, entries: Map<String, JsonElement>) {
            if (entries.isEmpty()) preferences.remove(THEMES)
            else preferences[THEMES] = JsonObject(entries).toString()
        }

        fun themeEntry(previous: JsonElement?, theme: CustomTheme): JsonObject =
            JsonObject((previous as? JsonObject).orEmpty() + mapOf(
                "name" to JsonPrimitive(theme.name),
                "base" to JsonPrimitive(theme.base.stored),
                "palette" to JSON.encodeToJsonElement(theme.palette),
                "font" to JSON.encodeToJsonElement(theme.font),
            ))

        private fun themeOf(id: String, element: JsonElement?): CustomTheme? {
            val entry = element as? JsonObject ?: return null
            val name = entry.string("name")?.takeIf(String::isNotBlank) ?: return null
            if (!isValidId(id)) return null
            val palette = decode<ThemePalette>(entry["palette"])?.takeIf { it.hasValidColors } ?: ThemePalette()
            val font = decode<ThemeFontSelection>(entry["font"])?.takeIf(::isValidFont) ?: ThemeFontSelection.System
            return CustomTheme(name, ThemeMode.fromStored(entry.string("base")), palette, font, id)
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private inline fun <reified T> decode(element: JsonElement?): T? = try {
            element?.let { JSON.decodeFromJsonElement<T>(it) }
        } catch (_: SerializationException) {
            null
        }

        fun isValidFont(font: ThemeFontSelection): Boolean = when (font) {
            ThemeFontSelection.System -> true
            is ThemeFontSelection.BuiltIn -> font.id in BUILT_IN_FONTS
            is ThemeFontSelection.Imported -> font.displayName.isNotBlank() &&
                ID.matches(font.fileName.substringBeforeLast('.')) && font.fileName.substringAfterLast('.') in FONT_EXTENSIONS
        }
    }
}
