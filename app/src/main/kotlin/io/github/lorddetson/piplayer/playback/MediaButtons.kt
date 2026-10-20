package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import io.github.lorddetson.piplayer.R

/**
 * The shuffle and repeat buttons of the notification and the system media controls, after previous, play/pause and
 * next (docs/playback.md §3.7). Their icons and names show the current mode; a tap sets the next one. They are player
 * commands, so the session runs them on its player with no command of PiPlayer's own.
 */
@OptIn(UnstableApi::class)
object MediaButtons {

    fun of(context: Context, shuffle: Boolean, repeatMode: Int): List<CommandButton> = listOf(
        CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            // The new state rather than a toggle, so that a tap on a button that is out of date does no harm.
            .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, !shuffle)
            .setDisplayName(context.getString(if (shuffle) R.string.playback_shuffle_on else R.string.playback_shuffle_off))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        // Media3 drops a repeat button without the mode to set.
        CommandButton.Builder(repeatIcon(repeatMode))
            .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, RepeatModes.next(repeatMode))
            .setDisplayName(context.getString(repeatName(repeatMode)))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    /** A listener that calls [update] with new buttons whenever shuffle or repeat changes. */
    fun listener(context: Context, update: (List<CommandButton>) -> Unit) = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                update(of(context, player.shuffleModeEnabled, player.repeatMode))
            }
        }
    }

    private fun repeatIcon(mode: Int) = when (mode) {
        Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
        Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
        else -> CommandButton.ICON_REPEAT_OFF
    }

    private fun repeatName(mode: Int) = when (mode) {
        Player.REPEAT_MODE_ALL -> R.string.playback_repeat_all
        Player.REPEAT_MODE_ONE -> R.string.playback_repeat_one
        else -> R.string.playback_repeat_off
    }
}
