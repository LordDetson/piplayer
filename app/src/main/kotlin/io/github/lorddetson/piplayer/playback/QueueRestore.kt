package io.github.lorddetson.piplayer.playback

/** Where a restored queue starts and how far into that song (docs/playback.md §4.3). */
data class QueueStart(val index: Int, val positionMs: Long)

/** Which song of the list the saved queue resumes at. */
object QueueRestore {

    /**
     * The saved song by its ID keeps the saved position. If it is gone, the saved row is used with position 0: the
     * row's song is not the saved song, so a position could belong to another file. Null for an empty list.
     */
    fun choose(songIds: List<Long>, snapshot: PlaybackSnapshot): QueueStart? {
        if (songIds.isEmpty()) return null
        val exact = songIds.indexOf(snapshot.songId)
        return if (exact >= 0) {
            QueueStart(exact, snapshot.positionMs)
        } else {
            QueueStart(snapshot.row.coerceIn(0, songIds.lastIndex), 0)
        }
    }
}
