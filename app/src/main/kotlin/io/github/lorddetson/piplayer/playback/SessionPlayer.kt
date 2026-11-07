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
 * in the stream, so that fields PiPlayer leaves empty do not show raw tags (TI §7.1). The stream's cover stays, unless an
 * own cover or a system thumbnail replaces it ([setArtwork], docs/library.md §13.6).
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

        /**
         * [items] are about to be added at [index] by someone other than the edits themselves: a controller's add
         * (docs/playback.md §10.3).
         */
        fun inserting(index: Int, items: List<MediaItem>) {}
    }

    var edits: Edits? = null

    /**
     * The content URI of the cover of the queue item with a media ID, for controllers in other apps (docs/library.md
     * §13.8.6), or null; asked on the player's thread whenever the metadata is.
     */
    var artworkUriOf: (mediaId: String, picture: ByteArray?) -> android.net.Uri? = { _, _ -> null }

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
        edits?.inserting(player.mediaItemCount, listOf(mediaItem))
        super.addMediaItem(mediaItem)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        placeJoining()
        edits?.inserting(index.coerceIn(0, player.mediaItemCount), listOf(mediaItem))
        super.addMediaItem(index, mediaItem)
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        placeJoining()
        if (mediaItems.isNotEmpty()) edits?.inserting(player.mediaItemCount, mediaItems)
        super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        placeJoining()
        if (mediaItems.isNotEmpty()) edits?.inserting(index.coerceIn(0, player.mediaItemCount), mediaItems)
        super.addMediaItems(index, mediaItems)
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = replaceMediaItems(index, index + 1, listOf(mediaItem))

    /**
     * New metadata for the same entries passes as it is (the refresh of docs/playback.md §2.6). Other entries in place of
     * old ones, as a controller's replace brings them (§10.3), are added after them and the old ones removed, so that the
     * edits see both.
     */
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) {
        val to = toIndex.coerceAtMost(player.mediaItemCount)
        val same = to - fromIndex == mediaItems.size &&
            mediaItems.indices.all { player.getMediaItemAt(fromIndex + it).mediaId == mediaItems[it].mediaId }
        if (edits == null || same || fromIndex !in 0..to) return super.replaceMediaItems(fromIndex, toIndex, mediaItems)
        if (mediaItems.isNotEmpty()) addMediaItems(to, mediaItems)
        if (to > fromIndex) removeMediaItems(fromIndex, to)
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
        val before = if (data == null) artworks.remove(mediaId) else artworks.put(mediaId, data)
        // Only a change of what the current item shows is news to the listeners.
        if (mediaId != currentMediaItem?.mediaId) return
        val metadata = getMediaMetadata()
        // The same picture under the same URI is no news: each change makes the platform session draw its notification
        // again. A new cover URI with the same picture is (library.md §13.8.6).
        if (before.contentEquals(data) && metadata.artworkUri == heardUri) return
        heardUri = metadata.artworkUri
        filtered.keys.toList().forEach { it.onMediaMetadataChanged(metadata) }
    }

    /** The cover URI the listeners heard last for the current item. */
    private var heardUri: android.net.Uri? = null

    /** Forgets the pictures of every item but [mediaId]: own covers changed, so they may be stale. */
    fun keepArtworkOf(mediaId: String?) {
        artworks.keys.retainAll(setOfNotNull(mediaId))
    }

    /** The pictures that replace the stream's, by media ID: the current item's and the last few before it. */
    private val artworks = object : LinkedHashMap<String, ByteArray>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > KEPT_ARTWORKS
    }

    override fun getMediaMetadata(): MediaMetadata = itemMetadata(super.getMediaMetadata())

    override fun addListener(listener: Player.Listener) {
        super.addListener(filtered.getOrPut(listener) { MetadataFilter(listener) })
    }

    override fun removeListener(listener: Player.Listener) {
        filtered.remove(listener)?.let { super.removeListener(it) }
    }

    private fun itemMetadata(stream: MediaMetadata): MediaMetadata {
        val current = currentMediaItem ?: return MediaMetadata.EMPTY
        val item = current.mediaMetadata
        if (item.artworkData != null || item.artworkUri != null) return item
        val own = artworks[current.mediaId]
        val picture = own ?: stream.artworkData
        // The URI names the picture shown too: Media3's bitmap cache matches a URI before the data, so one URI for two
        // pictures would keep the first one (docs/library.md §13.8.6).
        val uri = artworkUriOf(current.mediaId, picture)
        if (uri == null && picture == null) return item
        return item.buildUpon().apply {
            when {
                own != null -> setArtworkData(own, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                picture != null -> setArtworkData(picture, stream.artworkDataType)
            }
            if (uri != null) setArtworkUri(uri)
        }.build()
    }

    /** A listener hears the same as [getMediaMetadata] gives; the session keeps what it hears. */
    private inner class MetadataFilter(listener: Player.Listener) : ForwardingListener(listener) {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val metadata = itemMetadata(mediaMetadata)
            heardUri = metadata.artworkUri
            super.onMediaMetadataChanged(metadata)
        }
    }
}

/** How many items' replacement pictures the session player keeps. */
private const val KEPT_ARTWORKS = 4
