package io.github.lorddetson.piplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Stops playback when the song that plays ends and moves to the next song without playing it (docs/playback.md §6.3,
 * §6.4), for "stop after current track" and for the sleep timer's finish-track mode. ExoPlayer's own setting pauses at
 * the end of every song, so this clears it as soon as it has stopped once; changes of song by hand leave it set.
 * [onChange] hears every change of [stopAfterCurrent].
 */
@OptIn(UnstableApi::class)
class EndOfSongPause(private val player: ExoPlayer, private val onChange: () -> Unit) : Player.Listener {

    var stopAfterCurrent = false
        private set

    private var forTimer = false

    /** Turns "stop after current track" on, while there is a current song, or off. */
    fun setStopAfterCurrent(enabled: Boolean) {
        val allowed = !enabled || (player.currentMediaItem != null && player.playbackState != Player.STATE_ENDED)
        if (!allowed || enabled == stopAfterCurrent) return
        stopAfterCurrent = enabled
        apply()
        onChange()
    }

    /** The sleep timer's finish-track mode; it outlasts changes of song, so the song playing when it is due finishes. */
    fun setForTimer(enabled: Boolean) {
        forTimer = enabled
        apply()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
        clear()
        // The song repeat and shuffle would play next; none after the last song with repeat off, which stays at its end.
        val next = player.currentTimeline.getNextWindowIndex(player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled)
        if (next != C.INDEX_UNSET) player.seekTo(next, 0)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if ((stopAfterCurrent || forTimer) && SongEndRules.missedEnd(reason)) {
            // The song ended before the player had the setting: stop at the start of the next one instead.
            clear()
            player.pause()
            player.seekTo(player.currentMediaItemIndex, 0)
        }
    }

    private fun clear() {
        val changed = stopAfterCurrent
        stopAfterCurrent = false
        forTimer = false
        apply()
        if (changed) onChange()
    }

    private fun apply() {
        val pause = stopAfterCurrent || forTimer
        if (player.pauseAtEndOfMediaItems != pause) player.pauseAtEndOfMediaItems = pause
    }
}
