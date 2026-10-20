package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.CurrentMediaItemState
import androidx.media3.ui.compose.state.NextButtonState
import androidx.media3.ui.compose.state.PlayPauseButtonState
import androidx.media3.ui.compose.state.PreviousButtonState
import androidx.media3.ui.compose.state.ProgressStateWithTickCount
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import androidx.media3.ui.compose.state.RepeatButtonState
import androidx.media3.ui.compose.state.ShuffleButtonState
import io.github.lorddetson.piplayer.playback.RepeatModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Media3's state holders for the player's screens (docs/playback.md §3.3), observing the player on the main thread
 * whatever dispatcher runs the composition. A player may only be called on its application thread, and Media3's own
 * remember functions observe it in the composition's coroutines: when those resume elsewhere, as under Compose's test
 * dispatcher, the holders call the player from the wrong thread when they stop.
 */

@OptIn(UnstableApi::class)
@Composable
fun rememberPlayPauseState(player: Player?): PlayPauseButtonState =
    rememberObserved(player, ::PlayPauseButtonState) { observe() }

@OptIn(UnstableApi::class)
@Composable
fun rememberPreviousState(player: Player?): PreviousButtonState =
    rememberObserved(player, ::PreviousButtonState) { observe() }

@OptIn(UnstableApi::class)
@Composable
fun rememberNextState(player: Player?): NextButtonState = rememberObserved(player, ::NextButtonState) { observe() }

@OptIn(UnstableApi::class)
@Composable
fun rememberShuffleState(player: Player?): ShuffleButtonState =
    rememberObserved(player, ::ShuffleButtonState) { observe() }

/** Repeat goes round [RepeatModes.CYCLE], as the notification's button does (§3.6). */
@OptIn(UnstableApi::class)
@Composable
fun rememberRepeatState(player: Player?): RepeatButtonState =
    rememberObserved(player, { RepeatButtonState(it, RepeatModes.CYCLE) }) { observe() }

@OptIn(UnstableApi::class)
@Composable
fun rememberCurrentItemState(player: Player?): CurrentMediaItemState =
    rememberObserved(player, ::CurrentMediaItemState) { observe() }

/** The position, in whole steps of [tickIntervalMs] of the song. */
@OptIn(UnstableApi::class)
@Composable
fun rememberProgressInterval(player: Player?, tickIntervalMs: Long): ProgressStateWithTickInterval {
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    return rememberObserved(player, { ProgressStateWithTickInterval(it, tickIntervalMs, scope) }) { observe() }
}

/** The position as a fraction of the song, in [totalTickCount] steps. */
@OptIn(UnstableApi::class)
@Composable
fun rememberProgressTicks(player: Player?, totalTickCount: Int): ProgressStateWithTickCount {
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val state = rememberObserved(player, { ProgressStateWithTickCount(it, totalTickCount, scope) }) { observe() }
    LaunchedEffect(state, totalTickCount) {
        withContext(Dispatchers.Main.immediate) { state.updateTotalTickCount(totalTickCount) }
    }
    return state
}

@Composable
private fun <S : Any> rememberObserved(player: Player?, create: (Player?) -> S, observe: suspend S.() -> Unit): S {
    val state = remember(player) { create(player) }
    LaunchedEffect(state) {
        withContext(Dispatchers.Main.immediate) { state.observe() }
    }
    return state
}
