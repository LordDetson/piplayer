package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player

/** When "stop after current track" turns itself off (docs/playback.md §6.4). */
object SongEndRules {

    /**
     * Whether a media item transition for [reason] is a change of song by hand: a skip, a new queue, an opened file, or
     * a broken song skipped (the skipper seeks too).
     */
    fun clearsStopAfterCurrent(reason: Int): Boolean =
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED

    /**
     * Whether a transition for [reason] while the pause at the song's end was set means the end was missed: the pause is
     * set on the playback thread, and a song that ends first goes on to the next.
     */
    fun missedEnd(reason: Int): Boolean =
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
}
