package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val STATE_FILE_NAME = "playback_state"

/**
 * The one playback state file (docs/playback.md §4.1). Its name is also in the backup exclusion rules. A file that
 * cannot be read is replaced by an empty one rather than stopping playback.
 */
private val Context.playbackStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = STATE_FILE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Saves and reads the compact playback state. Writes go through one queue, so a later snapshot never lands before an
 * earlier one; [read] first waits for everything already enqueued, which is what the UI needs after a save on pause.
 */
class PlaybackStateStore internal constructor(private val dataStore: DataStore<Preferences>) {

    private sealed interface Write {
        class Save(val snapshot: PlaybackSnapshot, val done: CompletableDeferred<Unit>? = null) : Write
        class Clear(val done: CompletableDeferred<Unit>) : Write
        class Flush(val done: CompletableDeferred<Unit>) : Write
    }

    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (write in writes) perform(write)
        }
    }

    /** Saves [snapshot] in the background; the latest state is never overtaken by an older one. */
    fun enqueue(snapshot: PlaybackSnapshot) {
        writes.trySend(Write.Save(snapshot))
    }

    /** Saves [snapshot] and waits until it is on disk; for tests and for a save a caller must see finished. */
    suspend fun write(snapshot: PlaybackSnapshot) {
        val done = CompletableDeferred<Unit>()
        writes.send(Write.Save(snapshot, done))
        done.await()
    }

    /** The saved state after every write enqueued so far, or null. */
    suspend fun read(): PlaybackSnapshot? {
        flush()
        return try {
            dataStore.data.first().toSnapshot()
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the saved playback state", e)
            null
        }
    }

    fun readBlocking(): PlaybackSnapshot? = runBlocking { read() }

    /** Forgets the saved state after every write enqueued so far. */
    suspend fun clear() {
        val done = CompletableDeferred<Unit>()
        writes.send(Write.Clear(done))
        done.await()
    }

    private suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        writes.send(Write.Flush(done))
        done.await()
    }

    private suspend fun perform(write: Write) {
        when (write) {
            is Write.Save -> try {
                dataStore.edit { it.putSnapshot(write.snapshot) }
                write.done?.complete(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the playback state", e)
                write.done?.completeExceptionally(e)
            }
            is Write.Clear -> try {
                dataStore.edit { it.clear() }
                write.done.complete(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear the playback state", e)
                write.done.completeExceptionally(e)
            }
            is Write.Flush -> write.done.complete(Unit)
        }
    }

    /** Stops the writer of a test's own store; the process store lives as long as the app. */
    @VisibleForTesting
    fun close() {
        writes.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "PlaybackStateStore"

        @Volatile
        private var instance: PlaybackStateStore? = null

        /** The store of this process; its file is shared by the service and the screens. */
        fun get(context: Context): PlaybackStateStore = instance ?: synchronized(this) {
            instance ?: PlaybackStateStore(context.applicationContext.playbackStateDataStore).also { instance = it }
        }
    }
}
