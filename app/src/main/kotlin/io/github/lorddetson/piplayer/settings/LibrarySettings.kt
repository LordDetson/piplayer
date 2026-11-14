package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/**
 * The Library settings in the `settings` file: which files the lists hide (docs/library.md §3.5) and the legacy encoding
 * (§5.7).
 */
class LibrarySettings(context: Context) {

    private val file = SettingsFiles.settings(context)

    val hiddenFiles: Flow<HiddenFiles> = file.data.map(HiddenFiles::from).distinctUntilChanged().onEach { latest = it }

    /** The choice now; blocks. */
    fun readHiddenFiles(): HiddenFiles = runBlocking { hiddenFiles.first() }

    suspend fun setShowSystemSounds(show: Boolean) = update { it[HiddenFiles.SHOW_SYSTEM_SOUNDS] = show }

    suspend fun setShowRecordings(show: Boolean) = update { it[HiddenFiles.SHOW_RECORDINGS] = show }

    suspend fun setMinLengthSeconds(seconds: Int) = update { it[HiddenFiles.MIN_LENGTH_SECONDS] = seconds }

    /** The manual choice of the legacy encoding; null for Automatic (docs/library.md §5.7). */
    val legacyEncoding: Flow<LegacyEncoding?> =
        file.data.map { LegacyEncodings.fromStored(it[LEGACY_ENCODING]) }.distinctUntilChanged()

    suspend fun readLegacyEncoding(): LegacyEncoding? = legacyEncoding.first()

    /** Stores [encoding], or Automatic for null; a change decodes the library again (docs/library.md §5.7). */
    suspend fun setLegacyEncoding(encoding: LegacyEncoding?) {
        SettingsFiles.edit(file) { preferences ->
            if (encoding == null) {
                preferences.remove(LEGACY_ENCODING)
            } else {
                preferences[LEGACY_ENCODING] = encoding.name
            }
        }
    }

    private suspend fun update(change: (MutablePreferences) -> Unit) {
        SettingsFiles.edit(file, change)?.let { latest = HiddenFiles.from(it) }
    }

    companion object {
        val LEGACY_ENCODING = stringPreferencesKey("legacy_encoding")

        /** The choice last read or written in this process; a kept list made with another one is out of date. */
        @Volatile
        var latest: HiddenFiles? = null
            private set
    }
}
