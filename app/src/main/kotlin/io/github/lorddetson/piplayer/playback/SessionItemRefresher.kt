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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
 * the player's thread, under a job of its own that [stop] ends, so that no read under way reaches a released player.
 */
class SessionItemRefresher(
    private val player: Player,
    private val songs: suspend (Collection<Long>) -> Map<Long, Song>,
    scope: CoroutineScope,
) {

    private val turn = Mutex()

    /** [scope] with a job of its own for the reads and comparisons: [stop] cancels them, and none starts after it. */
    private val own = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
    }

    /** From now on, each transition refreshes too. */
    fun start() = player.addListener(listener)

    /** For good: transitions no longer refresh, a read under way is dropped and later refreshes do nothing. */
    fun stop() {
        player.removeListener(listener)
        own.cancel()
    }

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
     * Reads the songs of the items [choose] gives, then compares those items once they are read. They are chosen once,
     * since choosing can look through a large queue: the queue may have changed meanwhile, so an item is compared only
     * where its place still holds the same entry, and the others catch up at their transition (docs/playback.md §2.6).
     */
    private fun compare(choose: () -> List<Int>) {
        // An undispatched start runs up to its first suspension even in a cancelled scope, the read included.
        if (!own.isActive) return
        own.launch(start = CoroutineStart.UNDISPATCHED) {
            turn.withLock {
                val chosen = choose().mapNotNull { index -> entryAt(index) }
                val ids = chosen.mapTo(LinkedHashSet()) { it.file }
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
                // Stopped while the read returned: the player may be released.
                ensureActive()
                for (entry in chosen) refreshAt(entry, found)
            }
        }
    }

    /** A queue item to compare: its place, its entry and the library file it plays. */
    private class Entry(val index: Int, val mediaId: String, val file: Long)

    private fun entryAt(index: Int): Entry? {
        if (index !in 0 until player.mediaItemCount) return null
        val mediaId = player.getMediaItemAt(index).mediaId
        return QueueIds.fileId(mediaId)?.let { Entry(index, mediaId, it) }
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

    private fun refreshAt(entry: Entry, found: Map<Long, Song>) {
        val index = entry.index
        if (index !in 0 until player.mediaItemCount) return
        val item = player.getMediaItemAt(index)
        // Another entry in its place, after an edit of the queue during the read.
        if (item.mediaId != entry.mediaId) return
        // An added entry plays a library song too (docs/playback.md §9.4).
        val song = found[entry.file] ?: return
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
