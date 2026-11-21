package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

internal data class PlayerPanelState<P>(val themeId: String, val panel: P)

/** Stores a layout separately for each built-in or saved theme. */
internal class PlayerPanelStore<P>(
    private val prefix: String,
    private val default: P,
    private val decode: (String) -> P?,
    private val encode: (P) -> String,
) {
    fun forContext(context: Context) = Settings(SettingsFiles.settings(context))

    fun stateOf(preferences: Preferences): PlayerPanelState<P> {
        val theme = AppearanceSettings.panelThemeId(preferences)
        val panel = try {
            preferences[key(theme)]?.let(decode)
        } catch (_: SerializationException) {
            null
        }
        return PlayerPanelState(theme, panel ?: default)
    }

    fun storePanel(preferences: MutablePreferences, theme: String, panel: P) {
        preferences[key(theme)] = encode(panel)
    }

    private fun key(theme: String) = stringPreferencesKey(prefix + theme)

    inner class Settings internal constructor(private val file: DataStore<Preferences>) {
        val state = file.data.map(::stateOf).distinctUntilChanged()

        suspend fun edit(themeId: String, change: (P) -> P): Boolean {
            var applied = false
            val written = SettingsFiles.edit(file) { preferences ->
                val current = stateOf(preferences)
                if (current.themeId == themeId) {
                    val updated = change(current.panel)
                    if (updated != current.panel) storePanel(preferences, themeId, updated)
                    applied = true
                }
            }
            return applied && written != null
        }
    }
}
