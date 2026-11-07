package io.github.lorddetson.piplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * The player the session wraps, so that every controller turns shuffle on the same way: the app, the notification,
 * the system, Bluetooth and other apps (docs/playback.md §3.6). Shuffle turned on in the middle of a queue starts at
 * the current song; a new queue gets its start song first from [StartFirstShuffleOrder] itself.
 *
 * It shows the queue item's metadata, built from the library's merged values, and nothing of what the extractor finds
 * in the stream, so that fields PiPlayer leaves empty do not show raw tags (TI §7.1). The stream's cover stays until the
 * cover resolver of M2a step 13 takes over.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(private val player: ExoPlayer) : ForwardingPlayer(player) {

    private val filtered = HashMap<Player.Listener, Player.Listener>()

    init {
        // The player is still empty, so this changes no timeline and tells no listener.
        player.setShuffleOrder(StartFirstShuffleOrder())
    }

    /**
     * Sets the order before turning shuffle on, while it is still off: the song after the current one stays the same
     * until shuffle is on, and the player rebuilds what it has prepared ahead only once.
     */
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled && !player.shuffleModeEnabled && player.mediaItemCount > 0) {
            val order = player.shuffleOrder as? StartFirstShuffleOrder ?: StartFirstShuffleOrder()
            player.setShuffleOrder(order.startingAt(player.mediaItemCount, player.currentMediaItemIndex))
        }
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    /**
     * The next queue of [order]'s length plays in [order]: a restored shuffle order (docs/playback.md §4.8). Only while
     * the player is empty.
     */
    fun awaitOrder(order: IntArray, mediaIds: List<String>) {
        if (player.mediaItemCount > 0) return
        val current = player.shuffleOrder as? StartFirstShuffleOrder ?: StartFirstShuffleOrder()
        player.setShuffleOrder(current.waitingFor(order))
        awaitedIds = mediaIds
    }

    /** The queue a waiting order belongs to; another queue makes it go, even of the same length. */
    private var awaitedIds: List<String>? = null

    private fun beforeNewQueue(items: List<MediaItem>) {
        val awaited = awaitedIds ?: return
        awaitedIds = null
        if (items.map { it.mediaId } != awaited && player.mediaItemCount == 0) player.setShuffleOrder(StartFirstShuffleOrder())
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        beforeNewQueue(mediaItems)
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        beforeNewQueue(mediaItems)
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        beforeNewQueue(mediaItems)
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        beforeNewQueue(listOf(mediaItem))
        super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        beforeNewQueue(listOf(mediaItem))
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        beforeNewQueue(listOf(mediaItem))
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun getMediaMetadata(): MediaMetadata = itemMetadata(super.getMediaMetadata())

    override fun addListener(listener: Player.Listener) {
        super.addListener(filtered.getOrPut(listener) { MetadataFilter(listener) })
    }

    override fun removeListener(listener: Player.Listener) {
        filtered.remove(listener)?.let { super.removeListener(it) }
    }

    private fun itemMetadata(stream: MediaMetadata): MediaMetadata {
        val item = currentMediaItem?.mediaMetadata ?: return MediaMetadata.EMPTY
        if (stream.artworkData == null || item.artworkData != null || item.artworkUri != null) return item
        return item.buildUpon().setArtworkData(stream.artworkData, stream.artworkDataType).build()
    }

    /** A listener hears the same as [getMediaMetadata] gives; the session keeps what it hears. */
    private inner class MetadataFilter(listener: Player.Listener) : ForwardingListener(listener) {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
            super.onMediaMetadataChanged(itemMetadata(mediaMetadata))
    }
}
