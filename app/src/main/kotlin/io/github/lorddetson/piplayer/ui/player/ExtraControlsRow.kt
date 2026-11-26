package io.github.lorddetson.piplayer.ui.player

import android.os.SystemClock
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.ui.customization.UiControlColor
import io.github.lorddetson.piplayer.ui.customization.UiControlTextAlign
import androidx.compose.ui.text.style.TextAlign
import io.github.lorddetson.piplayer.ui.customization.UiControlIcon
import kotlinx.coroutines.delay

/** The sleep timer shows the time left and opens its controls (docs/playback.md §6.1). */
@Composable
internal fun SleepTimerButton(controls: ExtraControls, modifier: Modifier, onOpen: () -> Unit) {
    val now = rememberTimerTime(controls.extras.sleepDeadlineMs)
    val locale = LocalConfiguration.current.locales[0]
    val timerLabel = stringResource(R.string.player_sleep_timer)
    val left = controls.extras.sleepLeftMs(now)
    val leftText = left?.let { stringResource(R.string.player_sleep_timer_left, PlayerTime.spoken(PlayerTime.wholeMinutes(it), locale)) }

    TextButton(
        onClick = onOpen,
        modifier = modifier.semantics {
            contentDescription = timerLabel
            leftText?.let { stateDescription = it }
        },
    ) {
        UiControlIcon(painterResource(R.drawable.ic_player_timer), contentDescription = null)
        if (left != null) {
            val alignment = UiControlTextAlign()
            val labelWidth = if (alignment == TextAlign.Unspecified) Modifier else Modifier.weight(1f)
            Text(PlayerTime.clock(left), modifier = labelWidth.padding(start = 8.dp),
                color = UiControlColor(Color.Unspecified), textAlign = alignment)
        }
    }
}

@Composable
private fun rememberTimerTime(deadline: Long?): Long {
    // The time left changes once a minute; looking every second keeps it on the minute.
    val now by produceState(SystemClock.elapsedRealtime(), deadline) {
        while (deadline != null) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerSheet(controls: ExtraControls, onDismiss: () -> Unit) {
    val now = rememberTimerTime(controls.extras.sleepDeadlineMs)
    // Fully open from the start: a half-open sheet would follow the wheels' drags up, then drop back.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SleepTimerContent(
            extras = controls.extras,
            nowMs = now,
            choice = controls.timerChoice,
            onChoiceChange = controls.saveTimerChoice,
            onStart = { duration, finishTrack ->
                controls.setSleepTimer(duration, finishTrack)
                onDismiss()
            },
            // The sheet stays open with the wheels, so that a new timer can be set at once (§6.2).
            onCancel = controls.cancelSleepTimer,
        )
    }
}

@Composable
internal fun StopAfterCurrentButton(controls: ExtraControls, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val stopAfterState = stringResource(if (controls.extras.stopAfterCurrent) R.string.player_state_on else R.string.player_state_off)
    IconToggleButton(
        checked = controls.extras.stopAfterCurrent,
        onCheckedChange = { if (onClick == null) controls.setStopAfterCurrent(it) else onClick() },
        modifier = modifier.semantics { stateDescription = stopAfterState },
    ) {
        UiControlIcon(painterResource(R.drawable.ic_player_stop_after), stringResource(R.string.player_stop_after_current))
    }
}

@Composable
internal fun EqualizerButton(open: () -> Unit, modifier: Modifier) {
    IconButton(onClick = open, modifier = modifier) {
        UiControlIcon(painterResource(R.drawable.ic_player_equalizer), stringResource(R.string.player_equalizer))
    }
}
