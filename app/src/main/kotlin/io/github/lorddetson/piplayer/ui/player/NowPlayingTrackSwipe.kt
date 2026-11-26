package io.github.lorddetson.piplayer.ui.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class TrackSwipeDirection(val incomingSide: Float) { PREVIOUS(-1f), NEXT(1f) }

internal data class TrackSwipeEntry(val index: Int, val item: MediaItem)

internal data class TrackSwipeNeighbors(
    val revision: Int,
    val occurrence: Int,
    val currentIndex: Int,
    val previous: TrackSwipeEntry?,
    val next: TrackSwipeEntry?,
) {
    fun adjacent(direction: TrackSwipeDirection) = when (direction) {
        TrackSwipeDirection.PREVIOUS -> previous
        TrackSwipeDirection.NEXT -> next
    }
}

@OptIn(UnstableApi::class)
internal class NowPlayingTrackSwipe(private val player: Player?) : Player.Listener {
    data class Transition(val direction: TrackSwipeDirection, val sequence: Int)
    private data class Skip(
        val direction: TrackSwipeDirection, val index: Int, val mediaId: String, val deadlineMs: Long, val animate: Boolean,
    )

    private var pending: Skip? = null
    private var sequence = 0
    private var timelineRevision = 0
    private var trackOccurrence = 0
    private var guardedDown: PointerInputChange? = null
    var neighbors by mutableStateOf(readNeighbors())
        private set
    var transition by mutableStateOf<Transition?>(null)
        private set

    fun guard(down: PointerInputChange) {
        guardedDown = down
    }

    fun isGuarded(down: PointerInputChange): Boolean =
        guardedDown?.let { it.id == down.id && it.uptimeMillis == down.uptimeMillis } == true

    fun skip(direction: TrackSwipeDirection, expected: TrackSwipeNeighbors? = null): Boolean {
        val target = player ?: return false
        val now = SystemClock.uptimeMillis()
        if ((pending?.deadlineMs ?: 0) > now) return false
        val current = readNeighbors() ?: return false
        if (expected != null && current != expected) return false
        val destination = current.adjacent(direction) ?: return false
        // The preview is already in place for a cover swipe. Only the non-cover fallback needs an entrance animation.
        pending = Skip(direction, destination.index, destination.item.mediaId, now + 1_400, animate = expected == null)
        when (direction) {
            TrackSwipeDirection.PREVIOUS -> target.seekToPreviousMediaItem()
            TrackSwipeDirection.NEXT -> target.seekToNextMediaItem()
        }
        return true
    }

    private fun readNeighbors(): TrackSwipeNeighbors? {
        val target = player ?: return null
        if (!target.isCommandAvailable(Player.COMMAND_GET_TIMELINE) || target.mediaItemCount == 0) return null
        val current = target.currentMediaItemIndex
        fun entry(command: Int, index: Int) = if (target.isCommandAvailable(command) &&
            index != C.INDEX_UNSET && index != current
        ) TrackSwipeEntry(index, target.getMediaItemAt(index)) else null
        return TrackSwipeNeighbors(
            timelineRevision,
            trackOccurrence,
            current,
            entry(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, target.previousMediaItemIndex),
            entry(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, target.nextMediaItemIndex),
        )
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) timelineRevision++
        if (events.containsAny(
                Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
            )
        ) {
            neighbors = readNeighbors()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        trackOccurrence++
        val skip = pending ?: return
        pending = null
        if (skip.animate && player?.currentMediaItemIndex == skip.index && mediaItem?.mediaId == skip.mediaId &&
            SystemClock.uptimeMillis() <= skip.deadlineMs
        ) {
            transition = Transition(skip.direction, ++sequence)
        }
    }
}

@Composable
internal fun rememberNowPlayingTrackSwipe(player: Player?): NowPlayingTrackSwipe {
    val swipe = remember(player) { NowPlayingTrackSwipe(player) }
    DisposableEffect(player, swipe) {
        player?.addListener(swipe)
        onDispose { player?.removeListener(swipe) }
    }
    return swipe
}

/** The child reports where the gesture began without taking any events away from its regular control. */
internal fun Modifier.guardTrackSwipe(swipe: NowPlayingTrackSwipe): Modifier = pointerInput(swipe) {
    awaitEachGesture {
        swipe.guard(awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial))
    }
}

/** On the main pass, draggable children and the vertical scroller get the first chance to handle movement. */
internal fun Modifier.nowPlayingTrackSwipe(swipe: NowPlayingTrackSwipe): Modifier = pointerInput(swipe) {
    awaitEachGesture {
        val down = awaitFirstDown()
        if (swipe.isGuarded(down)) return@awaitEachGesture
        val minimumSwipe = (size.width * 0.15f).coerceIn(48.dp.toPx(), 80.dp.toPx())
        var current = down
        var horizontal = false
        while (current.pressed) {
            val changed = awaitPointerEvent().changes.singleOrNull() ?: return@awaitEachGesture
            if (changed.id != down.id || changed.isConsumed) return@awaitEachGesture
            current = changed
            val distance = current.position - down.position
            if (!horizontal) {
                if (distance.getDistance() < viewConfiguration.touchSlop) continue
                if (abs(distance.x) < abs(distance.y) * 1.3f) return@awaitEachGesture
                horizontal = true
            }
            if (current.pressed) current.consume()
        }
        val distance = current.position - down.position
        if (horizontal && abs(distance.x) >= minimumSwipe && abs(distance.x) >= abs(distance.y) * 1.3f) {
            swipe.skip(if (distance.x < 0) TrackSwipeDirection.NEXT else TrackSwipeDirection.PREVIOUS)
        }
    }
}

@Composable
internal fun rememberTrackSwipeMotion(swipe: NowPlayingTrackSwipe): Modifier {
    val progress = remember(swipe) { Animatable(0f) }
    val transition = swipe.transition
    LaunchedEffect(transition) {
        if (transition != null && currentCoroutineContext()[MotionDurationScale]?.scaleFactor != 0f) {
            progress.snapTo(transition.direction.incomingSide)
            progress.animateTo(0f, tween(durationMillis = 230))
        } else {
            progress.snapTo(0f)
        }
    }
    return Modifier
        .absoluteOffset { IntOffset((40.dp.toPx() * progress.value).roundToInt(), 0) }
        .graphicsLayer { alpha = 1f - 0.12f * abs(progress.value) }
}
