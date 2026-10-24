package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player

/** When stopping at the end of a song has to catch up (docs/playback.md §6.4). */
object SongEndRules {

    /**
     * Whether a transition for [reason] while the stop at the song's end was set means the end was missed: the setting
     * reaches the playback thread later, and a song that ends first goes on to the next.
     */
    fun missedEnd(reason: Int): Boolean =
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
}
