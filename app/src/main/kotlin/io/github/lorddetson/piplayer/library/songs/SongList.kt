package io.github.lorddetson.piplayer.library.songs

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.SongRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.util.concurrent.atomic.AtomicLong

/**
 * The song list of the library in title order, held once for the process: the list screen and the playback service
 * share it, so that a tap does not read 20 000 rows a second time (docs/playback.md §2.1, §2.3, docs/library.md §1.4).
 */
object SongList {

    /** Replaces the library in tests; read on every load, because the playback service outlives a test. */
    @VisibleForTesting
    @Volatile
    var sourceOverride: SongSource? = null

    /**
     * The kept list with the library's revision and the hidden files it was read with; the key is null for a test's own
     * source, which has neither. One value, so that a reader never pairs a list with another list's key.
     */
    private class Kept(val songs: List<Song>, val key: Pair<Long?, HiddenFiles>?)

    @Volatile
    private var kept: Kept? = null

    /** Reads are numbered as they start; only a read newer than the one kept may replace it. */
    private val started = AtomicLong()
    private var keptRead = 0L
    private val lock = Any()

    /** Whether a read keeps the order of the list, while the tag pass runs and until the list is sorted anew after it. */
    private var frozen = false
    private var passRunning = false
    private var changedByPass = false
    private var resortPending = false
    private var activeReads = 0
    private var batchCount = 0L

    /** Values the tag pass gave while a read was under way. */
    private val racing = HashMap<Long, SongRow>()
    private val mutableBatches = MutableStateFlow(0L)

    /**
     * Reads the list again and keeps it. While the tag pass has the order frozen it stays as it is, unless [fresh]. Blocks;
     * call it off the main thread.
     */
    fun load(context: Context, fresh: Boolean = false): List<Song> {
        sourceOverride?.let { return load(it, fresh = fresh) }
        val app = context.applicationContext
        // Without access the library is not listed, as the media index would list nothing (docs/playback.md §2.2).
        if (!Library.mayRead(app)) return load(SongSource { emptyList() }, fresh = fresh)
        // A saved queue restored while the first sync runs would otherwise find an empty library and be dropped.
        Library.awaitReady(app, READY_TIMEOUT_MS)
        val hidden = LibrarySettings(app).readHiddenFiles()
        return Library.read(app) { database -> load(LibrarySongSource(database, hidden), Library.revision() to hidden, fresh) }
    }

    /**
     * The first [count] songs of the list, read without the rest and without being kept: a screen shows them while it
     * reads the whole list (docs/library.md §1.4).
     */
    fun firstPage(context: Context, count: Int): List<Song> =
        sourceOverride?.songs()?.take(count) ?: LibrarySettings(context).readHiddenFiles().let { hidden ->
            Library.read(context) { LibrarySongSource(it, hidden).firstSongs(count) }
        }

    /**
     * A new value whenever the list may be read: once the library is ready, after every sync that changed its files,
     * and after every change of the hidden files (docs/library.md §3.5). A test's own source is always ready.
     */
    fun changes(context: Context): Flow<Any> = Library.status(context)
        .filter { it.ready || sourceOverride != null }
        .combine(LibrarySettings(context).hiddenFiles) { status, hidden -> status.revision to hidden }
        .distinctUntilChanged()

    /**
     * Reads [source], which gives its songs in list order, and keeps the list, unless a read that started later has
     * already been kept: a slow older read would otherwise leave the playback service a list the screen no longer shows.
     */
    @VisibleForTesting
    internal fun load(source: SongSource, key: Pair<Long?, HiddenFiles>? = null, fresh: Boolean = false): List<Song> {
        var batchesAtStart = 0L
        val read = synchronized(lock) {
            activeReads++
            batchesAtStart = batchCount
            started.incrementAndGet()
        }
        var list = emptyList<Song>()
        var failed = true
        try {
            list = source.songs()
            failed = false
        } finally {
            if (failed) synchronized(lock) { readEnded() }
        }
        return synchronized(lock) {
            val patched = if (racing.isEmpty()) list else list.withValues(racing)
            readEnded()
            val shown = kept?.songs.takeIf { frozen && !fresh }?.let { FrozenOrder.merge(it, patched) } ?: patched
            // A batch during the read: this order is older than its titles, so the sort stays due.
            if (fresh && !passRunning && batchCount == batchesAtStart) {
                frozen = false
                changedByPass = false
                resortPending = false
            }
            // A fresh read ends the order that a newer read may have been merged against, so it replaces that one too.
            if (fresh || read > keptRead) {
                keptRead = maxOf(keptRead, read)
                kept = Kept(shown, key)
            }
            shown
        }
    }

    private fun readEnded() {
        activeReads--
        if (activeReads == 0) racing.clear()
    }

    /**
     * The list last loaded, or null before the first load and once a sync or a change of the hidden files has changed
     * it since: a tap must not expand against a list the screen no longer shows.
     */
    fun current(): List<Song>? {
        val kept = kept ?: return null
        val (revision, hidden) = kept.key ?: return kept.songs
        return kept.songs.takeIf { revision == Library.revision() && hidden == (LibrarySettings.latest ?: hidden) }
    }

    /** The list as it was last read with [key], unless it no longer holds; null for a test's own source. */
    fun keptFor(key: Any): List<Song>? {
        val kept = kept ?: return null
        return kept.songs.takeIf { kept.key == key }
    }

    /**
     * The song as the kept list holds it, also when that list is out of date since a sync: a batch's values hold whichever
     * revision it was read at, and with the screen off nothing reads the list again.
     */
    fun keptSong(id: Long): Song? = kept?.songs?.firstOrNull { it.id == id }

    /** Raised after each batch of the tag pass that [applyInPlace] took. */
    val batches: StateFlow<Long> get() = mutableBatches

    /**
     * New values for rows of the kept list: rows change in place, none moves or goes (docs/library.md §5.4). A read under
     * way may hold the older values, so it gets these too when it is kept.
     */
    fun applyInPlace(rows: List<SongRow>) {
        if (rows.isEmpty()) return
        applyRows(rows) {
            changedByPass = true
            frozen = true
        }
    }

    /** New lengths for rows of the kept list, from the walks after a pass: no order depends on them (docs/library.md §7.2). */
    fun applyLengths(rows: List<SongRow>) {
        if (rows.isNotEmpty()) applyRows(rows) {}
    }

    private inline fun applyRows(rows: List<SongRow>, marking: () -> Unit) {
        synchronized(lock) {
            marking()
            batchCount++
            if (activeReads > 0) rows.forEach { racing[it.mediaStoreId] = it }
            kept = kept?.let { current ->
                val byId = rows.associateBy { it.mediaStoreId }
                Kept(current.songs.withValues(byId), current.key)
            }
        }
        mutableBatches.update { it + 1 }
    }

    /** The tag pass starts: from now on a read keeps the order of the list, unless asked for a fresh one. */
    fun passStarted() = synchronized(lock) {
        passRunning = true
        frozen = true
    }

    /** The tag pass ended: if it changed rows, one fresh read is due at the next chance (docs/library.md §5.4). */
    fun passFinished() = synchronized(lock) {
        passRunning = false
        if (changedByPass) resortPending = true else frozen = false
    }

    /** Whether the list should be sorted anew; the fresh read ends that, and another pass running makes it wait. */
    fun resortDue(): Boolean = synchronized(lock) { resortPending && !passRunning }

    /** Forgets the list and all that a tag pass left, so that the next request reads it again. */
    @VisibleForTesting
    fun forget() = synchronized(lock) {
        kept = null
        frozen = false
        passRunning = false
        changedByPass = false
        resortPending = false
        racing.clear()
    }

    private fun List<Song>.withValues(rows: Map<Long, SongRow>): List<Song> = map { song ->
        rows[song.id]?.let { song.copy(title = it.title, artist = it.artist, album = it.album, durationMs = it.durationMs) } ?: song
    }

    /** Longer than a first sync of 20 000 files takes (docs/library.md §1.7). */
    private const val READY_TIMEOUT_MS = 30_000L
}
