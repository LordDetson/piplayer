package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

data class Appearance(val theme: ThemeMode, val nowPlayingLayout: NowPlayingLayout)

/** The Appearance settings in the `settings` file (docs/library.md §3.6). */
class AppearanceSettings(context: Context) {

    private val file = SettingsFiles.settings(context)

    val appearance: Flow<Appearance> = file.data.map { values ->
        Appearance(ThemeMode.fromStored(values[THEME]), NowPlayingLayout.fromStored(values[NOW_PLAYING_LAYOUT]))
    }.distinctUntilChanged()

    /** Reads the saved appearance before the activity draws its first frame. */
    fun read(): Appearance = runBlocking { appearance.first() }

    fun readTheme(): ThemeMode = read().theme

    suspend fun setTheme(mode: ThemeMode) {
        SettingsFiles.edit(file) { it[THEME] = mode.stored }
    }

    suspend fun setNowPlayingLayout(layout: NowPlayingLayout) {
        SettingsFiles.edit(file) { it[NOW_PLAYING_LAYOUT] = layout.stored }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val NOW_PLAYING_LAYOUT = stringPreferencesKey("now_playing_layout")
    }
}
