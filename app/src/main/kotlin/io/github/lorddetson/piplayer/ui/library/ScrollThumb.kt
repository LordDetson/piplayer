package io.github.lorddetson.piplayer.ui.library

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The scroll thumb of long lists (docs/library.md §11.2). */
object ScrollThumbTags {
    const val THUMB = "scroll_thumb"
    const val BUBBLE = "scroll_thumb_bubble"
}

/** What the thumb reads of a lazy list or grid and how it moves it. */
private class ThumbTarget(
    val total: () -> Int,
    val visible: () -> Int,
    val first: () -> Int,
    val scrolling: () -> Boolean,
    val scrollTo: suspend (Int) -> Unit,
)

/** The thumb over a lazy list; [label] is the bubble's text for an item index, null for none. Put it in a box over the list. */
@Composable
fun ScrollThumb(state: LazyListState, label: (Int) -> String?, modifier: Modifier = Modifier) {
    val target = remember(state) {
        ThumbTarget(
            total = { state.layoutInfo.totalItemsCount },
            visible = { state.layoutInfo.visibleItemsInfo.size },
            first = { state.firstVisibleItemIndex },
            scrolling = { state.isScrollInProgress },
            scrollTo = { state.scrollToItem(it) },
        )
    }
    Thumb(target, label, modifier)
}

/** The thumb over a lazy grid, as for a list. */
@Composable
fun ScrollThumb(state: LazyGridState, label: (Int) -> String?, modifier: Modifier = Modifier) {
    val target = remember(state) {
        ThumbTarget(
            total = { state.layoutInfo.totalItemsCount },
            visible = { state.layoutInfo.visibleItemsInfo.size },
            first = { state.firstVisibleItemIndex },
            scrolling = { state.isScrollInProgress },
            scrollTo = { state.scrollToItem(it) },
        )
    }
    Thumb(target, label, modifier)
}

/** A list is long when it holds more than this many screens, and stops being long below [SHORT_LIST_SCREENS]. */
private const val LONG_LIST_SCREENS = 5
private const val SHORT_LIST_SCREENS = 4

/** How long the thumb stays after the last scroll or drag. */
private const val HIDE_AFTER_MS = 1_500L

private val THUMB_HEIGHT = 48.dp
private val THUMB_WIDTH = 8.dp
private val TOUCH_WIDTH = 32.dp
private val BUBBLE_SIZE = 56.dp
private val BUBBLE_GAP = 16.dp

@Composable
private fun Thumb(target: ThumbTarget, label: (Int) -> String?, modifier: Modifier) {
    var dragging by remember { mutableStateOf(false) }
    // Read in snapshots, not in composition, so that a scroll frame recomposes nothing. Partly visible rows make the count
    // of visible items move by one while scrolling, so a list stops being long only well below the limit.
    var long by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        snapshotFlow { target.total() to target.visible() }.collect { (total, visible) ->
            long = visible > 0 && total > visible * (if (long) SHORT_LIST_SCREENS else LONG_LIST_SCREENS)
        }
    }
    if (!long && !dragging) return

    val scope = rememberCoroutineScope()
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragItem by remember { mutableIntStateOf(0) }
    val scrolling by remember(target) { derivedStateOf { target.scrolling() } }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            shown = true
        } else {
            delay(HIDE_AFTER_MS)
            shown = false
        }
    }
    val description = stringResource(R.string.scroll_thumb)

    BoxWithConstraints(modifier.fillMaxHeight().width(TOUCH_WIDTH)) {
        val density = LocalDensity.current
        val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
        val track = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)

        /** The last item the list can show at its top: the thumb's bottom, at rest and while dragging. */
        fun lastTop() = (target.total() - target.visible()).coerceAtLeast(1)

        /** Where the thumb is; read while placing, so that scrolling only places it again. */
        fun thumbY() = if (dragging) dragY else track * (target.first().toFloat() / lastTop()).coerceIn(0f, 1f)

        AnimatedVisibility(visible = shown, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxHeight().width(TOUCH_WIDTH)) {
                if (dragging) {
                    label(dragItem)?.let { text ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                // Beside the thumb, taking no width of its own, so that the thumb does not move.
                                .layout { measurable, constraints ->
                                    val bubble = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity, minHeight = 0))
                                    layout(0, 0) {
                                        val y = thumbY() + thumbPx / 2 - bubble.height / 2
                                        bubble.place(-bubble.width - BUBBLE_GAP.roundToPx(), y.roundToInt())
                                    }
                                }
                                .sizeIn(minWidth = BUBBLE_SIZE, minHeight = BUBBLE_SIZE)
                                // One node with its text, as TalkBack reads it.
                                .semantics(mergeDescendants = true) {}
                                .testTag(ScrollThumbTags.BUBBLE),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.sizeIn(minWidth = BUBBLE_SIZE, minHeight = BUBBLE_SIZE)) {
                                Text(
                                    text,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset { IntOffset(0, thumbY().roundToInt()) }
                        .size(TOUCH_WIDTH, THUMB_HEIGHT)
                        .testTag(ScrollThumbTags.THUMB)
                        .semantics { contentDescription = description }
                        .pointerInput(target, track) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragY = track * (target.first().toFloat() / lastTop()).coerceIn(0f, 1f)
                                    dragItem = target.first()
                                    dragging = true
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, amount ->
                                change.consume()
                                dragY = (dragY + amount).coerceIn(0f, track)
                                // The same mapping as at rest: the thumb's place is the item at the top.
                                val item = ((dragY / track) * lastTop()).roundToInt().coerceIn(0, (target.total() - 1).coerceAtLeast(0))
                                dragItem = item
                                scope.launch { target.scrollTo(item) }
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .size(THUMB_WIDTH, THUMB_HEIGHT)
                            .background(
                                if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}
