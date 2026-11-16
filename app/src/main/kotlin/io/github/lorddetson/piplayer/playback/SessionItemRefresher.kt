package io.github.lorddetson.piplayer.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.github.lorddetson.piplayer.library.songs.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps queue items up to the library's merged values in place (docs/playback.md §2.6, TI §7.1): an item is replaced only
 * when what it shows differs, with the same URI, so playback does not prepare again. Each replacement costs every
 * controller a new timeline, so only some items are compared: the playing and the next item after each batch of the tag
 * pass ([refresh]) and at each transition, and after a change of decided values the playing item and at most
 * [DecidedRefresh.MAX_QUEUED] queued items of the files it touched ([refreshDecided]).
 *
 * [songs] reads the songs of file IDs from the library each time, never from a list a screen keeps, which a decision does
 * not change; it gives nothing for what the library does not hold, such as a file opened from another app. Reads take
 * turns, each applied before the next starts, so that an older read never replaces a newer one's values. Runs on [scope],
 * the player's thread.
 */
class SessionItemRefresher(
    private val player: Player,
    private val songs: suspend (Collection<Long>) -> Map<Long, Song>,
    private val scope: CoroutineScope,
) {

    private val turn = Mutex()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
    }

    /** From now on, each transition refreshes too. */
    fun start() = player.addListener(listener)

    fun stop() = player.removeListener(listener)

    /** Compares the playing and the next item: after a batch of the tag pass, at a transition. */
    fun refresh() = compare(::currentAndNext)

    /**
     * Compares the playing item and the queued items of [files], whose decided values changed (TI §7.1), or the playing
     * and the next item when [files] is null, not known.
     */
    fun refreshDecided(files: Set<Long>?) {
        if (files == null) refresh() else compare { decided(files) }
    }

    /**
     * Reads the songs of the items [choose] gives, then compares the items it gives once they are read: the queue may have
     * changed meanwhile, and only items whose songs were read are compared.
     */
    private fun compare(choose: () -> List<Int>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            turn.withLock {
                val ids = choose().mapNotNullTo(LinkedHashSet()) { fileAt(it) }
                if (ids.isEmpty()) return@withLock
                val found = try {
                    songs(ids)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A library that cannot be read now must not take playback down; the items stay as they are.
                    Log.w(TAG, "The queue items could not be compared with the library", e)
                    return@withLock
                }
                for (index in choose()) refreshAt(index, found)
            }
        }
    }

    private fun fileAt(index: Int): Long? =
        if (index in 0 until player.mediaItemCount) QueueIds.fileId(player.getMediaItemAt(index).mediaId) else null

    private fun currentAndNext(): List<Int> {
        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return emptyList()
        val next = player.nextMediaItemIndex
        return if (next == C.INDEX_UNSET || next == current) listOf(current) else listOf(current, next)
    }

    private fun decided(files: Set<Long>): List<Int> {
        val timeline = player.currentTimeline
        val shuffled = player.shuffleModeEnabled
        return DecidedRefresh.indices(
            current = player.currentMediaItemIndex,
            count = timeline.windowCount,
            // Play order, wrapping round, so that the items before the playing one come last.
            next = { timeline.getNextWindowIndex(it, Player.REPEAT_MODE_ALL, shuffled) },
            fileOf = ::fileAt,
            touched = files,
        )
    }

    private fun refreshAt(index: Int, found: Map<Long, Song>) {
        if (index !in 0 until player.mediaItemCount) return
        val item = player.getMediaItemAt(index)
        // An added entry plays a library song too (docs/playback.md §9.4).
        val song = QueueIds.fileId(item.mediaId)?.let(found::get) ?: return
        if (item.mediaMetadata.shows(song)) return
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(songMetadata(song)).build())
    }

    /** Whether the item shows what [songMetadata] would give [song]: its values and no cover of its own. */
    private fun MediaMetadata.shows(song: Song) =
        title?.toString() == song.title && artist?.toString() == song.artist && albumTitle?.toString() == song.album &&
            durationMs == song.durationMs && artworkUri == null && artworkData == null

    companion object {
        private const val TAG = "SessionItemRefresher"

        /**
         * What a queue item shows of a song. No artwork, which the session would publish to other apps: the playing item's
         * cover comes from the session player (docs/library.md §13.6, §13.8.6).
         */
        fun songMetadata(song: Song): MediaMetadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setDurationMs(song.durationMs)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    }
}
