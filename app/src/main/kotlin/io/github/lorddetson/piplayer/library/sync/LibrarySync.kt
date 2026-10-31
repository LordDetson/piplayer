package io.github.lorddetson.piplayer.library.sync

import android.util.Log
import androidx.room3.TransactionScope
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.LibraryDao
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.VolumeSyncStateEntity
import io.github.lorddetson.piplayer.library.db.JsonRows
import io.github.lorddetson.piplayer.library.db.replaceAll
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether the library can be listed yet, and how many syncs have changed its files in this process (docs/library.md
 * §1.3).
 */
data class LibraryStatus(val ready: Boolean = false, val revision: Long = 0)

/** Keeps the library database up to date with the media index (docs/library.md §1.3). */
class LibrarySync(
    private val database: LibraryDatabase,
    private val index: MediaIndex,
    private val mayRead: () -> Boolean,
) {

    private val dao: LibraryDao = database.library()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    private val mutableStatus = MutableStateFlow(LibraryStatus())
    val status: StateFlow<LibraryStatus> = mutableStatus.asStateFlow()

    /**
     * Conflated: requests that arrive during a sync collapse into one more sync after it. [pending] says whether a
     * request still waits for a sync that starts after it; it and [idle] change only under [lock], so the background
     * loop and [request] cannot interleave between deciding and setting them.
     */
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var pending = false
    private val idle = MutableStateFlow(true)
    private var soon: Job? = null

    init {
        scope.launch {
            for (request in requests) {
                // A request that came before the previous sync started was served by it; its element is stale.
                val run = synchronized(lock) { pending.also { pending = false } }
                if (run) sync()
                synchronized(lock) { if (!pending) idle.value = true }
            }
        }
    }

    /** Runs one sync now, after any that is running; whether it changed files. A failure is logged, not thrown. */
    suspend fun sync(): Boolean = running.withLock {
        if (!mayRead()) return@withLock false
        try {
            syncOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The library sync failed", e)
            false
        } finally {
            markReady()
        }
    }

    /** Asks for a sync in the background; requests that arrive during a sync start one more after it. */
    fun request() {
        synchronized(lock) {
            pending = true
            idle.value = false
        }
        requests.trySend(Unit)
    }

    /** Asks for a sync a second after the last call of a burst: for change reports of the media index. */
    @Synchronized
    fun requestSoon() {
        soon?.cancel()
        soon = scope.launch {
            delay(CHANGE_DELAY_MS)
            request()
        }
    }

    /** Waits until no sync runs and none is asked for. */
    suspend fun awaitIdle() {
        synchronized(this) { soon }?.join()
        idle.first { it }
    }

    /** Stops the background work; the database stays open. */
    fun close() {
        requests.close()
        scope.cancel()
    }

    private fun markReady() = mutableStatus.update { it.copy(ready = true) }

    /**
     * What a sync writes for one volume. [rows] are the rows to store, the updated ones first; the added ones get their
     * IDs when they are written. [displays] are their display values, in the same order.
     */
    private class VolumePlan(
        val state: VolumeSyncStateEntity,
        val stateChanged: Boolean,
        val changes: FileChanges,
        val rows: List<IndexRow>,
        val displays: List<DisplayValues>,
    )

    /** Reads the whole index first and writes everything in one transaction, so a failed read writes nothing. */
    private suspend fun syncOnce(): Boolean {
        val stored = dao.volumes().associateBy { it.volumeName }
        // An earlier process synced the library: lists may show it while this sync reads the index.
        if (stored.isNotEmpty()) markReady()
        val attached = index.volumes()
        val plans = attached.mapNotNull { volume -> plan(volume, stored[volume]) }
        val detached = SyncPlanner.detached(stored.keys, attached)
        val detachedFiles = detached.flatMap { volume -> dao.storedFiles(volume).map { it.fileId } }

        val filesChanged = plans.any { !it.changes.isEmpty } || detachedFiles.isNotEmpty()
        if (!filesChanged && plans.none { it.stateChanged } && detached.isEmpty()) return false
        database.withWriteTransaction {
            delete(detachedFiles)
            detached.forEach { dao.forgetVolume(it) }
            for (plan in plans) {
                write(plan)
                if (plan.stateChanged) dao.saveVolume(plan.state)
            }
        }
        if (filesChanged) mutableStatus.update { it.copy(revision = it.revision + 1) }
        return filesChanged
    }

    private suspend fun plan(volume: String, stored: VolumeSyncStateEntity?): VolumePlan? {
        val version = index.version(volume) ?: return null
        // Read before the rows: a change that lands while they are read is read again by the next sync.
        val generation = index.generation(volume)
        val files = dao.storedFiles(volume)
        val storedVolume = stored?.let { StoredVolume(it.volumeName, it.version, it.generation) }
        val changes = when (val mode = SyncPlanner.mode(storedVolume, version, generation)) {
            VolumeMode.Full -> SyncPlanner.full(files, index.rows(volume, since = null))
            is VolumeMode.Incremental -> {
                val changed = index.rows(volume, mode.since)
                SyncPlanner.incremental(files, changed, index.ids(volume))
            }
        }
        if (index.version(volume) != version) {
            // The index was rebuilt while it was read: its rows and IDs belong to two versions. The volume keeps its
            // files for now, and the next sync reads it in full under the new version.
            request()
            return null
        }
        val state = VolumeSyncStateEntity(volume, version, generation)
        val rows = changes.updated.map { it.row } + changes.added
        return VolumePlan(state, stateChanged = state != stored, changes, rows, displayValues(rows))
    }

    /** Normalizing 20 000 titles and search texts takes about a second on one core, so the cores share it. */
    private suspend fun displayValues(rows: List<IndexRow>): List<DisplayValues> = coroutineScope {
        rows.chunked(DISPLAY_CHUNK)
            .map { chunk -> async(Dispatchers.Default) { chunk.map(DisplayValues::of) } }
            .awaitAll()
            .flatten()
    }

    /** Updated files keep their row IDs and added ones take the next free IDs; each table is written in bulk. */
    private suspend fun TransactionScope<*>.write(plan: VolumePlan) {
        val changes = plan.changes
        delete(changes.removed)
        if (plan.rows.isEmpty()) return
        val first = dao.lastFileId() + 1
        val ids = changes.updated.map { it.fileId } + List(changes.added.size) { first + it }
        val indices = plan.rows.indices.toList()
        replaceAll("audio_file", FILE_COLUMNS, indices) { i -> file(ids[i], plan.rows[i]) }
        replaceAll("media_index_entry", ENTRY_COLUMNS, indices) { i -> entry(ids[i], plan.rows[i]) }
        replaceAll("track_display", DISPLAY_COLUMNS, indices) { i -> display(ids[i], plan.displays[i]) }
    }

    /** In chunks, so that no statement takes thousands of parameters. */
    private suspend fun delete(fileIds: List<Long>) {
        for (chunk in fileIds.chunked(DELETE_CHUNK)) {
            dao.deleteDisplays(chunk)
            dao.deleteEntries(chunk)
            dao.deleteFiles(chunk)
        }
    }

    private fun JsonRows.Row.file(id: Long, row: IndexRow) {
        long(id)
        long(row.mediaStoreId)
        text(row.volume)
        text(row.relativePath)
        text(row.displayName)
        long(row.size)
        long(row.modifiedSeconds)
        long(row.generationModified)
        text(row.mimeType)
        flag(row.kinds.music)
        flag(row.kinds.podcast)
        flag(row.kinds.audiobook)
        flag(row.kinds.ringtone)
        flag(row.kinds.notification)
        flag(row.kinds.alarm)
        flag(row.kinds.recording)
    }

    private fun JsonRows.Row.entry(id: Long, row: IndexRow) {
        long(id)
        text(row.title)
        text(row.artist)
        text(row.album)
        text(row.albumArtist)
        text(row.composer)
        text(row.genre)
        long(row.year?.toLong())
        long(row.trackNumber?.toLong())
        long(row.durationMs)
    }

    private fun JsonRows.Row.display(id: Long, values: DisplayValues) {
        long(id)
        text(values.title)
        text(values.artist)
        text(values.album)
        long(values.durationMs)
        text(values.titleKey)
        text(values.searchText)
    }

    private companion object {
        const val TAG = "LibrarySync"
        const val CHANGE_DELAY_MS = 1_000L
        const val DELETE_CHUNK = 500
        const val DISPLAY_CHUNK = 2_000

        // The columns of the entities in LibraryDatabase.kt, in the order the writers above give their values.
        val FILE_COLUMNS = listOf(
            "id", "media_store_id", "volume_name", "relative_path", "display_name", "size", "mtime", "generation_modified",
            "mime_type", "is_music", "is_podcast", "is_audiobook", "is_ringtone", "is_notification", "is_alarm",
            "is_recording",
        )
        val ENTRY_COLUMNS = listOf(
            "file_id", "title", "artist", "album", "album_artist", "composer", "genre", "year", "track_number",
            "duration_ms",
        )
        val DISPLAY_COLUMNS = listOf("file_id", "title", "artist", "album", "duration_ms", "title_key", "search_text")
    }
}
