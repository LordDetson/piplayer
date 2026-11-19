package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player

internal suspend fun writeTagsWithPlaybackPaused(player: Player, mediaId: String, write: suspend () -> Unit) {
    if (player.currentMediaItem?.mediaId != mediaId) return
    val index = player.currentMediaItemIndex
    val position = player.currentPosition
    val resume = player.playWhenReady
    player.pause()
    player.stop()
    try {
        write()
    } finally {
        if (player.currentMediaItem?.mediaId == mediaId && player.currentMediaItemIndex == index &&
            player.playbackState == Player.STATE_IDLE && !player.playWhenReady
        ) {
            player.prepare()
            player.seekTo(position)
            if (resume) player.play()
        }
    }
}
