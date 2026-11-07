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

    /**
     * What the queue's edits know (docs/playback.md §9): the up-next run and the journal. Every change of the list goes
     * through this player, whoever asks for it, so that the journal sees it.
     */
    interface Edits {
        /** The rows of the up-next run right after the current entry, which stay next when shuffle goes on. */
        fun upNextRows(): IntArray

        /** The play position after which songs that join the queue in other ways go. */
        fun joinAfter(): Int

        /** The entries from [from] to [to] are about to be removed. */
        fun removing(from: Int, to: Int)

        /** The entries from [from] to [to] are about to move to start at [newIndex]. */
        fun moving(from: Int, to: Int, newIndex: Int)
    }

    var edits: Edits? = null

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
            // The up-next run stays right after the current song (§9.3).
            val next = edits?.upNextRows() ?: IntArray(0)
            player.setShuffleOrder(order.startingAt(player.mediaItemCount, player.currentMediaItemIndex, next))
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

    /** The shuffle order now, or null while shuffle is off or the order is not PiPlayer's. */
    val playOrder: StartFirstShuffleOrder?
        get() = if (player.shuffleModeEnabled) player.shuffleOrder as? StartFirstShuffleOrder else null

    /** Puts [order] in place: a drag while shuffle is on changes only the play order (§9.2). */
    fun setPlayOrder(order: StartFirstShuffleOrder) = player.setShuffleOrder(order)

    /**
     * Adds [items] at list [index], at play [position] while shuffle is on, or else where songs that join the queue go
     * (§9.3); the edits record their own inserts.
     */
    fun insert(index: Int, items: List<MediaItem>, position: Int?) {
        (player.shuffleOrder as? StartFirstShuffleOrder)?.placeNextInsert(
            if (position != null) InsertPlacement.At(position) else InsertPlacement.RandomAfter(joinPosition()),
        )
        player.addMediaItems(index, items)
    }

    /** Where songs that join the queue go: after the current song and its up-next run in the play order. */
    private fun joinPosition(): Int = edits?.joinAfter() ?: run {
        val order = player.shuffleOrder as? StartFirstShuffleOrder
        val current = player.currentMediaItemIndex
        if (order != null && current in 0 until order.length) order.positionOf(current) else 0
    }

    /** Songs added by a controller or an open from another app go to random places after the current song (§9.3). */
    private fun placeJoining() {
        if (player.mediaItemCount == 0) return
        (player.shuffleOrder as? StartFirstShuffleOrder)?.placeNextInsert(InsertPlacement.RandomAfter(joinPosition()))
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        placeJoining()
        super.addMediaItem(mediaItem)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        placeJoining()
        super.addMediaItem(index, mediaItem)
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        placeJoining()
        super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        placeJoining()
        super.addMediaItems(index, mediaItems)
    }

    override fun removeMediaItem(index: Int) {
        if (index in 0 until player.mediaItemCount) edits?.removing(index, index + 1)
        super.removeMediaItem(index)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        val to = toIndex.coerceAtMost(player.mediaItemCount)
        if (fromIndex in 0 until to) edits?.removing(fromIndex, to)
        super.removeMediaItems(fromIndex, toIndex)
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        val count = player.mediaItemCount
        if (currentIndex in 0 until count && newIndex in 0 until count) edits?.moving(currentIndex, currentIndex + 1, newIndex)
        super.moveMediaItem(currentIndex, newIndex)
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        val count = player.mediaItemCount
        val to = toIndex.coerceAtMost(count)
        if (fromIndex in 0 until to) edits?.moving(fromIndex, to, newIndex.coerceAtMost(count - (to - fromIndex)))
        super.moveMediaItems(fromIndex, toIndex, newIndex)
    }

    /**
     * Replaces the stream's picture of the item [mediaId] with [data] while it is the current one (docs/library.md §13.6),
     * or gives the stream's back with null. Call it on the player's thread.
     */
    fun setArtwork(mediaId: String, data: ByteArray?) {
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
