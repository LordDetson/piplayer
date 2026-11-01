package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** The Appearance settings in the `settings` file (docs/library.md §3.6). */
class AppearanceSettings(context: Context) {

    private val file = SettingsFiles.settings(context)

    val theme: Flow<ThemeMode> = file.data.map { ThemeMode.fromStored(it[THEME]) }.distinctUntilChanged()

    /** The theme now; blocks, so that the first frame is drawn in it. */
    fun readTheme(): ThemeMode = runBlocking { theme.first() }

    suspend fun setTheme(mode: ThemeMode) {
        file.edit { it[THEME] = mode.stored }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
    }
}
