package io.github.lorddetson.piplayer.ui.customization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiGesture
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureBinding
import io.github.lorddetson.piplayer.settings.UiGestureEnvironment
import io.github.lorddetson.piplayer.settings.UiGestureKeyframe
import io.github.lorddetson.piplayer.settings.UiGestureMotionRecognizer
import io.github.lorddetson.piplayer.settings.UiGestureMotionPreset
import io.github.lorddetson.piplayer.settings.UiGesturePoint
import io.github.lorddetson.piplayer.settings.UiGesturePreset
import io.github.lorddetson.piplayer.settings.UiGestureProblem
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiGestureSlot
import io.github.lorddetson.piplayer.settings.UiGestureTimeline
import io.github.lorddetson.piplayer.settings.UiGestureTarget
import io.github.lorddetson.piplayer.settings.UiGestureTransform
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiSurfaceGestures
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun UiGestureEditor(
    initialId: String,
    surface: UiSurface,
    catalog: Map<String, UiCanvasComponent>,
    environment: UiGestureEnvironment,
    initial: UiGestureProfile,
    previous: UiGestureProfile,
    runtime: UiGestureRuntime,
    onApply: (UiGestureProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = environment.components.filter(catalog::containsKey).map(UiGestureTarget::component) +
        environment.groups.map(UiGestureTarget::group)
    if (targets.isEmpty()) return
    var source by remember { mutableStateOf(targets.firstOrNull { !it.isGroup && initialId in it.members } ?: targets.first()) }
    var gesture by remember { mutableStateOf(UiGesture.builtIn(UiBuiltInGesture.TAP)) }
    var profile by remember { mutableStateOf(initial) }
    var progress by remember { mutableStateOf(0f) }
    var recording by remember { mutableStateOf(false) }
    var editingRecording by remember { mutableStateOf<UiGesturePreset?>(null) }
    var keyframe by remember { mutableStateOf<UiGestureTimeline?>(null) }
    val surfaceDraft = profile.surfaces[surface] ?: UiSurfaceGestures(emptyList(), emptySet())
    val slot = UiGestureSlot(source, gesture)
    val assigned = surfaceDraft.bindings.firstOrNull { it.slot == slot }
    val stock = environment.stock.firstOrNull { it.slot == slot }
    val current = assigned ?: stock?.takeUnless { slot in surfaceDraft.unbound }
    val problem = profile.validate(surface, environment, previous = previous)
    val actions = (environment.stock.map(UiGestureBinding::action) +
        targets.filterNot(UiGestureTarget::isGroup).map {
            UiGestureAction(UiGestureActionId.CONTEXT_MENU, targetId = it.members.single())
        } +
        UiGestureActionId.entries.filterNot {
            it == UiGestureActionId.STOCK_CLICK || it == UiGestureActionId.CONTEXT_MENU
        }.map { UiGestureAction(it) }).distinct()
    var target by remember { mutableStateOf(source) }
    val selectedRecording = profile.presets.firstOrNull { it.id == gesture.presetId }
    val usedOnAnotherSurface = profile.surfaces.filterKeys { it != surface }.values.any { layout ->
        layout.bindings.any { it.slot.gesture == gesture }
    }
    fun update(draft: UiSurfaceGestures) {
        profile = profile.editSurface(surface, draft)
    }
    fun preview(value: Float, binding: UiGestureBinding? = current) {
        progress = value
        runtime.previewEditor(binding, value)
    }
    fun switchSlot() = preview(0f, null)
    fun setTimeline(binding: UiGestureBinding, timeline: UiGestureTimeline) {
        val updated = binding.copy(timelines = binding.timelines.filterNot { track ->
            track.target.members.any(timeline.target.members::contains)
        } + timeline)
        update(surfaceDraft.assign(updated))
        preview(progress, updated)
    }

    DisposableEffect(runtime) { onDispose { runtime.previewEditor(null, 0f) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_gestures)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("gesture_editor"),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GestureChoice(R.string.gesture_source, targetLabel(source, catalog), "gesture_source", targets,
                    label = { targetLabel(it, catalog) }, optionTag = { "gesture_source_" + it.members.joinToString("+") }) {
                    if (target == source) target = it
                    source = it
                    switchSlot()
                }
                val gestures = UiBuiltInGesture.entries.map(UiGesture::builtIn) + profile.presets.map { UiGesture.custom(it.id) }
                GestureChoice(R.string.gesture_pattern, gestureLabel(gesture, profile), "gesture_pattern", gestures,
                    label = { gestureLabel(it, profile) }, optionTag = { "gesture_pattern_" + (it.builtIn?.name ?: it.presetId).toString().lowercase() }) {
                    gesture = it
                    switchSlot()
                }
                Column {
                    TextButton(onClick = { editingRecording = null; recording = true }, enabled = profile.presets.size < 24,
                        modifier = Modifier.testTag("gesture_record")) {
                        Text(stringResource(R.string.gesture_record))
                    }
                    if (selectedRecording != null) TextButton(onClick = {
                        editingRecording = selectedRecording
                        recording = true
                    }, modifier = Modifier.testTag("gesture_edit_recording")) {
                        Text(stringResource(if (usedOnAnotherSurface) R.string.gesture_rename_recording else R.string.gesture_edit_recording))
                    }
                    if (selectedRecording != null && !usedOnAnotherSurface) {
                        TextButton(onClick = {
                            val recorded = gesture
                            update(UiSurfaceGestures(surfaceDraft.bindings.filterNot { it.slot.gesture == recorded },
                                surfaceDraft.unbound.filterNot { it.gesture == recorded }.toSet()))
                            profile = profile.copy(presets = profile.presets.filterNot { it.id == recorded.presetId })
                            gesture = UiGesture.builtIn(UiBuiltInGesture.TAP)
                            switchSlot()
                        }) { Text(stringResource(R.string.gesture_delete_recording)) }
                    }
                }
                GestureChoice(R.string.gesture_action, current?.action?.let { gestureActionLabel(it, catalog) }
                    ?: stringResource(R.string.gesture_unassigned), "gesture_action", actions,
                    label = { gestureActionLabel(it, catalog) },
                    optionTag = { "gesture_action_" + it.id.name.lowercase() + it.targetId?.let { id -> "_$id" }.orEmpty() }) {
                    update(surfaceDraft.assign(UiGestureBinding(slot, it, current?.timelines.orEmpty())))
                    switchSlot()
                }
                if (current != null || slot in surfaceDraft.unbound) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current != null) TextButton(onClick = {
                            update(if (stock == null) surfaceDraft.restore(slot) else surfaceDraft.unbind(slot))
                            switchSlot()
                        }, modifier = Modifier.testTag("gesture_remove")) { Text(stringResource(R.string.gesture_remove)) }
                        if (assigned != null || slot in surfaceDraft.unbound) {
                            TextButton(onClick = {
                                update(surfaceDraft.restore(slot))
                                switchSlot()
                            }, modifier = Modifier.testTag("gesture_restore")) { Text(stringResource(R.string.gesture_restore)) }
                        }
                    }
                }
                if (current != null) {
                    if (current.action.id in listOf(UiGestureActionId.SEEK_FORWARD, UiGestureActionId.SEEK_BACKWARD)) {
                        val amount = current.action.amountSeconds ?: 10
                        Text(stringResource(R.string.gesture_seek_amount, amount))
                        Slider(amount.toFloat(), { value ->
                            update(surfaceDraft.assign(current.copy(action = current.action.copy(amountSeconds = value.roundToInt()))))
                        }, valueRange = 1f..300f, modifier = Modifier.testTag("gesture_seek_amount"))
                    }
                    Text(stringResource(R.string.gesture_animation), style = MaterialTheme.typography.titleSmall)
                    for (timeline in current.timelines) {
                        Text(targetLabel(timeline.target, catalog), style = MaterialTheme.typography.bodySmall)
                    }
                    GestureChoice(R.string.gesture_animation_target, targetLabel(target, catalog), "gesture_animation_target", targets,
                        label = { targetLabel(it, catalog) }, optionTag = { "gesture_animation_target_" + it.members.joinToString("+") }) {
                        target = it
                    }
                    val timeline = current.timelines.firstOrNull { it.target == target }
                    val noAnimation = timeline == null || timeline.keyframes.all { it.transform == UiGestureTransform() }
                    GestureChoice(R.string.gesture_animation, if (noAnimation) stringResource(R.string.gesture_motion_none)
                        else stringResource(R.string.gesture_animation_target_assigned), "gesture_animation",
                        UiGestureMotionPreset.entries, label = { stringResource(it.label) },
                        optionTag = { "gesture_animation_" + it.name.lowercase() }) {
                        val isAutomaticCoverMotion = target.members.singleOrNull() == "now:cover" &&
                            current.action.id in trackChangeActions && (gesture.builtIn in previewMovements || gesture.presetId != null)
                        if (it == UiGestureMotionPreset.NONE && !isAutomaticCoverMotion) {
                            val updated = current.copy(timelines = current.timelines.filterNot { track -> track.target == target })
                            update(surfaceDraft.assign(updated))
                            preview(progress, updated)
                        } else setTimeline(current, it.timeline(target))
                    }
                    TextButton(onClick = {
                        keyframe = timeline ?: UiGestureTimeline(target,
                            listOf(UiGestureKeyframe(0f, UiGestureTransform()), UiGestureKeyframe(1f, UiGestureTransform())))
                    }, modifier = Modifier.testTag("gesture_keyframes")) { Text(stringResource(R.string.gesture_keyframes)) }
                    Text(stringResource(R.string.gesture_preview), style = MaterialTheme.typography.bodySmall)
                    Slider(value = progress, onValueChange = { preview(it) }, modifier = Modifier.testTag("gesture_preview"))
                    if (current.timelines.isNotEmpty() && (gesture.builtIn in previewMovements || gesture.presetId != null)) {
                        GesturePreviewPad(gesture, profile.presets) { preview(it) }
                    }
                }
                when (problem) {
                    is UiGestureProblem.MissingActions -> Text(
                        stringResource(R.string.gesture_restore_or_remap,
                            problem.actions.map { gestureActionLabel(it, catalog) }.joinToString()),
                        color = MaterialTheme.colorScheme.error,
                    )
                    UiGestureProblem.InvalidProfile -> Text(stringResource(R.string.gesture_invalid), color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(profile) }, enabled = problem == null, modifier = Modifier.testTag("gesture_apply")) {
                Text(stringResource(R.string.ui_edit_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_edit_cancel)) } },
    )
    if (recording) GestureRecording(editingRecording, allowDrawing = editingRecording == null || !usedOnAnotherSurface,
        onCancel = { recording = false }) {
        profile = profile.copy(presets = profile.presets.filterNot { preset -> preset.id == it.id } + it)
        gesture = UiGesture.custom(it.id)
        recording = false
        switchSlot()
    }
    keyframe?.let { timeline ->
        GestureKeyframes(timeline, onDismiss = { keyframe = null; preview(0f) }, onPreview = { changed, value ->
            current?.let { preview(value, it.copy(timelines = it.timelines.filterNot { track -> track.target == timeline.target } + changed)) }
        }, onApply = {
            current?.let { binding -> setTimeline(binding, it) }
            keyframe = null
        })
    }
}

@Composable
private fun <T> GestureChoice(
    title: Int,
    selected: String,
    tag: String,
    values: List<T>,
    label: @Composable (T) -> String,
    optionTag: (T) -> String,
    onChoose: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.bodySmall)
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(tag)) { Text(selected) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                values.forEach { value ->
                    DropdownMenuItem(text = { Text(label(value)) }, onClick = {
                        expanded = false
                        onChoose(value)
                    }, modifier = Modifier.testTag(optionTag(value)))
                }
            }
        }
    }
}

@Composable
private fun GestureRecording(
    initial: UiGesturePreset?,
    allowDrawing: Boolean,
    onCancel: () -> Unit,
    onRecord: (UiGesturePreset) -> Unit,
) {
    var points by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }
    val recording = remember(name, points) {
        if (points.isEmpty()) initial?.copy(name = name.trim())?.takeIf(UiGesturePreset::isValid)
        else UiGesturePreset.record(id, name.trim(), points.map { UiGesturePoint(it.x, it.y) })
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.gesture_record)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.gesture_name)) },
                    singleLine = true, modifier = Modifier.testTag("gesture_name"))
                if (allowDrawing) {
                    Text(stringResource(R.string.gesture_draw_instruction))
                    GestureTrackPad(points, "gesture_record_pad", onUpdate = { points = it }, onFinish = { _, cancelled ->
                        if (cancelled) points = emptyList()
                    })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { recording?.let(onRecord) }, enabled = recording != null,
                modifier = Modifier.testTag("gesture_save_recording")) { Text(stringResource(R.string.ui_edit_apply)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.ui_edit_cancel)) } },
    )
}

@Composable
private fun GesturePreviewPad(gesture: UiGesture, presets: List<UiGesturePreset>, onProgress: (Float) -> Unit) {
    var points by remember(gesture) { mutableStateOf<List<Offset>>(emptyList()) }
    val density = LocalDensity.current
    val recognizer = remember(gesture, presets, density) {
        with(density) { UiGestureMotionRecognizer(setOf(gesture), presets, 24.dp.toPx(), 120.dp.toPx()) }
    }
    Text(stringResource(R.string.gesture_try_preview))
    GestureTrackPad(points, "gesture_preview_pad", onUpdate = { stroke ->
        points = stroke
        onProgress(recognizer.preview(stroke.map { UiGesturePoint(it.x, it.y) })?.progress ?: 0f)
    }, onFinish = { stroke, cancelled ->
        val result = if (cancelled) null else recognizer.finish(stroke.map { UiGesturePoint(it.x, it.y) })
        onProgress(result?.progress ?: 0f)
    })
}

@Composable
private fun GestureTrackPad(
    points: List<Offset>,
    tag: String,
    onUpdate: (List<Offset>) -> Unit,
    onFinish: (List<Offset>, Boolean) -> Unit,
) {
    val stroke = MaterialTheme.colorScheme.primary
    val update by rememberUpdatedState(onUpdate)
    val finish by rememberUpdatedState(onFinish)
    Canvas(Modifier.fillMaxWidth().height(180.dp).testTag(tag)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val samples = UiGesturePathBuffer()
                samples.add(UiGesturePoint(down.position.x, down.position.y))
                update(listOf(down.position))
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Main).changes.singleOrNull()
                    if (change == null || change.id != down.id || change.isConsumed) {
                        finish(emptyList(), true)
                        break
                    }
                    change.consume()
                    samples.add(UiGesturePoint(change.position.x, change.position.y))
                    val path = samples.points().map { Offset(it.x, it.y) }
                    update(path)
                    if (!change.pressed) {
                        finish(path, false)
                        break
                    }
                }
            }
        }) {
        points.zipWithNext().forEach { (start, end) -> drawLine(stroke, start, end, strokeWidth = 3.dp.toPx()) }
    }
}

@Composable
private fun GestureKeyframes(
    initial: UiGestureTimeline,
    onDismiss: () -> Unit,
    onPreview: (UiGestureTimeline, Float) -> Unit,
    onApply: (UiGestureTimeline) -> Unit,
) {
    var frames by remember { mutableStateOf(initial.keyframes) }
    var time by remember { mutableStateOf(0f) }
    var value by remember { mutableStateOf(initial.at(0f)) }
    var changed by remember { mutableStateOf(false) }
    fun draft() = initial.copy(keyframes = (frames.filterNot { it.progress == time } + UiGestureKeyframe(time, value)).sortedBy { it.progress })
    fun adjust(change: UiGestureTransform) {
        value = change
        changed = true
        onPreview(draft(), time)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gesture_keyframes)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.gesture_keyframe_position, (time * 100).toInt()))
                Slider(time, { next ->
                    time = next
                    changed = false
                    val timeline = initial.copy(keyframes = frames)
                    value = timeline.at(next)
                    onPreview(timeline, next)
                }, steps = 19, modifier = Modifier.testTag("gesture_keyframe_time"))
                TransformSlider(R.string.gesture_translation_x, value.translationX, -1f..1f) { adjust(value.copy(translationX = it)) }
                TransformSlider(R.string.gesture_translation_y, value.translationY, -1f..1f) { adjust(value.copy(translationY = it)) }
                TransformSlider(R.string.gesture_scale, value.scale, 0.25f..2f) { adjust(value.copy(scale = it)) }
                TransformSlider(R.string.gesture_rotation, value.rotation, -180f..180f) { adjust(value.copy(rotation = it)) }
                TransformSlider(R.string.gesture_opacity, value.alpha, 0f..1f) { adjust(value.copy(alpha = it)) }
                TransformSlider(R.string.gesture_pivot_x, value.pivotX, 0f..1f) { adjust(value.copy(pivotX = it)) }
                TransformSlider(R.string.gesture_pivot_y, value.pivotY, 0f..1f) { adjust(value.copy(pivotY = it)) }
                TextButton(onClick = { frames = draft().keyframes; changed = false }, enabled = frames.size < 12 || frames.any { it.progress == time },
                    modifier = Modifier.testTag("gesture_add_keyframe")) { Text(stringResource(R.string.gesture_add_keyframe)) }
                TextButton(onClick = {
                    frames = frames.filterNot { it.progress == time }
                    changed = false
                    value = initial.copy(keyframes = frames).at(time)
                    onPreview(initial.copy(keyframes = frames), time)
                }, enabled = time > 0f && time < 1f && frames.any { it.progress == time },
                    modifier = Modifier.testTag("gesture_delete_keyframe")) {
                    Text(stringResource(R.string.gesture_delete_keyframe))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(if (changed) draft() else initial.copy(keyframes = frames)) },
                enabled = !changed || frames.size < 12 || frames.any { it.progress == time },
                modifier = Modifier.testTag("gesture_apply_keyframes")) { Text(stringResource(R.string.ui_edit_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_edit_cancel)) } },
    )
}

@Composable
private fun TransformSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(stringResource(label))
    Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range)
}

@Composable
private fun targetLabel(target: UiGestureTarget, catalog: Map<String, UiCanvasComponent>): String {
    val values = target.members.map { id -> catalog[id]?.name() ?: id }
    return if (target.isGroup) stringResource(R.string.gesture_group, values.joinToString()) else values.single()
}

@Composable
internal fun gestureActionLabel(action: UiGestureAction, catalog: Map<String, UiCanvasComponent>): String {
    val id = action.targetId
    val label = catalog[id]?.name() ?: id.orEmpty()
    return when (action.id) {
        UiGestureActionId.STOCK_CLICK -> stringResource(R.string.gesture_stock_click, label)
        UiGestureActionId.CONTEXT_MENU -> stringResource(R.string.gesture_context_menu, label)
        UiGestureActionId.NEXT_TRACK -> stringResource(R.string.player_next)
        UiGestureActionId.PREVIOUS_TRACK -> stringResource(R.string.player_previous)
        UiGestureActionId.OPEN_QUEUE -> stringResource(R.string.queue_open)
        UiGestureActionId.PLAY_PAUSE -> stringResource(R.string.player_element_play_pause)
        UiGestureActionId.VOLUME_UP -> stringResource(R.string.gesture_volume_up)
        UiGestureActionId.VOLUME_DOWN -> stringResource(R.string.gesture_volume_down)
        UiGestureActionId.SEEK_FORWARD -> stringResource(R.string.gesture_seek_forward)
        UiGestureActionId.SEEK_BACKWARD -> stringResource(R.string.gesture_seek_backward)
        UiGestureActionId.SPEED_UP -> stringResource(R.string.gesture_speed_up)
        UiGestureActionId.SPEED_DOWN -> stringResource(R.string.gesture_speed_down)
        UiGestureActionId.SPEED_RESET -> stringResource(R.string.gesture_speed_reset)
        UiGestureActionId.TOGGLE_FAVORITE -> stringResource(R.string.player_element_favorite)
        UiGestureActionId.CYCLE_REPEAT -> stringResource(R.string.player_repeat)
        UiGestureActionId.TOGGLE_SHUFFLE -> stringResource(R.string.player_shuffle)
        UiGestureActionId.SLEEP_TIMER -> stringResource(R.string.player_sleep_timer)
    }
}

@Composable
private fun gestureLabel(gesture: UiGesture, profile: UiGestureProfile): String {
    val stock = when (gesture.builtIn) {
        UiBuiltInGesture.SWIPE_LEFT -> R.string.gesture_swipe_left
        UiBuiltInGesture.SWIPE_RIGHT -> R.string.gesture_swipe_right
        UiBuiltInGesture.SWIPE_UP -> R.string.gesture_swipe_up
        UiBuiltInGesture.SWIPE_DOWN -> R.string.gesture_swipe_down
        UiBuiltInGesture.CIRCLE_CLOCKWISE -> R.string.gesture_circle_clockwise
        UiBuiltInGesture.CIRCLE_COUNTERCLOCKWISE -> R.string.gesture_circle_counterclockwise
        UiBuiltInGesture.TAP -> R.string.gesture_tap
        UiBuiltInGesture.DOUBLE_TAP -> R.string.gesture_double_tap
        UiBuiltInGesture.TRIPLE_TAP -> R.string.gesture_triple_tap
        UiBuiltInGesture.LONG_PRESS -> R.string.gesture_long_press
        UiBuiltInGesture.TAP_THEN_LONG_PRESS -> R.string.gesture_tap_then_long_press
        UiBuiltInGesture.DOUBLE_TAP_THEN_LONG_PRESS -> R.string.gesture_double_tap_then_long_press
        null -> null
    }
    return stock?.let { stringResource(it) } ?: profile.presets.firstOrNull { it.id == gesture.presetId }?.name.orEmpty()
}

private val UiGestureMotionPreset.label: Int get() = when (this) {
    UiGestureMotionPreset.SLIDE -> R.string.gesture_motion_slide
    UiGestureMotionPreset.CARDS -> R.string.gesture_motion_cards
    UiGestureMotionPreset.SCALE -> R.string.gesture_motion_scale
    UiGestureMotionPreset.FADE -> R.string.gesture_motion_fade
    UiGestureMotionPreset.NONE -> R.string.gesture_motion_none
}

private val previewMovements = setOf(
    UiBuiltInGesture.SWIPE_LEFT, UiBuiltInGesture.SWIPE_RIGHT, UiBuiltInGesture.SWIPE_UP, UiBuiltInGesture.SWIPE_DOWN,
    UiBuiltInGesture.CIRCLE_CLOCKWISE, UiBuiltInGesture.CIRCLE_COUNTERCLOCKWISE,
)

private val trackChangeActions = setOf(UiGestureActionId.NEXT_TRACK, UiGestureActionId.PREVIOUS_TRACK)
