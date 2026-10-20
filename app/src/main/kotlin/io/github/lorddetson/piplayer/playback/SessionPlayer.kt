package io.github.lorddetson.piplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * The player the session wraps, so that every controller turns shuffle on the same way: the app, the notification,
 * the system, Bluetooth and other apps (docs/playback.md §3.6). Shuffle turned on in the middle of a queue starts at
 * the current song; a new queue gets its start song first from [StartFirstShuffleOrder] itself.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(private val player: ExoPlayer) : ForwardingPlayer(player) {

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
            player.setShuffleOrder(order.startingAt(player.mediaItemCount, player.currentMediaItemIndex))
        }
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }
}
