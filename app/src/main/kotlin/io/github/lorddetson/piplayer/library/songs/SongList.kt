package io.github.lorddetson.piplayer.library.songs

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.SongRow
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update

/**
 * The song lists of the library, held once for the process: the screens and the playback service share them, so that a
 * tap plays exactly what is visible and does not read 20 000 rows a second time (docs/playback.md §2.1, §2.3,
 * docs/library.md §1.4, §6.4). One list is kept per [SongQuery], for the last [MAX_KEPT] lists read.
 */
object SongList {

    /** Replaces the library in tests; read on every load, because the playback service outlives a test. */
    @VisibleForTesting
    @Volatile
    var sourceOverride: SongSource? = null

    /**
     * A kept list with the library's revision and the hidden files it was read with; the key is null for a test's own
     * source, which has neither. [read] numbers the read that gave it. [frozen] keeps its order while the tag pass changes
     * titles, [resortDue] asks for one fresh read after a pass that changed rows (docs/library.md §5.4).
     */
    private class Kept(
        val songs: List<Song>,
        val key: Pair<Long?, HiddenFiles>?,
        val read: Long,
        val frozen: Boolean,
        val resortDue: Boolean,
        /** The collation its sort keys were made by (docs/library.md §11.1). */
        val collation: String? = null,
    )

    /** In the order of last use, so that the oldest goes first. Guarded by [lock]. */
    private val kept = LinkedHashMap<SongQuery, Kept>(16, 0.75f, true)

    /** The list each holder needs kept (§6.4). Guarded by [lock]. */
    private val held = HashMap<Any, SongQuery>()

    /** Reads are numbered as they start; only a read newer than the one kept may replace it. */
    private val started = AtomicLong()
    private val lock = Any()

    private var passRunning = false
    private var changedByPass = false
    private var activeReads = 0
    private var batchCount = 0L

    /** Values the tag pass gave while a read was under way. */
    private val racing = HashMap<Long, SongRow>()
    private val mutableBatches = MutableStateFlow(0L)

    /**
     * Reads [query] again and keeps it. While the tag pass has its order frozen it stays as it is, unless [fresh]. Blocks;
     * call it off the main thread.
     */
    fun load(context: Context, fresh: Boolean = false, query: SongQuery = SongQuery.SONGS): List<Song> {
        sourceOverride?.let { return load(it, fresh = fresh, query = query) }
        val app = context.applicationContext
        // Without access the library is not listed, as the media index would list nothing (docs/playback.md §2.2).
        if (!Library.mayRead(app)) return load(SongSource { emptyList() }, fresh = fresh, query = query)
        // A saved queue restored while the first sync runs would otherwise find an empty library and be dropped.
        Library.awaitReady(app, READY_TIMEOUT_MS)
        val hidden = LibrarySettings(app).readHiddenFiles()
        return Library.read(app) { database ->
            load(LibrarySongSource(database, hidden, query), Library.revision() to hidden, fresh, query, Library.collation())
        }
    }

    /**
     * The songs of the files [ids] by their file ID, read without being kept (docs/playback.md §9.1, §9.4); with a test's
     * own source, those of its songs. Blocks; call it off the main thread.
     */
    fun songsById(context: Context, ids: Collection<Long>): Map<Long, Song> {
        if (ids.isEmpty()) return emptyMap()
        sourceOverride?.let { source ->
            val wanted = ids.toHashSet()
            return source.songs().filter { it.id in wanted }.associateBy { it.id }
        }
        val app = context.applicationContext
        if (!Library.mayRead(app)) return emptyMap()
        Library.awaitReady(app, READY_TIMEOUT_MS)
        return Library.read(app) { database -> LibraryLists(database).songsById(ids) }
    }

    /**
     * The first [count] songs of [query], read without the rest and without being kept: a screen shows them while it
     * reads the whole list (docs/library.md §1.4).
     */
    fun firstPage(context: Context, count: Int, query: SongQuery = SongQuery.SONGS): List<Song> =
        sourceOverride?.songs()?.take(count) ?: LibrarySettings(context).readHiddenFiles().let { hidden ->
            Library.read(context) { LibrarySongSource(it, hidden, query).firstSongs(count) }
        }

    /**
     * A new value whenever the lists may be read: once the library is ready, after every sync that changed its files,
     * and after every change of the hidden files (docs/library.md §3.5). A test's own source is always ready.
     */
    fun changes(context: Context): Flow<Any> = Library.status(context)
        .filter { it.ready || sourceOverride != null }
        .combine(LibrarySettings(context).hiddenFiles) { status, hidden -> status.revision to hidden }
        .distinctUntilChanged()

    /**
     * Reads [source], which gives its songs in list order, and keeps the list as [query], unless a read of it that
     * started later has already been kept: a slow older read would otherwise leave the playback service a list the screen
     * no longer shows.
     */
    @VisibleForTesting
    internal fun load(
        source: SongSource,
        key: Pair<Long?, HiddenFiles>? = null,
        fresh: Boolean = false,
        query: SongQuery = SongQuery.SONGS,
        collation: String? = null,
    ): List<Song> {
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
            val old = kept[query]
            // Keys of another collation are another order: the list takes it, even while the pass runs (§11.1).
            val keepsOrder = old != null && (old.frozen || passRunning) && !fresh && old.collation == collation
            val shown = if (keepsOrder) FrozenOrder.merge(old!!.songs, patched) else patched
            var frozen = old?.frozen == true || passRunning
            var resortDue = old?.resortDue == true
            // A batch during the read: this order is older than its titles, so the sort stays due.
            if (fresh && !passRunning && batchCount == batchesAtStart) {
                frozen = false
                resortDue = false
            }
            // A fresh read ends the order that a newer read may have been merged against, so it replaces that one too.
            if (fresh || old == null || read > old.read) {
                kept[query] = Kept(shown, key, maxOf(read, old?.read ?: 0), frozen, resortDue, collation)
                evict()
            }
            shown
        }
    }

    private fun readEnded() {
        activeReads--
        if (activeReads == 0) racing.clear()
    }

    /**
     * The list of [query] last loaded, or null before its first load and once a sync or a change of the hidden files has
     * changed it since: a tap must not expand against a list the screen no longer shows.
     */
    fun current(query: SongQuery = SongQuery.SONGS): List<Song>? {
        val kept = synchronized(lock) { kept[query] } ?: return null
        val (revision, hidden) = kept.key ?: return kept.songs
        return kept.songs.takeIf { revision == Library.revision() && hidden == (LibrarySettings.latest ?: hidden) }
    }

    /** The list of [query] as it was last read with [key], unless it no longer holds; null for a test's own source. */
    fun keptFor(key: Any, query: SongQuery = SongQuery.SONGS): List<Song>? {
        val kept = synchronized(lock) { kept[query] } ?: return null
        return kept.songs.takeIf { kept.key == key }
    }

    /**
     * The song as a kept list holds it, also when that list is out of date since a sync: a batch's values hold whichever
     * revision it was read at, and with the screen off nothing reads the list again.
     */
    fun keptSong(id: Long): Song? = synchronized(lock) {
        kept.values.firstNotNullOfOrNull { list -> list.songs.firstOrNull { it.id == id } }
    }

    /** Raised after each batch of the tag pass that [applyInPlace] took. */
    val batches: StateFlow<Long> get() = mutableBatches

    /**
     * New values for rows of the kept lists: rows change in place, none moves or goes (docs/library.md §5.4). A read under
     * way may hold the older values, so it gets these too when it is kept.
     */
    fun applyInPlace(rows: List<SongRow>) {
        if (rows.isEmpty()) return
        applyRows(rows, freezing = true)
    }

    /** New lengths for rows of the kept lists, from the walks after a pass: no order depends on them (docs/library.md §7.2). */
    fun applyLengths(rows: List<SongRow>) {
        if (rows.isNotEmpty()) applyRows(rows, freezing = false)
    }

    private fun applyRows(rows: List<SongRow>, freezing: Boolean) {
        synchronized(lock) {
            if (freezing) changedByPass = true
            batchCount++
            if (activeReads > 0) rows.forEach { racing[it.fileId] = it }
            val byId = rows.associateBy { it.fileId }
            for (entry in kept.entries) {
                val list = entry.value
                entry.setValue(Kept(list.songs.withValues(byId), list.key, list.read, list.frozen || freezing, list.resortDue, list.collation))
            }
        }
        mutableBatches.update { it + 1 }
    }

    /** The tag pass starts: from now on a read keeps the order of its list, unless asked for a fresh one. */
    fun passStarted() = synchronized(lock) {
        passRunning = true
        mapKept { Kept(it.songs, it.key, it.read, frozen = true, it.resortDue, it.collation) }
    }

    /** The tag pass ended: if it changed rows, one fresh read of each kept list is due at the next chance (§5.4). */
    fun passFinished() = synchronized(lock) {
        passRunning = false
        val changed = changedByPass
        changedByPass = false
        if (changed) changingPasses++
        mapKept { if (changed) Kept(it.songs, it.key, it.read, frozen = true, resortDue = true, it.collation) else Kept(it.songs, it.key, it.read, false, it.resortDue, it.collation) }
    }

    private inline fun mapKept(change: (Kept) -> Kept) {
        for (entry in kept.entries) entry.setValue(change(entry.value))
    }

    /**
     * [holder] needs [query]'s list kept, or no list any more when null: a screen holds the list it shows, the playback
     * service the list it plays. A held list is not pushed out by newer ones, so that typing a search or opening pages
     * does not lose the order a screen on the back stack keeps during the tag pass (docs/library.md §5.4, §6.4).
     */
    fun hold(holder: Any, query: SongQuery?) = synchronized(lock) {
        if (query == null) held.remove(holder) else held[holder] = query
        evict()
    }

    /** Drops the oldest lists past [MAX_KEPT] that nobody holds. */
    private fun evict() {
        if (kept.size <= MAX_KEPT) return
        val holding = held.values.toHashSet()
        val iterator = kept.keys.iterator()
        var excess = kept.size - MAX_KEPT
        while (excess > 0 && iterator.hasNext()) {
            if (iterator.next() !in holding) {
                iterator.remove()
                excess--
            }
        }
    }

    /** How many passes have ended that changed rows; the lists of albums, artists and folders group again after one. */
    @Volatile
    var changingPasses = 0L
        private set

    /** Whether the tag pass runs now. */
    fun passRunning(): Boolean = synchronized(lock) { passRunning }

    /** Whether [query]'s list should be sorted anew; its fresh read ends that, and another pass running makes it wait. */
    fun resortDue(query: SongQuery = SongQuery.SONGS): Boolean = synchronized(lock) {
        !passRunning && kept[query]?.resortDue == true
    }

    /** Forgets the lists and all that a tag pass left, so that the next request reads them again. */
    @VisibleForTesting
    fun forget() = synchronized(lock) {
        kept.clear()
        held.clear()
        passRunning = false
        changedByPass = false
        racing.clear()
    }

    private fun List<Song>.withValues(rows: Map<Long, SongRow>): List<Song> = map { song ->
        rows[song.id]?.let { song.copy(title = it.title, artist = it.artist, album = it.album, durationMs = it.durationMs) } ?: song
    }

    /** How many lists are kept at most, besides those held: the recent screens and the one the service plays. */
    const val MAX_KEPT = 6

    /** Longer than a first sync of 20 000 files takes (docs/library.md §1.7). */
    private const val READY_TIMEOUT_MS = 30_000L
}
