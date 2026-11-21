package io.github.lorddetson.piplayer.ui.player

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal fun withStockPlayerTheme(context: Context, panelPrefix: String, test: () -> Unit) {
    val file = SettingsFiles.settings(context)
    val theme = stringPreferencesKey("theme_mode")
    val selected = stringPreferencesKey("selected_custom_theme_id")
    val previous = runBlocking { file.data.first() }
    runBlocking {
        SettingsFiles.edit(file) { preferences ->
            preferences.remove(theme)
            preferences.remove(selected)
            preferences.asMap().keys.filter { it.name.startsWith(panelPrefix) }.forEach { preferences.remove(it) }
        }
    }
    try {
        test()
    } finally {
        runBlocking {
            SettingsFiles.edit(file) { preferences ->
                previous[theme]?.let { preferences[theme] = it } ?: preferences.remove(theme)
                previous[selected]?.let { preferences[selected] = it } ?: preferences.remove(selected)
                preferences.asMap().keys.filter { it.name.startsWith(panelPrefix) }.forEach { preferences.remove(it) }
                for ((key, value) in previous.asMap()) {
                    if (key.name.startsWith(panelPrefix) && value is String) preferences[stringPreferencesKey(key.name)] = value
                }
            }
        }
    }
}
