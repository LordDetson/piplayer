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
import android.util.AtomicFile
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.identity.TrackRef
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
class PlaybackStateStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    /** `queue.bin`, the structure of the queue (docs/playback.md §4.8); null keeps none. */
    private val queueFile: File? = null,
    /** The track reference of a library song and the library ID, which a snapshot gets when it is written. */
    private val tracks: TrackSource = TrackSource.NONE,
) {

    /** What the store adds to a snapshot of a library song (docs/playback.md §4.8). */
    interface TrackSource {
        suspend fun refOf(fileId: Long): TrackRef?

        suspend fun libraryId(): String?

        companion object {
            val NONE = object : TrackSource {
                override suspend fun refOf(fileId: Long): TrackRef? = null

                override suspend fun libraryId(): String? = null
            }
        }
    }

    private sealed interface Write {
        class Save(val snapshot: PlaybackSnapshot, val done: CompletableDeferred<Unit>? = null) : Write
        class SaveQueue(val file: QueueFile, val done: CompletableDeferred<Unit>? = null) : Write
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

    /** Saves the structure of the queue in the background, before any snapshot enqueued after it. */
    fun enqueue(file: QueueFile) {
        writes.trySend(Write.SaveQueue(file))
    }

    /** Saves [file] and waits until it is on disk. */
    suspend fun write(file: QueueFile) {
        val done = CompletableDeferred<Unit>()
        writes.send(Write.SaveQueue(file, done))
        done.await()
    }

    /** The saved structure of the queue after every write enqueued so far, or null when there is none or it is damaged. */
    suspend fun readQueue(): QueueFile? {
        flush()
        val file = queueFile ?: return null
        return withContext(Dispatchers.IO) {
            try {
                if (file.exists()) AtomicFile(file).openRead().use(QueueFileCodec::read) else null
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the saved queue", e)
                null
            }
        }
    }

    fun readQueueBlocking(): QueueFile? = runBlocking { readQueue() }

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

    /**
     * The saved state as DataStore reports it, starting with the one on disk. A test collects it to see a state that a
     * later write replaces soon, such as the queue while a short song plays. DataStore conflates its updates, so a slow
     * collector may skip a state that the next write replaced within moments.
     */
    @VisibleForTesting
    val saved: Flow<PlaybackSnapshot?>
        get() = dataStore.data.map { it.toSnapshot() }

    /** Forgets the saved state in the background, after every write enqueued so far and before any enqueued after it. */
    fun enqueueClear() {
        writes.trySend(Write.Clear(CompletableDeferred()))
    }

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
                val snapshot = withTrack(write.snapshot)
                dataStore.edit { it.putSnapshot(snapshot) }
                write.done?.complete(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the playback state", e)
                write.done?.completeExceptionally(e)
            }
            is Write.SaveQueue -> try {
                saveQueue(write.file)
                write.done?.complete(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the queue", e)
                write.done?.completeExceptionally(e)
            }
            is Write.Clear -> try {
                dataStore.edit { it.clear() }
                queueFile?.let { AtomicFile(it).delete() }
                write.done.complete(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear the playback state", e)
                write.done.completeExceptionally(e)
            }
            is Write.Flush -> write.done.complete(Unit)
        }
    }

    /** A library song gets its track reference and the library ID, unless it has them (docs/playback.md §4.8). */
    private suspend fun withTrack(snapshot: PlaybackSnapshot): PlaybackSnapshot {
        if (snapshot.songId < 0 || snapshot.current != null) return snapshot
        return try {
            val now = tracks.libraryId()
            // File IDs read under another library ID (a rebuild that gave new IDs) would name other files here.
            if (snapshot.libraryId != null && snapshot.libraryId != now) return snapshot
            snapshot.copy(current = tracks.refOf(snapshot.songId), libraryId = snapshot.libraryId ?: now)
        } catch (e: Exception) {
            Log.w(TAG, "Could not look up the current song", e)
            snapshot
        }
    }

    private suspend fun saveQueue(file: QueueFile) {
        val target = queueFile ?: return
        val withLibrary = if (file.libraryId == null) file.copy(libraryId = tracks.libraryId()) else file
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            QueueFileCodec.write(withLibrary, output)
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
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

        /** Under the no-backup files, so that no backup takes it (docs/playback.md §4.8). */
        const val QUEUE_FILE_NAME = "queue.bin"

        @Volatile
        private var instance: PlaybackStateStore? = null

        /** The store of this process; its file is shared by the service and the screens. */
        fun get(context: Context): PlaybackStateStore = instance ?: synchronized(this) {
            instance ?: run {
                // DataStore allows one process per file (docs/playback.md §1.5).
                ProcessRole.current(context).requireMain("The playback state store")
                val app = context.applicationContext
                val tracks = object : TrackSource {
                    override suspend fun refOf(fileId: Long) = Library.trackRefs(app).refOf(fileId)

                    override suspend fun libraryId() = Library.trackRefs(app).libraryId()
                }
                PlaybackStateStore(app.playbackStateDataStore, File(app.noBackupFilesDir, QUEUE_FILE_NAME), tracks)
                    .also { instance = it }
            }
        }
    }
}
