package io.github.lorddetson.piplayer.ui.customization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.UI_CANVAS_MAX_ITEMS
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiPlacementMode

private enum class EditorDialog { ADD, LAYERS, OPTIONS }

@Composable
internal fun UiEditorControls(
    layout: UiCanvasLayout,
    catalog: Map<String, UiCanvasComponent>,
    selected: UiCanvasItem?,
    picked: Set<String>?,
    saving: Boolean,
    onAdd: (UiCanvasComponent) -> Boolean,
    onSelect: (String) -> Unit,
    onGroup: () -> Unit,
    onUngroup: (String) -> Unit,
    onSnap: () -> Unit,
    onPlacement: (UiPlacementMode) -> Boolean,
    onRaise: (String) -> Unit,
    onLower: (String) -> Unit,
    onHide: (String) -> Unit,
    onInspect: () -> Unit,
    onGestures: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var noSpace by remember(dialog) { mutableStateOf(false) }
    val available = catalog.values.filter { candidate -> candidate.addable && layout.items.none { it.id == candidate.id } }
    val selectedId = selected?.id
    val hasSelection = selectedId != null && picked == null && !saving
    UiEditorRing(listOf(
        UiEditorAction(R.drawable.ic_ui_edit_done, R.string.ui_edit_done, "ui_edit_done", !saving && picked == null, onClick = onDone),
        UiEditorAction(R.drawable.ic_add, R.string.ui_edit_add, "ui_edit_add", !saving && picked == null &&
            available.isNotEmpty() && layout.items.size < UI_CANVAS_MAX_ITEMS) { dialog = EditorDialog.ADD },
        when {
            picked == null -> UiEditorAction(R.drawable.ic_ui_edit_group, R.string.ui_edit_group, "ui_edit_group", !saving, onClick = onGroup)
            picked.size >= 2 -> UiEditorAction(R.drawable.ic_ui_edit_done, R.string.ui_edit_finish_group, "ui_edit_group",
                !saving, selected = true, onClick = onGroup)
            else -> UiEditorAction(R.drawable.ic_close, R.string.ui_edit_cancel_group, "ui_edit_group",
                !saving, selected = true, onClick = onGroup)
        },
        UiEditorAction(R.drawable.ic_ui_edit_layers, R.string.ui_edit_layers, "ui_edit_layers", !saving && layout.items.isNotEmpty()) {
            dialog = EditorDialog.LAYERS
        },
        UiEditorAction(R.drawable.ic_settings, R.string.ui_edit_properties, "ui_edit_inspect", hasSelection, onClick = onInspect),
        UiEditorAction(R.drawable.ic_drag_handle, R.string.ui_gestures, "ui_edit_gestures", hasSelection, onClick = onGestures),
        UiEditorAction(R.drawable.ic_ui_edit_hide, R.string.ui_edit_hide, "ui_edit_hide", hasSelection) { selectedId?.let(onHide) },
        UiEditorAction(R.drawable.ic_ui_edit_snap, R.string.ui_edit_snap, "ui_edit_snap", !saving, layout.snapping, onSnap),
        UiEditorAction(R.drawable.ic_more, R.string.ui_edit_options, "ui_edit_options", !saving) { dialog = EditorDialog.OPTIONS },
    ))

    val active = dialog ?: return
    AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text(stringResource(when (active) {
            EditorDialog.ADD -> R.string.ui_edit_add
            EditorDialog.LAYERS -> R.string.ui_edit_layers
            EditorDialog.OPTIONS -> R.string.ui_edit_options
        })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (active) {
                    EditorDialog.ADD -> available.forEach { item ->
                        DropdownMenuItem(text = { Text(item.name()) }, onClick = {
                            if (onAdd(item)) dialog = null else noSpace = true
                        }, modifier = Modifier.testTag("ui_add_" + item.id))
                    }
                    EditorDialog.LAYERS -> {
                        if (hasSelection) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ui_edit_forward)) }, onClick = { onRaise(selectedId) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ui_edit_backward)) }, onClick = { onLower(selectedId) })
                        }
                        layout.items.asReversed().forEach { item ->
                            catalog[item.id]?.let { component ->
                                DropdownMenuItem(text = { Text(component.name()) }, onClick = {
                                    onSelect(item.id)
                                    dialog = null
                                }, modifier = Modifier.testTag("ui_layer_" + item.id))
                            }
                        }
                    }
                    EditorDialog.OPTIONS -> {
                        Text(stringResource(R.string.ui_edit_placement), style = MaterialTheme.typography.titleSmall)
                        UiPlacementMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(stringResource(if (mode == UiPlacementMode.PUSH) R.string.ui_edit_push else R.string.ui_edit_overlap)) },
                                onClick = { noSpace = !onPlacement(mode) },
                                leadingIcon = { RadioButton(selected = layout.placement == mode, onClick = null) },
                                enabled = picked == null,
                                modifier = Modifier.testTag("ui_edit_placement_" + mode.name.lowercase()),
                            )
                        }
                        if (hasSelection && layout.members(selectedId).size > 1) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ui_edit_ungroup)) }, onClick = {
                                onUngroup(selectedId)
                                dialog = null
                            }, modifier = Modifier.testTag("ui_edit_ungroup"))
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_edit_reset)) }, onClick = {
                            onReset()
                            dialog = null
                        }, enabled = picked == null, modifier = Modifier.testTag("ui_edit_reset"))
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_edit_cancel)) }, onClick = onCancel)
                    }
                }
                if (noSpace) Text(stringResource(R.string.ui_edit_no_space), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { dialog = null }, modifier = Modifier.testTag("ui_edit_close_options")) {
                Text(stringResource(R.string.ui_edit_done))
            }
        },
    )
}
