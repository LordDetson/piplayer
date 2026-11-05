package io.github.lorddetson.piplayer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Settings screen's state (docs/library.md §3.6). */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appearance = AppearanceSettings(application)
    private val library = LibrarySettings(application)

    val theme: StateFlow<ThemeMode> = appearance.theme.stateIn(viewModelScope, SharingStarted.Eagerly, appearance.readTheme())

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { appearance.setTheme(mode) }
    }

    val hiddenFiles: StateFlow<HiddenFiles> =
        library.hiddenFiles.stateIn(viewModelScope, SharingStarted.Eagerly, library.readHiddenFiles())

    fun setShowSystemSounds(show: Boolean) {
        viewModelScope.launch { library.setShowSystemSounds(show) }
    }

    fun setShowRecordings(show: Boolean) {
        viewModelScope.launch { library.setShowRecordings(show) }
    }

    fun setMinLengthSeconds(seconds: Int) {
        viewModelScope.launch { library.setMinLengthSeconds(seconds) }
    }

    /** Looks for new and changed files now (docs/library.md §5.6). */
    fun refreshLibrary() = Library.refresh(getApplication())

    /** Has the tag pass read every file again; lists keep what they show meanwhile (docs/library.md §5.6). */
    fun readAllTagsAgain() {
        viewModelScope.launch { Library.readAllTagsAgain(getApplication()) }
    }
}
