package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/** The Library settings in the `settings` file: which files the lists hide (docs/library.md §3.5). */
class LibrarySettings(context: Context) {

    private val file = SettingsFiles.settings(context)

    val hiddenFiles: Flow<HiddenFiles> = file.data.map(HiddenFiles::from).distinctUntilChanged().onEach { latest = it }

    /** The choice now; blocks. */
    fun readHiddenFiles(): HiddenFiles = runBlocking { hiddenFiles.first() }

    suspend fun setShowSystemSounds(show: Boolean) = update { it[HiddenFiles.SHOW_SYSTEM_SOUNDS] = show }

    suspend fun setShowRecordings(show: Boolean) = update { it[HiddenFiles.SHOW_RECORDINGS] = show }

    suspend fun setMinLengthSeconds(seconds: Int) = update { it[HiddenFiles.MIN_LENGTH_SECONDS] = seconds }

    private suspend fun update(change: (MutablePreferences) -> Unit) {
        SettingsFiles.edit(file, change)?.let { latest = HiddenFiles.from(it) }
    }

    companion object {
        /** The choice last read or written in this process; a kept list made with another one is out of date. */
        @Volatile
        var latest: HiddenFiles? = null
            private set
    }
}
