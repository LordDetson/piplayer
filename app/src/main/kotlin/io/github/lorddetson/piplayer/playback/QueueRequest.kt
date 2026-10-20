package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.songs.Song

/**
 * A compact queue request (docs/playback.md §2.3): play the song list from [startSongId], found at [startRow] when the
 * list was shown. It travels as the media ID of one media item, so Media3 controllers and the platform's
 * play-from-media-ID requests use one form.
 */
data class QueueRequest(val startSongId: Long, val startRow: Int) {

    init {
        require(startRow >= 0) { "startRow must not be negative" }
    }

    val mediaId: String get() = "$PREFIX$startSongId&index=$startRow"

    companion object {
        /** Version 1, the song list, by title. Other lists and orders arrive with the library (M2a). */
        private const val PREFIX = "q1/songs?sort=title&start="
        private val FORM = Regex("""q1/songs\?sort=title&start=(-?\d{1,19})&index=(\d{1,9})""")

        /** The request [mediaId] names, or null if it names none. */
        fun parse(mediaId: String): QueueRequest? {
            val match = FORM.matchEntire(mediaId) ?: return null
            val id = match.groupValues[1].toLongOrNull() ?: return null
            return QueueRequest(id, match.groupValues[2].toInt())
        }

        /** Where the queue of [songs] starts for [request]: the start song, else its row, else -1 for an empty list. */
        fun startIndex(songs: List<Song>, request: QueueRequest): Int {
            if (songs.isEmpty()) return -1
            val index = songs.indexOfFirst { it.id == request.startSongId }
            return if (index >= 0) index else request.startRow.coerceAtMost(songs.lastIndex)
        }
    }
}
