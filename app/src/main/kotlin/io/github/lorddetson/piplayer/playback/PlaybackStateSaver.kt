package io.github.lorddetson.piplayer.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * Keeps the compact state of the playing queue up to date (docs/playback.md §4.2): every five seconds while playing and
 * at once when playback pauses, stops, changes song or position, or when shuffle, repeat or the queue changes. Snapshots
 * are built on the main thread, where the player lives; the store writes them off it.
 *
 * A queue that has no compact request (a debug file, a file opened from another app) is not saved; the last library
 * snapshot stays as it was.
 */
class PlaybackStateSaver(private val player: Player, private val store: PlaybackStateStore) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())

    /** Written by the session callback's expansion thread, read on the main thread. */
    @Volatile
    private var queue: QueueRequest? = null

    /** The last snapshot handed to the store; only touched on the main thread. */
    private var last: PlaybackSnapshot? = null

    private var started = false

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

    /** The expandable queue now playing, or null when it is not one of PiPlayer's own lists. */
    fun setQueue(request: QueueRequest?) {
        queue = request
        // May come from the callback's expansion thread; the main thread owns the last snapshot.
        handler.post {
            last = null
            if (request != null) save()
        }
    }

    /** Writes the current state before the player goes away. */
    fun saveNow() = save()

    private fun save() {
        val request = queue ?: return
        val item = player.currentMediaItem ?: return
        val row = player.currentMediaItemIndex
        if (row < 0) return
        val songId = item.mediaId.toLongOrNull() ?: return
        val metadata = item.mediaMetadata
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
        val snapshot = PlaybackSnapshot(
            queue = request,
            songId = songId,
            row = row,
            positionMs = position,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            title = metadata.title?.toString(),
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            durationMs = (metadata.durationMs ?: 0L).coerceAtLeast(0),
            uri = item.localConfiguration?.uri?.toString(),
        )
        if (snapshot == last) return
        last = snapshot
        store.enqueue(snapshot)
    }

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
