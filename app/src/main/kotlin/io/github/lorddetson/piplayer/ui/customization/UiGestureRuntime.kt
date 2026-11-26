package io.github.lorddetson.piplayer.ui.customization

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiGesture
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureBinding
import io.github.lorddetson.piplayer.settings.UiGestureEnvironment
import io.github.lorddetson.piplayer.settings.UiGestureMotionRecognizer
import io.github.lorddetson.piplayer.settings.UiGesturePoint
import io.github.lorddetson.piplayer.settings.UiGestureProblem
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiGestureSlot
import io.github.lorddetson.piplayer.settings.UiGestureTapRecognizer
import io.github.lorddetson.piplayer.settings.UiGestureTarget
import io.github.lorddetson.piplayer.settings.UiGestureTimeline
import io.github.lorddetson.piplayer.settings.UiSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

internal data class UiGestureMotion(
    val preview: (source: String, binding: UiGestureBinding?, progress: Float) -> Unit,
    val commit: (source: String, binding: UiGestureBinding) -> Boolean?,
    val ownsTransform: (String) -> Boolean = { false },
    val start: (source: String) -> Unit = {},
    val finish: (source: String) -> Unit = {},
)

internal val LocalUiGestureMotion = staticCompositionLocalOf<UiGestureMotion?> { null }
internal val LocalUiGestureActionHandler = staticCompositionLocalOf<((UiGestureAction) -> Boolean)?> { null }
internal val LocalUiGestureRuntime = staticCompositionLocalOf<UiGestureRuntime?> { null }
internal val LocalUiGestureAncestors = staticCompositionLocalOf<List<Pair<UiGestureRuntime, String>>> { emptyList() }
internal val LocalUiGestureEditing = staticCompositionLocalOf { false }
internal val LocalUiGesturePanelRegistry = staticCompositionLocalOf<UiGesturePanelRegistry?> { null }

internal class UiGesturePanelRegistry {
    val panels = mutableStateMapOf<UiSurface, UiGestureRuntime>()
}

internal fun stockGesture(
    id: String,
    gesture: UiBuiltInGesture,
    action: UiGestureAction,
    timelines: List<UiGestureTimeline> = emptyList(),
) = UiGestureBinding(UiGestureSlot(UiGestureTarget.component(id), UiGesture.builtIn(gesture)), action, timelines)

internal fun UiGestureProfile.safeForDisplay(surface: UiSurface, environment: UiGestureEnvironment): UiGestureProfile {
    val current = surfaces[surface] ?: return this
    val problem = validate(surface, environment)
    if (problem == null) return this
    if (problem is UiGestureProblem.MissingActions) {
        val lost = environment.stock.filter { it.action in problem.actions && environment.reaches(it.slot.target) }
        val overrides = current.bindings.map(UiGestureBinding::slot) + current.unbound
        val restored = overrides.filter { slot ->
            lost.any { it.slot.gesture == slot.gesture && it.slot.target.members.any(slot.target.members::contains) }
        }.fold(current) { draft, slot -> draft.restore(slot) }
        return editSurface(surface, restored)
    }
    return copy(surfaces = surfaces - surface)
}

internal class UiGestureContactOwner {
    private var tapContact: Pair<Long, Long>? = null
    private var motionContact: Pair<Long, Long>? = null
    private var exclusiveTap: Pair<Long, Long>? = null

    fun reserveNativeTap(change: PointerInputChange) {
        exclusiveTap = change.id.value to change.uptimeMillis
        change.consume()
    }

    fun suppressNativeHold(change: PointerInputChange): Boolean = exclusiveTap == (change.id.value to change.uptimeMillis)

    // Main-pass delivery traverses the hit path from its deepest node outward.
    fun claim(change: PointerInputChange, movement: Boolean): Boolean {
        val key = change.id.value to change.uptimeMillis
        if (key == if (movement) motionContact else tapContact) return false
        if (movement) motionContact = key else tapContact = key
        return true
    }
}

internal class UiGestureRuntime(
    val surface: UiSurface,
    val owner: UiGestureContactOwner,
    val openEditor: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    var profile by mutableStateOf(UiGestureProfile())
    var environment by mutableStateOf(UiGestureEnvironment(emptySet(), emptyList(), emptyList()))
    var enabled by mutableStateOf(true)
    var userProfile = UiGestureProfile()
    var hide: (String) -> Boolean = { true }
    var nestedContainers: Set<String> = emptySet()
    var catalog: Map<String, UiCanvasComponent> = emptyMap()
    var preview by mutableStateOf<Pair<UiGestureBinding, Float>?>(null)
        private set

    val bounds = mutableMapOf<String, Rect>()
    val mounts = mutableStateMapOf<String, Int>()
    private val clickActions = mutableMapOf<String, MutableMap<Any, () -> Unit>>()
    private val holdActions = mutableMapOf<String, MutableMap<Any, () -> Unit>>()
    var registeredStock by mutableStateOf<Set<UiGestureBinding>>(emptySet())
        private set
    private var previewToken: Any? = null
    private var disposed = false

    fun dispose() {
        disposed = true
        previewToken = null
        preview = null
    }

    fun register(id: String, token: Any, click: (() -> Unit)?, hold: (() -> Unit)?) {
        if (click != null) {
            clickActions.getOrPut(id) { linkedMapOf() }[token] = click
            registeredStock += stockGesture(id, UiBuiltInGesture.TAP, UiGestureAction(UiGestureActionId.STOCK_CLICK, targetId = id))
        }
        if (hold != null) {
            holdActions.getOrPut(id) { linkedMapOf() }[token] = hold
            registeredStock += stockGesture(id, UiBuiltInGesture.LONG_PRESS, UiGestureAction(UiGestureActionId.CONTEXT_MENU, targetId = id))
        }
    }

    fun unregister(id: String, token: Any) {
        clickActions[id]?.let { if (it.remove(token) != null && it.isEmpty()) clickActions.remove(id) }
        holdActions[id]?.let { if (it.remove(token) != null && it.isEmpty()) holdActions.remove(id) }
    }

    fun changePreview(source: String, binding: UiGestureBinding?, progress: Float, motion: UiGestureMotion?) {
        previewToken = null
        preview = binding?.takeIf { motionEnabled() }?.let { it to progress }
        motion?.preview?.invoke(source, binding, progress)
    }

    fun finishPreview(source: String, committed: Boolean, motion: UiGestureMotion?) {
        motion?.finish?.invoke(source)
        if (disposed) return
        val (binding, progress) = preview ?: return
        val destination = if (committed) 1f else 0f
        if (!motionEnabled() || progress == destination || binding.timelines.isEmpty()) {
            preview = null
            return
        }
        val token = Any()
        previewToken = token
        scope.launch {
            Animatable(progress).animateTo(destination, tween(170)) {
                if (previewToken === token) preview = binding to value
            }
            if (previewToken === token) preview = null
        }
    }

    fun previewEditor(binding: UiGestureBinding?, progress: Float) {
        previewToken = null
        preview = binding?.let { it to progress }
    }

    fun dispatch(
        source: String,
        binding: UiGestureBinding,
        motion: UiGestureMotion?,
        handler: ((UiGestureAction) -> Boolean)?,
    ): Boolean {
        val action = binding.action
        val target = action.targetId ?: source
        val commit: () -> Boolean = {
            when (action.id) {
                UiGestureActionId.STOCK_CLICK ->
                    (clickActions[target]?.values?.lastOrNull() ?: catalog[target]?.onClick)?.let { it(); true } ?: false
                UiGestureActionId.CONTEXT_MENU -> {
                    val open = holdActions[target]?.values?.lastOrNull() ?: { openEditor(target) }
                    open()
                    true
                }
                else -> motion?.commit?.invoke(source, binding) ?: (handler?.invoke(action) == true)
            }
        }
        val animated = binding.slot.gesture.builtIn in tapGestures && binding.timelines.isNotEmpty() && motionEnabled()
        if (!animated) return commit()
        val handoff = action.id in handoffActions || action.id == UiGestureActionId.STOCK_CLICK && stockClickLeavesTarget(target)
        val accepted = handoff || commit()
        if (!accepted) return false
        val token = Any()
        previewToken = token
        scope.launch {
            Animatable(0f).animateTo(1f, tween(160, easing = LinearEasing)) {
                if (previewToken === token) preview = binding to value
            }
            if (previewToken === token) preview = null
            if (handoff && !disposed) commit()
        }
        return true
    }

    fun binding(id: String, gesture: UiBuiltInGesture): UiGestureBinding? =
        profile.resolveFor(surface, environment, id).firstOrNull { it.slot.gesture.builtIn == gesture }

    fun retains(id: String, gesture: UiBuiltInGesture): Boolean {
        if (profile.surfaces[surface] == null) return true
        val original = environment.stock.firstOrNull { id in it.slot.target.members && it.slot.gesture.builtIn == gesture } ?: return true
        val effective = binding(id, gesture) ?: return false
        return effective.slot == original.slot && effective.action == original.action &&
            profile.surfaces[surface]?.bindings.orEmpty().none { it.slot == original.slot }
    }

    fun input(id: String): List<UiGestureBinding> {
        val customized = profile.surfaces[surface]?.bindings?.map(UiGestureBinding::slot)?.toSet() ?: return emptyList()
        val resolved = profile.resolveFor(surface, environment, id)
        return resolved.filter { it.slot in customized }
    }

    fun nativeHold(id: String): Boolean =
        retains(id, UiBuiltInGesture.LONG_PRESS) &&
            input(id).none { it.slot.gesture.builtIn in holdGestures || it.slot.gesture.builtIn in sequenceGestures } &&
            !exclusiveTap(id)

    fun exclusiveTap(id: String): Boolean {
        if (id in nestedContainers || id.startsWith("workspace:")) return false
        val stock = environment.stock.filter { id in it.slot.target.members }.map { it.slot.gesture.builtIn }
        if (UiBuiltInGesture.TAP !in stock || stock.any { it !in tapGestures }) return false
        return !retains(id, UiBuiltInGesture.TAP) ||
            input(id).any { it.slot.gesture.builtIn == UiBuiltInGesture.TAP || it.slot.gesture.builtIn in sequenceGestures }
    }

    fun delegatesMotion(id: String): Boolean = input(id).any { it.slot.gesture.builtIn !in tapGestures }

    private fun motionEnabled() = scope.coroutineContext[MotionDurationScale]?.scaleFactor != 0f
}

@Composable
internal fun uiGestureBinding(id: String, gesture: UiBuiltInGesture): UiGestureBinding? =
    LocalUiGestureRuntime.current?.binding(id, gesture)

@Composable
internal fun stockGestureRetained(id: String, gesture: UiBuiltInGesture): Boolean =
    LocalUiGestureRuntime.current?.retains(id, gesture) ?: true

@Composable
internal fun stockHoldUsesNative(id: String, runtime: UiGestureRuntime? = LocalUiGestureRuntime.current): Boolean =
    runtime?.nativeHold(id) ?: true

@Composable
internal fun uiGestureRuntimeForStock(id: String): UiGestureRuntime? {
    val runtime = LocalUiGestureRuntime.current
    if (runtime?.surface != UiSurface.LIBRARY_WORKSPACE || (!id.startsWith("library:") && !id.startsWith("tab:"))) return runtime
    return LocalUiGesturePanelRegistry.current?.panels?.get(UiSurface.LIBRARY) ?: runtime
}

@Composable
internal fun stockMotionDelegated(id: String): Boolean = LocalUiGestureRuntime.current?.delegatesMotion(id) == true

@Composable
internal fun registerUiGestureActions(id: String, click: (() -> Unit)? = null, hold: (() -> Unit)? = null) {
    val runtime = LocalUiGestureRuntime.current ?: return
    RegisterUiGestureActions(runtime, id, click, hold)
}

@Composable
private fun RegisterUiGestureActions(runtime: UiGestureRuntime, id: String, click: (() -> Unit)?, hold: (() -> Unit)?) {
    val currentClick by rememberUpdatedState(click)
    val currentHold by rememberUpdatedState(hold)
    DisposableEffect(runtime, id, click != null, hold != null) {
        val token = Any()
        runtime.register(id, token,
            if (click == null) null else { { currentClick?.invoke() } },
            if (hold == null) null else { { currentHold?.invoke() } },
        )
        onDispose { runtime.unregister(id, token) }
    }
}

@Composable
internal fun Modifier.uiGestureTarget(
    id: String,
    runtime: UiGestureRuntime? = LocalUiGestureRuntime.current,
    onClick: (() -> Unit)? = null,
    onHold: (() -> Unit)? = null,
    preserveMovement: Boolean = false,
): Modifier {
    runtime ?: return this
    val motion by rememberUpdatedState(LocalUiGestureMotion.current)
    val handler by rememberUpdatedState(LocalUiGestureActionHandler.current)
    val ancestors = LocalUiGestureAncestors.current
    RegisterUiGestureActions(runtime, id, onClick, onHold)
    DisposableEffect(runtime, id) {
        runtime.mounts[id] = (runtime.mounts[id] ?: 0) + 1
        onDispose {
            val count = runtime.mounts[id] ?: 1
            if (count <= 1) {
                runtime.bounds.remove(id)
                runtime.mounts.remove(id)
            } else runtime.mounts[id] = count - 1
        }
    }
    val position = onGloballyPositioned { runtime.bounds[id] = it.boundsInRoot() }
    val animationLayer = Modifier.graphicsLayer {
        val (binding, progress) = runtime.preview ?: return@graphicsLayer
        if (runtime.enabled && motion?.ownsTransform?.invoke(id) == true) return@graphicsLayer
        val timeline = binding.timelines.firstOrNull { !it.target.isGroup && id in it.target.members }
            ?: binding.timelines.firstOrNull { id in it.target.members } ?: return@graphicsLayer
        if (ancestors.any { (parent, parentId) ->
            parent.preview?.first?.timelines?.any { parentId in it.target.members } == true
        }) return@graphicsLayer
        val frame = runtime.bounds[id]?.takeUnless(Rect::isEmpty) ?: return@graphicsLayer
        val target = timeline.target.members.mapNotNull(runtime.bounds::get).reduceOrNull { first, second ->
            Rect(minOf(first.left, second.left), minOf(first.top, second.top),
                maxOf(first.right, second.right), maxOf(first.bottom, second.bottom))
        } ?: frame
        val value = timeline.at(progress)
        translationX = value.translationX * target.width
        translationY = value.translationY * target.height
        scaleX = value.scale
        scaleY = value.scale
        rotationZ = value.rotation
        alpha = value.alpha
        transformOrigin = TransformOrigin(
            (target.left + target.width * value.pivotX - frame.left) / frame.width,
            (target.top + target.height * value.pivotY - frame.top) / frame.height,
        )
    }
    if (!runtime.enabled || runtime.profile.surfaces.isEmpty()) return position.then(animationLayer)
    val custom = runtime.input(id)
    val overridesTap = !runtime.retains(id, UiBuiltInGesture.TAP)
    val overridesHold = !runtime.retains(id, UiBuiltInGesture.LONG_PRESS)
    val allBindings = runtime.profile.resolveFor(runtime.surface, runtime.environment, id)
    val tapBindings = allBindings.filter { it.slot.gesture.builtIn in tapGestures }.associateBy { it.slot.gesture }
    val tapSequence = custom.any { it.slot.gesture.builtIn in sequenceGestures }
    val capturesTap = overridesTap || tapSequence || custom.any { it.slot.gesture.builtIn == UiBuiltInGesture.TAP }
    val reserveTap = runtime.exclusiveTap(id)
    val capturesHold = overridesHold || custom.any { it.slot.gesture.builtIn in holdGestures } ||
        ((tapSequence || reserveTap) && tapBindings.keys.any { it.builtIn == UiBuiltInGesture.LONG_PRESS })
    val motionGestures = if (runtime.delegatesMotion(id)) allBindings.map { it.slot.gesture }
        .filterNot { it.builtIn in tapGestures }.toSet() else emptySet()
    val reserveWorkspaceMotion = id == "workspace:content"
    val presets = runtime.profile.presets
    val accessibleActions = custom.distinctBy(UiGestureBinding::action).map { binding ->
        CustomAccessibilityAction(gestureActionLabel(binding.action, runtime.catalog)) {
            runtime.dispatch(id, binding, motion, handler)
        }
    }
    val tap = tapBindings.values.firstOrNull { it.slot.gesture.builtIn == UiBuiltInGesture.TAP }
    val hold = tapBindings.values.firstOrNull { it.slot.gesture.builtIn == UiBuiltInGesture.LONG_PRESS }
    val semantics = if (capturesTap || capturesHold || accessibleActions.isNotEmpty()) {
        Modifier.semantics(mergeDescendants = true) {
            customActions = accessibleActions
            if (capturesTap) onClick {
                tap?.let { runtime.dispatch(id, it, motion, handler) } == true
            }
            if (capturesHold) onLongClick {
                hold?.let { runtime.dispatch(id, it, motion, handler) } == true
            }
        }.onPreviewKeyEvent { event ->
            if (!capturesTap || event.key !in setOf(Key.Enter, Key.Spacebar, Key.DirectionCenter)) false
            else {
                if (event.type == KeyEventType.KeyUp && tap != null) runtime.dispatch(id, tap, motion, handler)
                true
            }
        }
    } else Modifier
    return position.then(semantics).pointerInput(runtime, id, allBindings, capturesTap, capturesHold, reserveTap, preserveMovement, presets) {
        val taps = UiGestureTapRecognizer(tapBindings.keys, viewConfiguration.doubleTapTimeoutMillis)
        val paths = UiGestureMotionRecognizer(motionGestures, presets, 30.dp.toPx(), 120.dp.toPx())
        var tapWaitDeadline: Long? = null
        var motionStarted = false
        fun finishMotion(committed: Boolean = false) {
            if (!motionStarted) return
            runtime.finishPreview(id, committed, motion)
            motionStarted = false
        }
        fun dispatch(gesture: UiGesture): Boolean {
            val binding = allBindings.firstOrNull { it.slot.gesture == gesture } ?: return false
            return runtime.dispatch(id, binding, motion, handler)
        }
        try {
            awaitPointerEventScope {
                while (true) {
                    val remaining = tapWaitDeadline?.takeIf { taps.deadlineMillis != null }
                        ?.let { (it - SystemClock.uptimeMillis()).coerceAtLeast(1L) }
                    val down = if (remaining == null) nextTouch() else withTimeoutOrNull(remaining) { nextTouch() }
                    if (down == null) {
                        taps.deadlineMillis?.let { deadline -> taps.advance(deadline).forEach { dispatch(it) } }
                        continue
                    }
                    if (reserveTap) runtime.owner.reserveNativeTap(down)
                    awaitPointerEvent(PointerEventPass.Main)
                    val ownsTap = (capturesTap || capturesHold || onClick != null || onHold != null) &&
                        runtime.owner.claim(down, movement = false)
                    val ownsMotion = motionGestures.isNotEmpty() && runtime.owner.claim(down, movement = true)
                    if ((!ownsTap || !capturesTap && !capturesHold) && !ownsMotion) continue
                    if (ownsTap) {
                        tapWaitDeadline = null
                        taps.pressStarted(down.uptimeMillis).forEach { dispatch(it) }
                    }
                    if (ownsMotion) {
                        runtime.changePreview(id, null, 0f, null)
                        motion?.start?.invoke(id)
                        motionStarted = true
                    }
                    val startPoint = down.position
                    val points = UiGesturePathBuffer().apply { add(UiGesturePoint(startPoint.x, startPoint.y)) }
                    var moved = false
                    var held = false
                    var capturedMotion = false
                    var motionCommitted = false
                    val holdDuration = viewConfiguration.longPressTimeoutMillis
                    val holdTime = down.uptimeMillis + holdDuration
                    val holdDeadline = SystemClock.uptimeMillis() + holdDuration
                    while (true) {
                        val holdRemaining = (holdDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                        val event = if (ownsTap && capturesHold && !held && !moved) {
                            withTimeoutOrNull(holdRemaining) { awaitPointerEvent(PointerEventPass.Initial) }
                        } else awaitPointerEvent(PointerEventPass.Initial)
                        if (event == null) {
                            held = true
                            taps.longPressed(holdTime).forEach { dispatch(it) }
                            continue
                        }
                        val change = event.changes.singleOrNull()?.takeIf { it.id == down.id }
                        if (change == null) {
                            taps.cancel()
                            break
                        }
                        val point = change.position
                        val displacement = point - startPoint
                        moved = moved || displacement.getDistance() > viewConfiguration.touchSlop
                        points.add(UiGesturePoint(point.x, point.y))
                        if (!change.pressed) {
                            if (change.isConsumed && !capturedMotion && !held) {
                                taps.cancel()
                                break
                            }
                            if (moved) {
                                val match = if (ownsMotion) paths.finish(points.points()) else null
                                if (match != null && (capturedMotion || !preserveMovement)) {
                                    change.consume()
                                    val binding = allBindings.firstOrNull { it.slot.gesture == match.gesture }
                                    val progress = runtime.preview?.takeIf { it.first == binding }?.second ?: match.progress
                                    runtime.changePreview(id, binding, progress, motion)
                                    motionCommitted = dispatch(match.gesture)
                                }
                                taps.cancel()
                            } else if (!held && ownsTap && capturesHold && change.uptimeMillis >= holdTime) {
                                change.consume()
                                taps.longPressed(holdTime).forEach { dispatch(it) }
                            } else if (ownsTap && (held || capturesTap)) {
                                change.consume()
                                if (!held) {
                                    taps.shortReleased(change.uptimeMillis).forEach { dispatch(it) }
                                    // Input timestamps match the sequence. Scheduling starts when this release reaches the handler.
                                    tapWaitDeadline = taps.deadlineMillis?.let {
                                        SystemClock.uptimeMillis() + (it - change.uptimeMillis)
                                    }
                                }
                            }
                            break
                        }
                        if (!moved) continue
                        val direction = dominantSwipe(displacement)
                        val match = if (ownsMotion) paths.preview(points.points()) else null
                        if (ownsMotion && reserveWorkspaceMotion &&
                            (match != null || motionGestures.any { it.builtIn == direction })
                        ) capturedMotion = true
                        if (held || capturedMotion) change.consume()
                        val movement = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
                        if (movement == null || (movement.isConsumed && !capturedMotion && !held && !reserveWorkspaceMotion)) {
                            taps.cancel()
                            break
                        }
                        if (match != null || (ownsMotion && !preserveMovement && motionGestures.any {
                                it.builtIn !in swipes || it.builtIn == direction
                            })) {
                            capturedMotion = true
                            movement.consume()
                        }
                        if (ownsMotion) {
                            runtime.changePreview(id, match?.let { entry -> allBindings.firstOrNull { it.slot.gesture == entry.gesture } },
                                match?.progress ?: 0f, motion)
                        }
                    }
                    finishMotion(motionCommitted)
                }
            }
        } finally {
            finishMotion()
        }
    }.then(animationLayer)
}

private suspend fun AwaitPointerEventScope.nextTouch() =
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

private val holdGestures = setOf(UiBuiltInGesture.LONG_PRESS, UiBuiltInGesture.TAP_THEN_LONG_PRESS, UiBuiltInGesture.DOUBLE_TAP_THEN_LONG_PRESS)
private val sequenceGestures = setOf(UiBuiltInGesture.DOUBLE_TAP, UiBuiltInGesture.TRIPLE_TAP,
    UiBuiltInGesture.TAP_THEN_LONG_PRESS, UiBuiltInGesture.DOUBLE_TAP_THEN_LONG_PRESS)
private val tapGestures = holdGestures + sequenceGestures + UiBuiltInGesture.TAP
private val swipes = setOf(UiBuiltInGesture.SWIPE_LEFT, UiBuiltInGesture.SWIPE_RIGHT, UiBuiltInGesture.SWIPE_UP, UiBuiltInGesture.SWIPE_DOWN)
private val handoffActions = setOf(UiGestureActionId.CONTEXT_MENU, UiGestureActionId.OPEN_QUEUE, UiGestureActionId.SLEEP_TIMER)
private val handoffStockClicks = setOf(
    "library:search", "library:settings", "now:queue", "now:more", "now:timer", "now:identify", "now:artist", "now:album",
)

private fun stockClickLeavesTarget(target: String): Boolean =
    target in handoffStockClicks || target.startsWith("nav:") || target.startsWith("tab:") ||
        target.startsWith("mini:") && target != "mini:play_pause"

private fun dominantSwipe(displacement: Offset): UiBuiltInGesture =
    if (abs(displacement.x) > abs(displacement.y)) {
        if (displacement.x < 0f) UiBuiltInGesture.SWIPE_LEFT else UiBuiltInGesture.SWIPE_RIGHT
    } else if (displacement.y < 0f) UiBuiltInGesture.SWIPE_UP else UiBuiltInGesture.SWIPE_DOWN
