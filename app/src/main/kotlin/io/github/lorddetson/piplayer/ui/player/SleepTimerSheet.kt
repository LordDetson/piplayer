package io.github.lorddetson.piplayer.ui.player

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.playback.PlaybackExtras
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filter

/**
 * The sleep timer's sheet (docs/playback.md §6.2): wheels of hours and minutes with ready-made times, or the running
 * timer's time left at [nowMs] (elapsed realtime) and a way to cancel it.
 */
@Composable
fun SleepTimerContent(
    extras: PlaybackExtras,
    nowMs: Long,
    onStart: (durationMs: Long, finishTrack: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.player_sleep_timer), style = MaterialTheme.typography.titleLarge)
        val left = extras.sleepLeftMs(nowMs)
        if (left != null) RunningTimer(left, extras.finishTrack, onCancel) else NewTimer(onStart)
    }
}

@Composable
private fun RunningTimer(leftMs: Long, finishTrack: Boolean, onCancel: () -> Unit) {
    Text(PlayerTime.clock(leftMs), style = MaterialTheme.typography.displayLarge)
    if (finishTrack) {
        Text(stringResource(R.string.timer_will_finish_track), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.timer_cancel)) }
}

@Composable
private fun NewTimer(onStart: (durationMs: Long, finishTrack: Boolean) -> Unit) {
    var hours by rememberSaveable { mutableIntStateOf(0) }
    var minutes by rememberSaveable { mutableIntStateOf(DEFAULT_MINUTES) }
    var finishTrack by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]

    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Wheel(hours, 0..23, { hours = it }, stringResource(R.string.timer_hours)) { spoken(it, MeasureUnit.HOUR, locale) }
        Text(":", style = MaterialTheme.typography.displaySmall)
        Wheel(minutes, 0..59, { minutes = it }, stringResource(R.string.timer_minutes)) { spoken(it, MeasureUnit.MINUTE, locale) }
    }
    val selected = TimerPresets.matching(hours, minutes)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (preset in TimerPresets.MINUTES) {
            val words = PlayerTime.spoken(preset * 60_000L, locale)
            FilterChip(
                selected = preset == selected,
                onClick = {
                    hours = preset / 60
                    minutes = preset % 60
                },
                label = { Text(PlayerTime.clock(preset * 60_000L)) },
                modifier = Modifier.semantics {
                    contentDescription = words
                    this.selected = preset == selected
                },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().toggleable(value = finishTrack, role = Role.Checkbox, onValueChange = { finishTrack = it }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = finishTrack, onCheckedChange = null)
        Text(stringResource(R.string.timer_finish_track), modifier = Modifier.padding(start = 12.dp))
    }
    Button(
        onClick = { onStart((hours * 60L + minutes) * 60_000L, finishTrack) },
        enabled = hours > 0 || minutes > 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.timer_start))
    }
}

/**
 * A wheel of the numbers in [range] that turns round in both directions and settles on the middle one. Accessibility
 * services see one adjustable control labelled [label] that states [spoken] of its value.
 */
@Composable
private fun Wheel(value: Int, range: IntRange, onValueChange: (Int) -> Unit, label: String, spoken: (Int) -> String) {
    val count = range.last - range.first + 1
    val middle = Int.MAX_VALUE / 2
    val start = middle - middle % count
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start + value - range.first)
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)

    // The wheel settles on a number: that is the value.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemIndex }
            .filter { (scrolling, _) -> !scrolling }
            .collect { (_, index) ->
                val settled = range.first + index % count
                if (settled != currentValue) change(settled)
            }
    }
    // A value set elsewhere (a chip, an accessibility service) turns the wheel the short way round.
    LaunchedEffect(value) {
        val current = range.first + state.firstVisibleItemIndex % count
        if (current != value && !state.isScrollInProgress) {
            var step = (value - current) % count
            if (abs(step) > count / 2) step -= count * Integer.signum(step)
            state.scrollToItem(state.firstVisibleItemIndex + step)
        }
    }

    Box(
        Modifier
            .width(72.dp)
            .height(ITEM_HEIGHT * 3)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = spoken(value)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    value.toFloat(),
                    range.first.toFloat()..range.last.toFloat(),
                    steps = count - 2,
                )
                setProgress { target ->
                    change(target.roundToInt().coerceIn(range))
                    true
                }
            },
    ) {
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = ITEM_HEIGHT),
        ) {
            items(count = Int.MAX_VALUE) { index ->
                val number = range.first + index % count
                Text(
                    text = "%02d".format(number),
                    style = MaterialTheme.typography.displaySmall,
                    color = if (number == value) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().height(ITEM_HEIGHT),
                )
            }
        }
    }
}

private fun spoken(value: Int, unit: MeasureUnit, locale: Locale): String =
    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).format(Measure(value, unit))

private val ITEM_HEIGHT = 56.dp
private const val DEFAULT_MINUTES = 30
