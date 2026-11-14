package io.github.lorddetson.piplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * Checks a restored start against the length the player reads from the file itself (docs/playback.md §4.3.1): when the
 * restored position turns out to be at the end of the song, because the queue item's length was unknown or wrong, the
 * player moves on at once to what plays after it, so that it neither ends nor stays buffering there. It checks once, as
 * soon as the player knows the restored item's length; a seek, another item or another queue before then drops it. Lives
 * on the main thread with the player.
 */
class RestoredEndCheck(
    /** The restored item moves back to its own start: its playback ends and a new one starts (§11.1). */
    private val onReplay: () -> Unit = {},
) : Player.Listener {

    private var player: Player? = null
    private var mediaId: String? = null
    private var index = 0
    private var positionMs = 0L
    private var current: () -> Boolean = { false }

    /**
     * Checks the start at [index] and [positionMs], whose item is [mediaId], once [player] knows its length. The player
     * may still be empty, as when Media3 sets the restored items itself after a play request. The check is dropped once
     * [current] is false: another queue or restore came.
     */
    fun watch(player: Player, mediaId: String, index: Int, positionMs: Long, current: () -> Boolean = { true }) {
        cancel()
        // The start of a song is never its end.
        if (positionMs <= 0) return
        this.player = player
        this.mediaId = mediaId
        this.index = index
        this.positionMs = positionMs
        this.current = current
        player.addListener(this)
        check()
    }

    /** Drops the check; a newer queue or restore needs none of it. */
    fun cancel() {
        player?.removeListener(this)
        player = null
        mediaId = null
    }

    override fun onEvents(player: Player, events: Player.Events) = check()

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // The user sought before the length was known: the restored position no longer matters.
        if (reason == Player.DISCONTINUITY_REASON_SEEK) cancel()
    }

    private fun check() {
        val player = player ?: return
        if (!current()) {
            cancel()
            return
        }
        // Media3 has not set the restored items yet.
        if (player.mediaItemCount == 0) return
        if (player.currentMediaItemIndex != index || player.currentMediaItem?.mediaId != mediaId) {
            cancel()
            return
        }
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        cancel()
        if (!QueueRestore.isAtEnd(positionMs, duration)) return
        val next = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> index
            else -> player.currentTimeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
                .takeIf { it != C.INDEX_UNSET } ?: index
        }
        if (next == index) onReplay()
        player.seekTo(next, 0)
    }
}
