package io.github.lorddetson.piplayer.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.identification.FingerprintStage
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatus
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.settings.Appearance
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.CustomTheme
import io.github.lorddetson.piplayer.settings.CustomThemesSettings
import io.github.lorddetson.piplayer.settings.CustomThemeWrite
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.settings.TagWritingOptions
import io.github.lorddetson.piplayer.settings.TagWritingSettings
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.ThemeApplyResult
import io.github.lorddetson.piplayer.settings.ThemeApplyUndo
import io.github.lorddetson.piplayer.settings.ThemePackageCodec
import io.github.lorddetson.piplayer.settings.ThemePackageRead
import io.github.lorddetson.piplayer.settings.ThemeUndoResult
import io.github.lorddetson.piplayer.tagging.write.TagCoverPreset
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** The Settings screen's state (docs/library.md §3.6). */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appearance = AppearanceSettings(application)
    private val customThemes = CustomThemesSettings(application)
    private val library = LibrarySettings(application)
    private val tagWriting = TagWritingSettings(application)

    internal val tagWritingOptions: StateFlow<TagWritingOptions> =
        tagWriting.options.stateIn(viewModelScope, SharingStarted.Eagerly, TagWritingOptions())

    internal fun setId3v1Policy(policy: Id3v1WritePolicy) {
        viewModelScope.launch { tagWriting.setId3v1(policy) }
    }

    internal fun setCoverPreset(preset: TagCoverPreset) {
        viewModelScope.launch { tagWriting.setCover(preset) }
    }

    val appearanceChoice: StateFlow<Appearance> =
        appearance.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, appearance.read())

    private val themeDocuments = application.contentResolver
    private val themePackage = MutableStateFlow<ThemePackageState>(ThemePackageState.Idle)
    private val lastThemeImport = MutableStateFlow<ThemeApplyUndo?>(null)
    internal val themePackageState: StateFlow<ThemePackageState> = themePackage
    internal val themePackageUndo: StateFlow<ThemeApplyUndo?> = lastThemeImport
    internal val savedThemes: Flow<List<CustomTheme>> = customThemes.state.map { it.themes }

    internal fun selectSavedTheme(id: String) = workWithThemePackage {
        if (customThemes.select(id) == CustomThemeWrite.STORED) ThemePackageState.Idle
        else ThemePackageState.Notice(R.string.theme_package_apply_failed)
    }

    internal fun openThemePackage(uri: Uri) = workWithThemePackage {
        val result = withContext(Dispatchers.IO) {
            try {
                themeDocuments.openInputStream(uri)?.use(ThemePackageCodec::decode) ?: ThemePackageRead.Unreadable
            } catch (_: IOException) {
                ThemePackageRead.Unreadable
            } catch (_: SecurityException) {
                ThemePackageRead.Unreadable
            }
        }
        when (result) {
            is ThemePackageRead.Loaded -> ThemePackageState.Preview(result.theme)
            ThemePackageRead.Invalid -> ThemePackageState.Notice(R.string.theme_package_invalid)
            ThemePackageRead.UnsupportedVersion -> ThemePackageState.Notice(R.string.theme_package_version)
            ThemePackageRead.TooLarge -> ThemePackageState.Notice(R.string.theme_package_too_large)
            ThemePackageRead.Unreadable -> ThemePackageState.Notice(R.string.theme_package_open_failed)
        }
    }

    internal fun exportThemePackage(uri: Uri) = workWithThemePackage {
        val export = appearance.exportThemePackage { getApplication<Application>().getString(it.label) }
        val written = withContext(Dispatchers.IO) {
            try {
                val bytes = ThemePackageCodec.encode(export)
                themeDocuments.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        if (written) ThemePackageState.Notice(R.string.theme_package_exported, error = false)
        else ThemePackageState.Notice(R.string.theme_package_export_failed)
    }

    internal fun cancelThemePackagePreview() {
        if (themePackage.value is ThemePackageState.Preview) themePackage.value = ThemePackageState.Idle
    }

    internal fun applyThemePackage() {
        val (theme) = themePackage.value as? ThemePackageState.Preview ?: return
        workWithThemePackage {
            when (val result = appearance.applyCustomTheme(CustomTheme(theme.name, theme.base, theme.palette), theme.layout, theme.panel)) {
                is ThemeApplyResult.Applied -> {
                    lastThemeImport.value = result.undo
                    ThemePackageState.Idle
                }
                ThemeApplyResult.InvalidTheme -> ThemePackageState.Notice(R.string.theme_package_invalid)
                ThemeApplyResult.WriteFailed -> ThemePackageState.Notice(R.string.theme_package_apply_failed)
            }
        }
    }

    internal fun undoThemePackage() {
        val undo = lastThemeImport.value ?: return
        workWithThemePackage {
            val result = appearance.undoCustomTheme(undo)
            if (result != ThemeUndoResult.WRITE_FAILED) lastThemeImport.value = null
            when (result) {
                ThemeUndoResult.RESTORED -> ThemePackageState.Idle
                ThemeUndoResult.STALE -> ThemePackageState.Notice(R.string.theme_package_changed)
                ThemeUndoResult.WRITE_FAILED -> ThemePackageState.Notice(R.string.theme_package_apply_failed)
            }
        }
    }

    private fun workWithThemePackage(work: suspend () -> ThemePackageState) {
        if (themePackage.value == ThemePackageState.Working) return
        themePackage.value = ThemePackageState.Working
        viewModelScope.launch { themePackage.value = work() }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { appearance.setTheme(mode) }
    }

    fun setNowPlayingLayout(layout: NowPlayingLayout) {
        viewModelScope.launch { appearance.setNowPlayingLayout(layout) }
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

    private val onlineGate = (application as PiPlayerApplication).onlineGate
    private val identification = onlineGate.settings

    /**
     * The choices: the switch shows the gate itself, which closes at once on off and stays closed after a failed off,
     * and shows it again once the answers have ended. The gate is read when any of them changes, so that the end of an
     * Enable never arrives with the gate as it was before.
     */
    private val choices: Flow<IdentificationPrivacyState> = combine(
        onlineGate.open,
        identification.needsDisclosure,
        identification.protectedFields,
        onlineGate.settled,
    ) { _, needsDisclosure, protectedFields, settled ->
        IdentificationPrivacyState(
            lookupsOn = onlineGate.isOpen(),
            needsDisclosure = needsDisclosure,
            protectedFields = protectedFields,
            answersSettled = settled,
        )
    }

    /** The providers' status, off the main thread: the first use of the services builds their HTTP client. */
    private val providers: Flow<List<ProviderState>> = flow {
        val services = IdentificationServices.of(application)
        val noKey = services.fingerprintStage is FingerprintStage.Unavailable
        emitAll(
            combine(
                services.status(ProviderId.ACOUSTID),
                services.status(ProviderId.MUSICBRAINZ),
                services.status(ProviderId.COVER_ART_ARCHIVE),
            ) { acoustId, musicBrainz, coverArtArchive ->
                listOf(if (noKey) ProviderState.NoKey else acoustId.shown, musicBrainz.shown, coverArtArchive.shown)
            },
        )
    }.flowOn(Dispatchers.Default)

    /** Settings, "Identification and privacy" (TI §9); the choices are read at once, as the other settings are. */
    val identificationPrivacy: StateFlow<IdentificationPrivacyState> =
        combine(choices, providers.onStart { emit(List(3) { ProviderState.Ready }) }) { state, (acoustId, musicBrainz, caa) ->
            state.copy(acoustId = acoustId, musicBrainz = musicBrainz, coverArtArchive = caa)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            runBlocking {
                onlineGate.awaitLoaded()
                choices.first()
            },
        )

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
