package io.github.lorddetson.piplayer.ui.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import kotlinx.coroutines.delay

/**
 * Now Playing's second row (docs/playback.md §6.1): the sleep timer with its time left, "stop after current track" and
 * the Equalizer, which shows only when the device has an effect panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraControlsRow(controls: ExtraControls, modifier: Modifier = Modifier) {
    val deadline = controls.extras.sleepDeadlineMs
    // The time left changes once a minute; looking every second keeps it on the minute.
    val now by produceState(SystemClock.elapsedRealtime(), deadline) {
        while (deadline != null) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    var timerOpen by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val timerLabel = stringResource(R.string.player_sleep_timer)
    val left = controls.extras.sleepLeftMs(now)
    val leftText = left?.let { stringResource(R.string.player_sleep_timer_left, PlayerTime.spoken(PlayerTime.wholeMinutes(it), locale)) }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { timerOpen = true },
            modifier = Modifier.semantics {
                contentDescription = timerLabel
                leftText?.let { stateDescription = it }
            },
        ) {
            Icon(painterResource(R.drawable.ic_player_timer), contentDescription = null)
            if (left != null) Text(PlayerTime.clock(left), modifier = Modifier.padding(start = 8.dp))
        }
        val stopAfterState = stringResource(if (controls.extras.stopAfterCurrent) R.string.player_state_on else R.string.player_state_off)
        IconToggleButton(
            checked = controls.extras.stopAfterCurrent,
            onCheckedChange = controls.setStopAfterCurrent,
            modifier = Modifier.semantics { stateDescription = stopAfterState },
        ) {
            Icon(painterResource(R.drawable.ic_player_stop_after), stringResource(R.string.player_stop_after_current))
        }
        controls.openEqualizer?.let { open ->
            IconButton(onClick = open) {
                Icon(painterResource(R.drawable.ic_player_equalizer), stringResource(R.string.player_equalizer))
            }
        }
    }

    if (timerOpen) {
        ModalBottomSheet(onDismissRequest = { timerOpen = false }) {
            SleepTimerContent(
                extras = controls.extras,
                nowMs = now,
                onStart = { duration, finishTrack ->
                    controls.setSleepTimer(duration, finishTrack)
                    timerOpen = false
                },
                // The sheet stays open with the wheels, so that a new timer can be set at once (§6.2).
                onCancel = controls.cancelSleepTimer,
            )
        }
    }
}
