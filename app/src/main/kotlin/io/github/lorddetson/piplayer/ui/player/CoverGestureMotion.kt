package io.github.lorddetson.piplayer.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureBinding
import io.github.lorddetson.piplayer.settings.UiGestureKeyframe
import io.github.lorddetson.piplayer.settings.UiGestureTarget
import io.github.lorddetson.piplayer.settings.UiGestureTimeline
import io.github.lorddetson.piplayer.settings.UiGestureTransform
import io.github.lorddetson.piplayer.ui.customization.stockGesture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal const val NOW_PLAYING_COVER_ID = "now:cover"

private fun cardTimeline(x: Float, y: Float) = UiGestureTimeline(
    UiGestureTarget.component(NOW_PLAYING_COVER_ID),
    listOf(
        UiGestureKeyframe(0f),
        UiGestureKeyframe(1f, UiGestureTransform(
            translationX = x,
            translationY = y,
            scale = 0.82f,
            rotation = (x + y) * 11f,
        )),
    ),
)

private val swipeCards = mapOf(
    UiBuiltInGesture.SWIPE_LEFT to cardTimeline(-1f, 0f),
    UiBuiltInGesture.SWIPE_RIGHT to cardTimeline(1f, 0f),
    UiBuiltInGesture.SWIPE_UP to cardTimeline(0f, -1f),
    UiBuiltInGesture.SWIPE_DOWN to cardTimeline(0f, 1f),
)

private fun cardsFor(direction: TrackSwipeDirection, gesture: UiBuiltInGesture?): UiGestureTimeline =
    gesture?.let(swipeCards::get) ?: swipeCards.getValue(
        if (direction == TrackSwipeDirection.NEXT) UiBuiltInGesture.SWIPE_LEFT else UiBuiltInGesture.SWIPE_RIGHT,
    )

internal val previousCoverGesture = stockGesture(
    NOW_PLAYING_COVER_ID, UiBuiltInGesture.SWIPE_RIGHT, UiGestureAction(UiGestureActionId.PREVIOUS_TRACK),
    listOf(swipeCards.getValue(UiBuiltInGesture.SWIPE_RIGHT)),
)
internal val nextCoverGesture = stockGesture(
    NOW_PLAYING_COVER_ID, UiBuiltInGesture.SWIPE_LEFT, UiGestureAction(UiGestureActionId.NEXT_TRACK),
    listOf(swipeCards.getValue(UiBuiltInGesture.SWIPE_LEFT)),
)
internal val queueCoverGesture = stockGesture(
    NOW_PLAYING_COVER_ID, UiBuiltInGesture.SWIPE_DOWN, UiGestureAction(UiGestureActionId.OPEN_QUEUE),
)

private fun UiGestureActionId?.trackDirection(): TrackSwipeDirection? = when (this) {
    UiGestureActionId.NEXT_TRACK -> TrackSwipeDirection.NEXT
    UiGestureActionId.PREVIOUS_TRACK -> TrackSwipeDirection.PREVIOUS
    else -> null
}

/** One contact previews a fixed neighborhood. Only the release can hand the preview off to playback or navigation. */
internal class CoverGestureMotion(private val swipe: NowPlayingTrackSwipe, private val scope: CoroutineScope) {
    data class TrackFrame(
        val destination: TrackSwipeEntry,
        val outgoing: ImageBitmap?,
        val outgoingName: String?,
        val timeline: UiGestureTimeline,
        val direct: Boolean,
    )

    private data class Contact(val source: String, val neighbors: TrackSwipeNeighbors?, val action: UiGestureActionId?)

    private data class ArtworkHandoff(
        val timelineRevision: Int,
        val occurrence: Int,
        val index: Int,
        val artworkRevision: Any?,
        val outgoing: ImageBitmap?,
        val image: ImageBitmap,
    ) {
        fun isCurrent(neighbors: TrackSwipeNeighbors) = occurrence == neighbors.occurrence && index == neighbors.currentIndex

        fun awaitsLiveArt(live: ImageBitmap?) = live == null || live === outgoing && live !== image
    }

    private var contact: Contact? = null
    private var settled: Job? = null
    private var opening = false
    private var artworkHandoff by mutableStateOf<ArtworkHandoff?>(null)
    private var prefetched = emptyMap<TrackSwipeEntry, ImageBitmap?>()
    private var artworkRevision: Any? = null
    private var liveCover: ImageBitmap? = null
    private var currentCover: ImageBitmap? = null
    private var currentName: String? = null
    var openQueue: (() -> Unit)? = null
    var track by mutableStateOf<TrackFrame?>(null)
        private set
    var showQueue by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    private val motionEnabled get() = scope.coroutineContext[MotionDurationScale]?.scaleFactor != 0f

    fun updateArtwork(cover: ImageBitmap?, name: String?, nearby: Map<TrackSwipeEntry, ImageBitmap?>, revision: Any?) {
        val neighbors = swipe.neighbors
        artworkHandoff = artworkHandoff?.takeIf { hint ->
            neighbors != null && hint.timelineRevision == neighbors.revision && hint.artworkRevision == revision &&
                (neighbors.occurrence < hint.occurrence || hint.isCurrent(neighbors) && hint.awaitsLiveArt(cover))
        }
        prefetched = nearby
        artworkRevision = revision
        liveCover = cover
        currentCover = displayedArtwork(cover, revision)
        currentName = name
    }

    fun displayedArtwork(live: ImageBitmap?, revision: Any?): ImageBitmap? {
        val neighbors = swipe.neighbors ?: return live
        return artworkHandoff?.takeIf { hint ->
            hint.timelineRevision == neighbors.revision && hint.artworkRevision == revision &&
                hint.isCurrent(neighbors) && hint.awaitsLiveArt(live)
        }?.image ?: live
    }

    fun start(source: String) {
        if (opening || contact?.source == source) return
        settled?.cancel()
        clearFrame()
        contact = Contact(source, swipe.neighbors, null)
    }

    fun preview(source: String, binding: UiGestureBinding?, fraction: Float) {
        val action = binding?.action?.id
        val direction = action.trackDirection()
        if (direction == null && action != UiGestureActionId.OPEN_QUEUE) {
            contact?.takeIf { it.source == source }?.let {
                contact = it.copy(action = null)
                clearFrame()
            }
            return
        }
        start(source)
        val active = contact?.takeIf { it.source == source } ?: return
        contact = active.copy(action = action)
        if (!motionEnabled) return
        val destination = direction?.let { active.neighbors?.takeIf { it == swipe.neighbors }?.adjacent(it) }
        track = if (direction != null && destination != null) {
            val timelines = binding?.timelines.orEmpty()
            val direct = timelines.firstOrNull { it.target.members.singleOrNull() == NOW_PLAYING_COVER_ID }
            val grouped = direct == null && timelines.any { NOW_PLAYING_COVER_ID in it.target.members }
            val motionOff = direct?.keyframes?.all { it.transform == UiGestureTransform() } == true
            if (grouped || motionOff) null else {
                val timeline = direct ?: cardsFor(direction, binding?.slot?.gesture?.builtIn)
                track?.takeIf { it.destination == destination && it.timeline == timeline && it.direct == (direct != null) }
                    ?: TrackFrame(destination, currentCover, currentName, timeline, direct != null)
            }
        } else null
        showQueue = action == UiGestureActionId.OPEN_QUEUE && openQueue != null
        progress = fraction.coerceIn(0f, 1f)
    }

    fun commit(source: String, binding: UiGestureBinding): Boolean? {
        if (contact?.source != source || contact?.action != binding.action.id) return null
        return end(commit = true)
    }

    fun end(commit: Boolean): Boolean {
        val active = contact ?: return false
        contact = null
        val action = active.action
        val direction = action?.trackDirection()
        if (commit && action == UiGestureActionId.OPEN_QUEUE && openQueue != null) {
            opening = true
            settle(1f) {
                opening = false
                openQueue?.invoke()
            }
            return true
        }
        val source = active.neighbors
        val destination = if (commit && direction != null) source?.adjacent(direction) else null
        val switched = destination != null && direction != null && swipe.skip(direction, source)
        if (switched && source != null && destination != null) {
            // The active art loader can still be working after the cards have settled. Hold only the exact new occurrence.
            artworkHandoff = prefetched[destination]?.let { image ->
                ArtworkHandoff(source.revision, source.occurrence + 1, destination.index, artworkRevision, liveCover, image)
            }
        }
        settle(if (switched) 1f else 0f)
        return switched
    }

    fun finish(source: String) {
        if (source == contact?.source) end(commit = false)
    }

    fun rejectStalePreview(neighbors: TrackSwipeNeighbors?) {
        val active = contact ?: return
        if (active.neighbors != neighbors) track = null
    }

    fun ownsTransform(id: String): Boolean = id == NOW_PLAYING_COVER_ID && track?.direct == true

    private fun settle(destination: Float, done: () -> Unit = {}) {
        settled?.cancel()
        if (!motionEnabled || track == null && !showQueue || progress == destination) {
            clearFrame()
            done()
            return
        }
        settled = scope.launch {
            Animatable(progress).animateTo(destination, tween(170)) { progress = value }
            clearFrame()
            done()
        }
    }

    private fun clearFrame() {
        track = null
        showQueue = false
        progress = 0f
    }
}

private enum class CoverSwipeAxis { HORIZONTAL, VERTICAL }

@Composable
internal fun rememberCoverGestureMotion(swipe: NowPlayingTrackSwipe, openQueue: (() -> Unit)?): CoverGestureMotion {
    val scope = rememberCoroutineScope()
    val motion = remember(swipe, scope) { CoverGestureMotion(swipe, scope) }
    SideEffect { motion.openQueue = openQueue }
    return motion
}

/** The cover owns the contact before the parent scroll view; controls outside it keep their usual touch handling. */
internal fun Modifier.coverGestureInput(
    swipe: NowPlayingTrackSwipe,
    motion: CoverGestureMotion,
    left: Boolean,
    right: Boolean,
    down: Boolean,
): Modifier = pointerInput(swipe, motion, left, right, down) {
    awaitEachGesture {
        val start = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        swipe.guard(start)
        if (!left && !right && !down) return@awaitEachGesture
        motion.start(NOW_PLAYING_COVER_ID)
        val trackMinimum = (size.width * 0.45f).coerceIn(44.dp.toPx(), 80.dp.toPx())
        val queueMinimum = 54.dp.toPx()
        var axis: CoverSwipeAxis? = null
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointer = event.changes.singleOrNull() ?: break
                if (pointer.id != start.id || pointer.isConsumed) break
                val distance = pointer.position - start.position
                val horizontal = abs(distance.x) >= abs(distance.y) * 1.3f
                val vertical = distance.y >= abs(distance.x) * 1.3f
                if (axis == null && distance.getDistance() >= viewConfiguration.touchSlop) {
                    axis = when {
                        horizontal && (if (distance.x < 0f) left else right) -> CoverSwipeAxis.HORIZONTAL
                        vertical && down -> CoverSwipeAxis.VERTICAL
                        else -> break
                    }
                }
                if (axis == null) {
                    if (!pointer.pressed) break
                    continue
                }
                val binding = when (axis) {
                    CoverSwipeAxis.HORIZONTAL -> when {
                        distance.x < 0f && left -> nextCoverGesture
                        distance.x > 0f && right -> previousCoverGesture
                        else -> null
                    }
                    CoverSwipeAxis.VERTICAL -> queueCoverGesture.takeIf { distance.y > 0f && down }
                }
                val trackDrag = axis == CoverSwipeAxis.HORIZONTAL
                val amount = if (trackDrag) abs(distance.x) else distance.y
                val minimum = if (trackDrag) trackMinimum else queueMinimum
                val full = if (trackDrag) size.width.toFloat() else size.height * 1.8f
                motion.preview(NOW_PLAYING_COVER_ID, binding, amount / full.coerceAtLeast(minimum))
                if (!pointer.pressed) {
                    motion.end(commit = binding != null && amount >= minimum && (if (trackDrag) horizontal else vertical))
                    break
                }
                pointer.consume()
            }
        } finally {
            motion.end(commit = false)
        }
    }
}

@Composable
internal fun SwipeCoverArtwork(
    cover: ImageBitmap?,
    name: String?,
    noteSize: Dp,
    motion: CoverGestureMotion,
    modifier: Modifier,
    fallback: Modifier,
    nearby: Map<TrackSwipeEntry, ImageBitmap?>,
) {
    val shown = motion.track
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.testTag(if (cover != null) PlayerTags.COVER else PlayerTags.COVER_PLACEHOLDER)) {
        if (shown == null) {
            CoverImage(
                cover, Modifier.fillMaxSize().then(fallback).graphicsLayer {
                    this.shape = shape
                    clip = true
                }, noteSize, name, tagged = false,
            )
        } else {
            val replacement = nearby[shown.destination]
            // The neighbor leaves the prefetch set as soon as playback switches to it.
            var prefetched by remember(shown) { mutableStateOf(replacement) }
            SideEffect { if (replacement != null) prefetched = replacement }
            CoverImage(prefetched, Modifier.fillMaxSize().coverPose(shape) {
                val pose = shown.timeline.at(1f - motion.progress)
                pose.copy(translationX = -pose.translationX, translationY = -pose.translationY, rotation = -pose.rotation)
            }, noteSize, shown.destination.item.mediaMetadata.albumTitle?.toString(), tagged = false)
            CoverImage(shown.outgoing, Modifier.fillMaxSize().coverPose(shape) {
                shown.timeline.at(motion.progress)
            }, noteSize, shown.outgoingName, tagged = false)
        }
    }
}

private fun Modifier.coverPose(
    corners: RoundedCornerShape,
    transform: () -> UiGestureTransform,
): Modifier = graphicsLayer {
    val pose = transform()
    translationX = size.width * pose.translationX
    translationY = size.height * pose.translationY
    scaleX = pose.scale
    scaleY = pose.scale
    rotationZ = pose.rotation
    alpha = pose.alpha
    transformOrigin = TransformOrigin(pose.pivotX, pose.pivotY)
    shape = corners
    clip = true
}

@Composable
internal fun rememberNearbyArtwork(
    entry: TrackSwipeEntry?,
    revision: Any?,
    load: (suspend (MediaItem) -> ImageBitmap?)?,
): ImageBitmap? {
    val resources = LocalContext.current.resources.displayMetrics
    val size = minOf(resources.widthPixels, resources.heightPixels)
    var image by remember(entry, revision) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry, revision) {
        val item = entry?.item ?: return@LaunchedEffect
        image = if (load != null) load(item) else item.mediaMetadata.artworkData?.let {
            withContext(Dispatchers.Default) { Covers.decode(it, size)?.asImageBitmap() }
        }
    }
    return image
}
