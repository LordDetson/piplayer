package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.customtabs.CustomTabRecord
import io.github.lorddetson.piplayer.settings.librarytabs.BuiltInLibraryTab
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabWrite
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal val builtInTabsById = BuiltInLibraryTab.entries.associateBy(BuiltInLibraryTab::id)

internal val BuiltInLibraryTab.labelResource: Int
    get() = when (this) {
        BuiltInLibraryTab.SONGS -> R.string.tab_songs
        BuiltInLibraryTab.ALBUMS -> R.string.tab_albums
        BuiltInLibraryTab.ARTISTS -> R.string.tab_artists
        BuiltInLibraryTab.PLAYLISTS -> R.string.tab_playlists
        BuiltInLibraryTab.GENRES -> R.string.tab_genres
        BuiltInLibraryTab.FOLDERS -> R.string.tab_folders
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryTabEditor(
    layout: LibraryTabLayout,
    settings: LibraryTabsSettings,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onSaveFailed: suspend () -> Unit,
    canChangeVisibility: (String, Boolean) -> Boolean,
    customTabs: Map<String, CustomTabRecord>,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (CustomTabRecord) -> Unit,
    onDelete: (CustomTabRecord) -> Unit,
) {
    val latestLayout by rememberUpdatedState(layout)
    var shown by remember { mutableStateOf(layout.order) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(layout.order) {
        if (!dragging) shown = layout.order
    }
    val list = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        val fromIndex = shown.indexOfFirst { it == from.key }
        val toIndex = shown.indexOfFirst { it == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            shown = shown.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }
    fun move(id: String, place: Int) {
        scope.launch {
            if (settings.move(id, place) != LibraryTabWrite.STORED) {
                shown = latestLayout.order
                onSaveFailed()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.testTag("library_tab_editor")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.library_tabs_manage),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onCreate, modifier = Modifier.testTag("custom_tab_create")) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.custom_tab_create))
                }
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.library_tabs_close))
                }
            }
            val moveUp = stringResource(R.string.library_tabs_move_up)
            val moveDown = stringResource(R.string.library_tabs_move_down)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), state = list) {
                itemsIndexed(shown.filter { it in layout.order }, key = { _, id -> id }) { index, id ->
                    ReorderableItem(reorder, key = id) { movingRow ->
                        val custom = customTabs[id]
                        val label = custom?.definition?.name ?: stringResource(builtInTabsById.getValue(id).labelResource)
                        val switchLabel = stringResource(R.string.library_tabs_show, label)
                        val visible = id !in layout.hidden
                        val canHide = !visible || layout.visible.size > 1
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                Icon(
                                    painterResource(R.drawable.ic_drag_handle),
                                    contentDescription = null,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                        .testTag("library_tab_editor_handle_$id")
                                        .draggableHandle(
                                            onDragStarted = { dragging = true },
                                            onDragStopped = {
                                                dragging = false
                                                val place = shown.indexOf(id)
                                                if (place >= 0 && place != latestLayout.order.indexOf(id)) move(id, place)
                                            },
                                        ),
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = visible,
                                        enabled = canHide,
                                        onCheckedChange = { show ->
                                            if (!canChangeVisibility(id, show)) onDismiss()
                                            else scope.launch {
                                                if (settings.setVisible(id, show) != LibraryTabWrite.STORED) onSaveFailed()
                                            }
                                        },
                                        modifier = Modifier.semantics { contentDescription = switchLabel },
                                    )
                                    if (custom != null) {
                                        CustomTabEditorMenu(custom, visible, onOpen, onEdit, onDelete)
                                    }
                                }
                            },
                            tonalElevation = if (movingRow) 4.dp else 0.dp,
                            shadowElevation = if (movingRow) 4.dp else 0.dp,
                            modifier = Modifier.testTag("library_tab_editor_row_$id").semantics(mergeDescendants = true) {
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(moveUp) { move(id, index - 1); true })
                                    if (index < shown.lastIndex) {
                                        add(CustomAccessibilityAction(moveDown) { move(id, index + 1); true })
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomTabEditorMenu(
    tab: CustomTabRecord,
    visible: Boolean,
    onOpen: (String) -> Unit,
    onEdit: (CustomTabRecord) -> Unit,
    onDelete: (CustomTabRecord) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.testTag("custom_tab_actions_${tab.definition.id}"),
        ) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.custom_tab_actions, tab.definition.name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (visible) DropdownMenuItem(
                text = { Text(stringResource(R.string.custom_tab_open)) },
                onClick = { open = false; onOpen(tab.definition.id) },
                modifier = Modifier.testTag("custom_tab_open"),
            )
            CustomTabMenuItems(tab, onEdit, onDelete) { open = false }
        }
    }
}

@Composable
internal fun CustomTabMenuItems(
    tab: CustomTabRecord,
    onEdit: (CustomTabRecord) -> Unit,
    onDelete: (CustomTabRecord) -> Unit,
    dismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.custom_tab_edit)) },
        onClick = { dismiss(); onEdit(tab) },
        modifier = Modifier.testTag("custom_tab_edit"),
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.custom_tab_delete)) },
        onClick = { dismiss(); onDelete(tab) },
        modifier = Modifier.testTag("custom_tab_delete"),
    )
}
