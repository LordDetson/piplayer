package io.github.lorddetson.piplayer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.identification.FingerprintStage
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatus
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.IdentificationSettings
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    private val identification = IdentificationSettings(application)
    private val onlineGate = (application as PiPlayerApplication).onlineGate
    private val services = IdentificationServices.of(application)

    private val privacy: Flow<IdentificationPrivacyState> = combine(
        identification.consent,
        identification.protectedFields,
        services.status(ProviderId.ACOUSTID),
        services.status(ProviderId.MUSICBRAINZ),
        services.status(ProviderId.COVER_ART_ARCHIVE),
    ) { consent, protectedFields, acoustId, musicBrainz, coverArtArchive ->
        val version = identification.disclosureVersion
        IdentificationPrivacyState(
            lookupsOn = consent.allows(version),
            needsDisclosure = consent.disclosureVersion < version,
            protectedFields = protectedFields,
            acoustId = if (services.fingerprintStage is FingerprintStage.Unavailable) ProviderState.NoKey else acoustId.shown,
            musicBrainz = musicBrainz.shown,
            coverArtArchive = coverArtArchive.shown,
        )
    }

    /** Settings, "Identification and privacy" (TI §9). */
    val identificationPrivacy: StateFlow<IdentificationPrivacyState> =
        privacy.stateIn(viewModelScope, SharingStarted.Eagerly, runBlocking { privacy.first() })

    /**
     * The answers to the disclosure and the setting, through the app's gate, which closes at once on Not now and off.
     * They outlive the screen, so that leaving it at once cannot cut a write short.
     */
    fun enableOnlineLookups() {
        consentWrites.launch { onlineGate.enable() }
    }

    fun notNow() {
        consentWrites.launch { onlineGate.notNow() }
    }

    fun turnOffOnlineLookups() {
        consentWrites.launch { onlineGate.turnOff() }
    }

    fun setProtected(field: ProtectedField, protected: Boolean) {
        viewModelScope.launch { identification.setProtected(field, protected) }
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

        /** Immediate: a turn-off closes the gate within the tap that asked for it. */
        val consentWrites = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val ProviderStatus.shown: ProviderState
            get() = when (this) {
                ProviderStatus.Active -> ProviderState.Ready
                is ProviderStatus.Stopped -> ProviderState.Stopped(reason)
            }
    }
}
