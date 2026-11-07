package io.github.lorddetson.piplayer.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * Keeps the compact state of the playing queue up to date (docs/playback.md §4.2, §5.3): every five seconds while
 * playing and at once when playback pauses, stops, changes song or position, or when shuffle, repeat or the queue
 * changes. Snapshots are built on the main thread, where the player lives; the store writes them off it.
 *
 * A queue without a compact request and no opened files (a test's own items) is not saved; the last snapshot stays as
 * it was.
 */
@OptIn(UnstableApi::class)
class PlaybackStateSaver(
    private val player: Player,
    private val store: PlaybackStateStore,
    /** The player's shuffle order, saved exactly while shuffle is on (docs/playback.md §4.8). */
    private val shuffleOrder: () -> ShuffleOrder? = { null },
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())

    /** Written by the session callback's expansion thread, read on the main thread. */
    @Volatile
    private var queue: QueueState? = null

    /** The last snapshot handed to the store; only touched on the main thread. */
    private var last: PlaybackSnapshot? = null

    private var started = false

    /** The shuffle order last written to the queue file, and that file's revision; only touched on the main thread. */
    private var savedOrder: ShuffleOrder? = null
    private var savedOrderQueue: QueueState? = null
    private var queueRevision = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (player.isPlaying) save()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        player.addListener(this)
        handler.postDelayed(tick, SAVE_INTERVAL_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        player.removeListener(this)
        handler.removeCallbacks(tick)
    }

    /** The queue now playing, or null when it is not one of PiPlayer's own lists or opened files. */
    fun setQueue(state: QueueState?) {
        queue = state
        // May come from the callback's expansion thread; the main thread owns the last snapshot.
        handler.post {
            last = null
            if (state == null) {
                // The saved queue may be gone with it (docs/playback.md §9.4): the next one writes its queue file anew.
                savedOrder = null
                savedOrderQueue = null
                queueRevision = 0
            } else {
                save()
            }
        }
    }

    /** Writes the current state before the player goes away. */
    fun saveNow() = save()

    private fun save() {
        val state = queue ?: return
        val item = player.currentMediaItem ?: return
        val row = player.currentMediaItemIndex
        if (row < 0) return
        val songId = QueueIds.fileId(item.mediaId) ?: -1L
        val uri = item.localConfiguration?.uri?.toString()
        if (songId < 0) {
            // During an insertion the player can change before the new queue state arrives; do not save that moment.
            if (state.openItems.none { it.index == row && it.uri == uri }) return
        } else if (state.library == null && state.edits.added.isEmpty()) {
            return
        }
        // While the player and the queue state disagree no snapshot is written: it would name a wrong queue file.
        if (!saveQueueFile(state)) return
        // A queue of added songs only lives in the queue file; without it there is nothing to name.
        if (state.library == null && state.openItems.isEmpty() && queueRevision <= 0) return
        val metadata = item.mediaMetadata
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
        val snapshot = PlaybackSnapshot(
            queue = state.library,
            openItems = state.openItems,
            songId = songId,
            row = row,
            positionMs = position,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            title = metadata.title?.toString(),
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            durationMs = (metadata.durationMs ?: 0L).coerceAtLeast(0),
            uri = uri,
            queueRevision = queueRevision,
            libraryId = state.libraryId,
        )
        if (snapshot == last) return
        last = snapshot
        store.enqueue(snapshot)
    }

    /**
     * Writes the queue file when the queue's edits or, while shuffle is on, its play order changed, before the snapshot
     * that names the file's revision (docs/playback.md §4.8, §9.4). With neither, the snapshot names no queue file.
     * False while the player and [state] disagree for a moment, when nothing may be saved.
     */
    private fun saveQueueFile(state: QueueState): Boolean {
        val order = shuffleOrder()?.takeIf { player.shuffleModeEnabled } as? StartFirstShuffleOrder
        if (order == null && state.edits.isEmpty) {
            savedOrder = null
            savedOrderQueue = null
            queueRevision = 0
            return true
        }
        if (order === savedOrder && state === savedOrderQueue) return true
        savedOrder = order
        savedOrderQueue = state
        val orderKeys = order?.let {
            val indices = it.indices()
            val keys = entryKeys(state)?.takeIf { keys -> indices.size == keys.size }
            if (keys == null) {
                // The player and the queue state disagree for a moment; no snapshot may name an older queue's file. The
                // next change of either tries again.
                savedOrder = null
                return false
            }
            QueueOrder.keys(keys, indices)
        }
        queueRevision = maxOf(queueRevision + 1, System.currentTimeMillis())
        val edits = state.edits
        store.enqueue(
            QueueFile(
                revision = queueRevision,
                libraryId = state.libraryId,
                added = edits.added,
                journal = edits.journal,
                order = orderKeys,
                nextNumber = edits.nextNumber,
                upNext = edits.upNext,
            ),
        )
        return true
    }

    /** The entries of the player's queue by their keys, or null while the player and [state] disagree. */
    private fun entryKeys(state: QueueState): List<EntryKey>? =
        QueueIds.keys(List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }, state.openItems)

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = save()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = save()

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = save()

    override fun onIsPlayingChanged(isPlaying: Boolean) = save()

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = save()

    override fun onPlaybackStateChanged(playbackState: Int) = save()

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = save()

    override fun onRepeatModeChanged(repeatMode: Int) = save()

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
