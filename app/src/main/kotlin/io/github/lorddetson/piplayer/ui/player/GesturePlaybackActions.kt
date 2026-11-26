package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import io.github.lorddetson.piplayer.playback.RepeatModes
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId

/** Handles the common playback actions that can be assigned to a themed gesture. */
@OptIn(UnstableApi::class)
internal fun performPlaybackGesture(
    action: UiGestureAction,
    player: Player?,
    onOpenQueue: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onOpenSleepTimer: (() -> Unit)? = null,
): Boolean {
    val screenAction = when (action.id) {
        UiGestureActionId.OPEN_QUEUE -> onOpenQueue
        UiGestureActionId.TOGGLE_FAVORITE -> onToggleFavorite
        UiGestureActionId.SLEEP_TIMER -> onOpenSleepTimer
        else -> null
    }
    if (screenAction != null) {
        screenAction()
        return true
    }
    if (player == null) return false
    return when (action.id) {
        UiGestureActionId.NEXT_TRACK -> player.seekToNeighbor(next = true)
        UiGestureActionId.PREVIOUS_TRACK -> player.seekToNeighbor(next = false)
        UiGestureActionId.PLAY_PAUSE ->
            player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) && Util.handlePlayPauseButtonAction(player)
        UiGestureActionId.VOLUME_UP -> player.changeVolume(0.1f)
        UiGestureActionId.VOLUME_DOWN -> player.changeVolume(-0.1f)
        UiGestureActionId.SEEK_FORWARD -> player.seekBy((action.amountSeconds ?: 10).toLong() * 1_000L)
        UiGestureActionId.SEEK_BACKWARD -> player.seekBy((action.amountSeconds ?: 10).toLong() * -1_000L)
        UiGestureActionId.SPEED_UP -> player.changeSpeed { it + 0.1f }
        UiGestureActionId.SPEED_DOWN -> player.changeSpeed { it - 0.1f }
        UiGestureActionId.SPEED_RESET -> player.changeSpeed { 1f }
        UiGestureActionId.CYCLE_REPEAT -> player.withCommand(Player.COMMAND_SET_REPEAT_MODE) {
            repeatMode = RepeatModes.next(repeatMode)
        }
        UiGestureActionId.TOGGLE_SHUFFLE -> player.withCommand(Player.COMMAND_SET_SHUFFLE_MODE) {
            shuffleModeEnabled = !shuffleModeEnabled
        }
        UiGestureActionId.OPEN_QUEUE, UiGestureActionId.TOGGLE_FAVORITE, UiGestureActionId.SLEEP_TIMER,
        UiGestureActionId.STOCK_CLICK, UiGestureActionId.CONTEXT_MENU -> false
    }
}

private fun Player.seekToNeighbor(next: Boolean): Boolean {
    val command = if (next) Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM else Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
    if (!isCommandAvailable(command) || !isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return false
    val destination = if (next) nextMediaItemIndex else previousMediaItemIndex
    if (destination == C.INDEX_UNSET || destination == currentMediaItemIndex) return false
    if (next) seekToNextMediaItem() else seekToPreviousMediaItem()
    return true
}

private fun Player.seekBy(offsetMs: Long): Boolean {
    if (!isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) ||
        !isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !isCurrentMediaItemSeekable
    ) return false
    val upperBound = duration.takeIf { it >= 0L } ?: Long.MAX_VALUE
    val destination = currentPosition.coerceIn(0L, upperBound).let { position ->
        if (offsetMs > upperBound - position) upperBound else (position + offsetMs).coerceAtLeast(0L)
    }
    seekTo(destination)
    return true
}

private fun Player.changeVolume(delta: Float): Boolean {
    if (!isCommandAvailable(Player.COMMAND_GET_VOLUME)) return false
    return withCommand(Player.COMMAND_SET_VOLUME) { volume = (volume + delta).coerceIn(0f, 1f) }
}

private fun Player.changeSpeed(adjust: (Float) -> Float): Boolean =
    withCommand(Player.COMMAND_SET_SPEED_AND_PITCH) {
        setPlaybackSpeed(adjust(playbackParameters.speed).coerceIn(0.25f, 3f))
    }

private inline fun Player.withCommand(command: Int, apply: Player.() -> Unit): Boolean {
    if (!isCommandAvailable(command)) return false
    apply()
    return true
}
