package io.github.lorddetson.piplayer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    /** The manual choice of the legacy encoding, null for Automatic (docs/library.md §5.7). */
    val legacyEncoding: StateFlow<LegacyEncoding?> =
        library.legacyEncoding.stateIn(viewModelScope, SharingStarted.Eagerly, runBlocking { library.readLegacyEncoding() })

    /** The code pages this device can decode. */
    val availableLegacyEncodings: List<LegacyEncoding> = LegacyEncodings.available()

    /** Stores the choice; the library follows it without a read of any file (docs/library.md §5.7). */
    fun setLegacyEncoding(encoding: LegacyEncoding?) {
        viewModelScope.launch { library.setLegacyEncoding(encoding) }
    }

    /** Looks for new and changed files now (docs/library.md §5.6). */
    fun refreshLibrary() = Library.refresh(getApplication())

    /** Has the tag pass read every file again; lists keep what they show meanwhile (docs/library.md §5.6). */
    fun readAllTagsAgain() {
        viewModelScope.launch { Library.readAllTagsAgain(getApplication()) }
    }

    /** Builds the library database anew, keeping user data (docs/library.md §8.5); it outlives the screen. */
    fun rebuildLibrary() {
        val app = getApplication<Application>()
        rebuilds.launch { Library.rebuild(app) }
    }

    private companion object {
        val rebuilds = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
