package io.github.lorddetson.piplayer.radio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A settings file in memory for JVM tests: writes one at a time, every reader sees the latest, as with DataStore. A file
 * on disk is no use here, because DataStore's JVM storage cannot replace a file on Windows, where local builds run.
 */
class MemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)
    private val writes = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = writes.withLock {
        transform(state.value).toPreferences().also { state.value = it }
    }
}
