package io.github.lorddetson.piplayer.library.songs

import android.content.Context
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicLong

/**
 * The temporary song list in title order, held once for the process: the list screen and the playback service share it,
 * so that a tap does not read the media index a second time (docs/playback.md §2.1, §2.3).
 */
object SongList {

    /** Replaces the media index in tests; read on every load, because the playback service outlives a test. */
    @VisibleForTesting
    @Volatile
    var sourceOverride: SongSource? = null

    @Volatile
    private var songs: List<Song>? = null

    /** Reads are numbered as they start; only a read newer than the one kept may replace it. */
    private val started = AtomicLong()
    private var kept = 0L
    private val lock = Any()

    /** Reads the list again and keeps it. Blocks; call it off the main thread. */
    fun load(context: Context): List<Song> = load(sourceOverride ?: MediaStoreSongSource(context.applicationContext))

    /**
     * Reads [source] and keeps the list, unless a read that started later has already been kept: a slow older read
     * would otherwise leave the playback service a list the screen no longer shows.
     */
    @VisibleForTesting
    internal fun load(source: SongSource): List<Song> {
        val read = started.incrementAndGet()
        val sorted = SongOrder.byTitle(source.songs())
        synchronized(lock) {
            if (read > kept) {
                kept = read
                songs = sorted
            }
        }
        return sorted
    }

    /** The list last loaded, or null before the first load. */
    fun current(): List<Song>? = songs

    /** Forgets the list, so that the next request reads it again. */
    @VisibleForTesting
    fun forget() = synchronized(lock) {
        songs = null
    }
}
