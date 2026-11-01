package io.github.lorddetson.piplayer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Settings screen's state (docs/library.md §3.6). */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appearance = AppearanceSettings(application)

    val theme: StateFlow<ThemeMode> = appearance.theme.stateIn(viewModelScope, SharingStarted.Eagerly, appearance.readTheme())

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { appearance.setTheme(mode) }
    }
}
