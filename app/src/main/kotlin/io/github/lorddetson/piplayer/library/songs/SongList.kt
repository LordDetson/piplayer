package io.github.lorddetson.piplayer.library.songs

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.lorddetson.piplayer.library.Library
import kotlinx.coroutines.flow.Flow
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

    /** Reads the list again and keeps it. Blocks; call it off the main thread. */
    fun load(context: Context): List<Song> {
        sourceOverride?.let { return load(it) }
        val app = context.applicationContext
        // Without access the library is not listed, as the media index would list nothing (docs/playback.md §2.2).
        if (!Library.mayRead(app)) return load(SongSource { emptyList() })
        // A saved queue restored while the first sync runs would otherwise find an empty library and be dropped.
        Library.awaitReady(app, READY_TIMEOUT_MS)
        val hidden = LibrarySettings(app).readHiddenFiles()
        return Library.read(app) { database -> load(LibrarySongSource(database, hidden), Library.revision() to hidden) }
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
    internal fun load(source: SongSource, key: Pair<Long?, HiddenFiles>? = null): List<Song> {
        val read = started.incrementAndGet()
        val list = source.songs()
        synchronized(lock) {
            if (read > keptRead) {
                keptRead = read
                kept = Kept(list, key)
            }
        }
        return list
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

    /** Forgets the list, so that the next request reads it again. */
    @VisibleForTesting
    fun forget() = synchronized(lock) {
        kept = null
    }

    /** Longer than a first sync of 20 000 files takes (docs/library.md §1.7). */
    private const val READY_TIMEOUT_MS = 30_000L
}
