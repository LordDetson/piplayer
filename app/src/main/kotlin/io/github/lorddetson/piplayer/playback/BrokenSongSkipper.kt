package io.github.lorddetson.piplayer.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks

/**
 * Moves on from songs that cannot be played and stops playback after a streak of them (docs/playback.md §2.4). It is
 * added to the player before the session, so that it acts on an error before the session passes it on. [onStop] gets
 * the number of songs that failed in a row and the kind of the last failure.
 */
class BrokenSongSkipper(
    private val player: Player,
    private val onStop: (failures: Int, kind: FailureKind) -> Unit,
) : Player.Listener {

    private val streak = FailureStreak()

    override fun onPlayerError(error: PlaybackException) = failed(PlaybackFailures.kind(error.errorCode, error.cause))

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_READY) return
        if (hasNoAudio(player.currentTracks)) failed(FailureKind.SONG) else streak.opened()
    }

    /** A song reached without a new preparation, at a gapless transition, gets its tracks here. */
    override fun onTracksChanged(tracks: Tracks) {
        if (player.playbackState == Player.STATE_READY && hasNoAudio(tracks)) failed(FailureKind.SONG)
    }

    /**
     * A new queue starts a new count. Media3 reports a new shuffle order as a change of the playlist too, but the
     * current song stays: only a new current item from a playlist change counts (§3.6).
     */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) streak.opened()
    }

    /**
     * A song whose tracks are known but hold no audio that can be played: no decoder for its codec, or an image under
     * an audio name. Media3 raises no error for it and would play silence.
     */
    private fun hasNoAudio(tracks: Tracks) =
        !tracks.isEmpty && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, /* allowExceedsCapabilities= */ true)

    /**
     * Moves on, or stops. A stop keeps the item and its error (or, for a song with no audio, makes the player prepare it
     * again on play, so that the check runs again), and a retry starts a new count.
     */
    private fun failed(kind: FailureKind) {
        if (kind == FailureKind.SONG && streak.failed(player.mediaItemCount) && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            return
        }
        val failures = streak.count
        streak.opened()
        player.playWhenReady = false
        if (player.playerError == null) player.stop()
        onStop(failures, kind)
    }
}
