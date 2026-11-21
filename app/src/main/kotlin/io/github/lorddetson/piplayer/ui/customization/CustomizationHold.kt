package io.github.lorddetson.piplayer.ui.customization

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.lorddetson.piplayer.R

/** Observe the hold before the child button or slider sees it. Leave taps and drags to that child. */
@Composable
internal fun Modifier.customizeOnHold(onHold: () -> Unit): Modifier {
    val label = stringResource(R.string.player_element_customize)
    val haptic = LocalHapticFeedback.current
    val open by rememberUpdatedState {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onHold()
    }
    return semantics { onLongClick(label) { open(); true } }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val pointer = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull()
                    if (pointer == null || pointer.id != down.id || !pointer.pressed ||
                        (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                false
            } ?: true
            if (held) {
                open()
                do {
                    val changes = awaitPointerEvent(PointerEventPass.Initial).changes
                    changes.forEach { it.consume() }
                } while (changes.any { it.pressed })
            }
        }
    }
}
