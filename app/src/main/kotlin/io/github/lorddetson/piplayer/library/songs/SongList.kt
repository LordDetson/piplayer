package io.github.lorddetson.piplayer.library.songs

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.lorddetson.piplayer.library.Library
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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

    @Volatile
    private var songs: List<Song>? = null

    /** The library's revision when the kept list was read; null for a test's own source, which has none. */
    @Volatile
    private var keptRevision: Long? = null

    /** Reads are numbered as they start; only a read newer than the one kept may replace it. */
    private val started = AtomicLong()
    private var kept = 0L
    private val lock = Any()

    /** Reads the list again and keeps it. Blocks; call it off the main thread. */
    fun load(context: Context): List<Song> {
        sourceOverride?.let { return load(it) }
        val app = context.applicationContext
        // Without access the library is not listed, as the media index would list nothing (docs/playback.md §2.2).
        if (!Library.mayRead(app)) return load(SongSource { emptyList() })
        // A saved queue restored while the first sync runs would otherwise find an empty library and be dropped.
        Library.awaitReady(app, READY_TIMEOUT_MS)
        return Library.read(app) { database -> load(LibrarySongSource(database), Library.revision()) }
    }

    /**
     * The first [count] songs of the list, read without the rest and without being kept: a screen shows them while it
     * reads the whole list (docs/library.md §1.4).
     */
    fun firstPage(context: Context, count: Int): List<Song> =
        sourceOverride?.songs()?.take(count) ?: Library.read(context) { LibrarySongSource(it).firstSongs(count) }

    /**
     * The library's revision whenever the list may be read: once the library is ready, and after every sync that
     * changed its files. A test's own source is always ready.
     */
    fun changes(context: Context): Flow<Long> = Library.status(context)
        .filter { it.ready || sourceOverride != null }
        .map { it.revision }
        .distinctUntilChanged()

    /**
     * Reads [source], which gives its songs in list order, and keeps the list, unless a read that started later has
     * already been kept: a slow older read would otherwise leave the playback service a list the screen no longer shows.
     */
    @VisibleForTesting
    internal fun load(source: SongSource, revision: Long? = null): List<Song> {
        val read = started.incrementAndGet()
        val list = source.songs()
        synchronized(lock) {
            if (read > kept) {
                kept = read
                songs = list
                keptRevision = revision
            }
        }
        return list
    }

    /**
     * The list last loaded, or null before the first load and once a sync has changed the library since the list was
     * read: a tap must not expand against a list the screen no longer shows.
     */
    fun current(): List<Song>? {
        val list = songs ?: return null
        val revision = keptRevision ?: return list
        return list.takeIf { revision == Library.revision() }
    }

    /** Forgets the list, so that the next request reads it again. */
    @VisibleForTesting
    fun forget() = synchronized(lock) {
        songs = null
        keptRevision = null
    }

    /** Longer than a first sync of 20 000 files takes (docs/library.md §1.7). */
    private const val READY_TIMEOUT_MS = 30_000L
}
