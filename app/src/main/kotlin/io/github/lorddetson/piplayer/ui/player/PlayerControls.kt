package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.lorddetson.piplayer.R

/*
 * The player's controls (docs/playback.md §3.3, §3.8). Media3's state holders (PlayerStates.kt) follow the player and
 * know which commands it offers; the buttons, icons and labels are PiPlayer's own.
 */

/** Play or pause, labelled with what a tap does; [large] on Now Playing. */
@OptIn(UnstableApi::class)
@Composable
fun PlayPauseButton(player: Player?, modifier: Modifier = Modifier, large: Boolean = false) {
    val state = rememberPlayPauseState(player)
    val label = stringResource(if (state.showPlay) R.string.player_play else R.string.player_pause)
    val icon = painterResource(if (state.showPlay) R.drawable.ic_player_play else R.drawable.ic_player_pause)
    if (large) {
        FilledIconButton(onClick = state::onClick, enabled = state.isEnabled, modifier = modifier.size(72.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(36.dp))
        }
    } else {
        IconButton(onClick = state::onClick, enabled = state.isEnabled, modifier = modifier) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** Back to the start of the song after its first seconds, otherwise to the previous song (§3.6). */
@OptIn(UnstableApi::class)
@Composable
fun PreviousButton(player: Player?, modifier: Modifier = Modifier) {
    val state = rememberPreviousState(player)
    IconButton(onClick = state::onClick, enabled = state.isEnabled, modifier = modifier.size(56.dp)) {
        Icon(painterResource(R.drawable.ic_player_previous), stringResource(R.string.player_previous), Modifier.size(36.dp))
    }
}

@OptIn(UnstableApi::class)
@Composable
fun NextButton(player: Player?, modifier: Modifier = Modifier) {
    val state = rememberNextState(player)
    IconButton(onClick = state::onClick, enabled = state.isEnabled, modifier = modifier.size(56.dp)) {
        Icon(painterResource(R.drawable.ic_player_next), stringResource(R.string.player_next), Modifier.size(36.dp))
    }
}

/** A toggle that states "On" or "Off" rather than "checked". */
@OptIn(UnstableApi::class)
@Composable
fun ShuffleButton(player: Player?, modifier: Modifier = Modifier) {
    val state = rememberShuffleState(player)
    val mode = stringResource(if (state.shuffleOn) R.string.player_state_on else R.string.player_state_off)
    IconToggleButton(
        checked = state.shuffleOn,
        onCheckedChange = { state.onClick() },
        enabled = state.isEnabled,
        modifier = modifier.semantics { stateDescription = mode },
    ) {
        Icon(painterResource(R.drawable.ic_player_shuffle), stringResource(R.string.player_shuffle))
    }
}

/** Goes off, all, one and round again, as the notification's button does (§3.6). */
@OptIn(UnstableApi::class)
@Composable
fun RepeatButton(player: Player?, modifier: Modifier = Modifier) {
    val state = rememberRepeatState(player)
    val (icon, mode) = when (state.repeatModeState) {
        Player.REPEAT_MODE_ALL -> R.drawable.ic_player_repeat to R.string.player_repeat_state_all
        Player.REPEAT_MODE_ONE -> R.drawable.ic_player_repeat_one to R.string.player_repeat_state_one
        else -> R.drawable.ic_player_repeat to R.string.player_state_off
    }
    val modeText = stringResource(mode)
    val action = stringResource(R.string.player_change_repeat_mode)
    IconButton(
        onClick = state::onClick,
        enabled = state.isEnabled,
        // A label for the button's own click action.
        modifier = modifier.semantics {
            stateDescription = modeText
            onClick(label = action, action = null)
        },
    ) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(R.string.player_repeat),
            tint = if (state.repeatModeState == Player.REPEAT_MODE_OFF) LocalContentColor.current else MaterialTheme.colorScheme.primary,
        )
    }
}

/** The cover, decorative, or a placeholder with a music note (§3.4). */
@Composable
fun CoverImage(cover: ImageBitmap?, modifier: Modifier = Modifier, noteSize: Dp = 24.dp) {
    if (cover != null) {
        Image(cover, contentDescription = null, modifier = modifier.testTag(PlayerTags.COVER), contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant).testTag(PlayerTags.COVER_PLACEHOLDER),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_player_cover),
                contentDescription = null,
                modifier = Modifier.size(noteSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
