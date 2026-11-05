package io.github.lorddetson.piplayer.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.github.lorddetson.piplayer.library.songs.Song

/**
 * Keeps the playing and the next queue item up to the library's merged values while the tag pass changes them (docs/
 * playback.md §2.6). Only those two: nothing else shows queue items yet, and the other items of a queue of 20 000 would
 * each cost the session a new timeline. The next one comes up to date on each transition. [find] gives a song by its
 * index ID, and null for what the library does not hold, such as a file opened from another app.
 */
class SessionItemRefresher(private val player: Player, private val find: (Long) -> Song?) {

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
    }

    /** From now on, each transition refreshes too. */
    fun start() = player.addListener(listener)

    fun stop() = player.removeListener(listener)

    fun refresh() {
        val current = player.currentMediaItemIndex
        refreshAt(current)
        val next = player.nextMediaItemIndex
        if (next != C.INDEX_UNSET && next != current) refreshAt(next)
    }

    private fun refreshAt(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        val item = player.getMediaItemAt(index)
        val song = item.mediaId.toLongOrNull()?.let(find) ?: return
        if (item.mediaMetadata.shows(song)) return
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(songMetadata(song)).build())
    }

    private fun MediaMetadata.shows(song: Song) =
        title?.toString() == song.title && artist?.toString() == song.artist && albumTitle?.toString() == song.album &&
            durationMs == song.durationMs

    companion object {
        /** What a queue item shows of a song. No artwork, which the session would publish to other apps. */
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
