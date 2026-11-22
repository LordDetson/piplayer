package io.github.lorddetson.piplayer.ui.customization

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.UiLabelDisplay

internal enum class PanelMenuPage { ACTIONS, MOVE, ADD, LABEL }

internal fun workspaceSlotFor(id: String): String? = when (id.substringBefore(':')) {
    "tab" -> "workspace:tabs"
    "library" -> "workspace:toolbar"
    "mini" -> "workspace:mini_player"
    "nav" -> "workspace:navigation"
    else -> null
}

internal enum class PanelItemKind(val tag: String, val move: Int, val hide: Int, val add: Int) {
    TAB("library_tab", R.string.library_tab_move, R.string.library_tab_hide, R.string.library_tab_add),
    BUTTON("library_button", R.string.library_button_move, R.string.library_button_hide, R.string.library_button_add),
    PLAYER("player_element", R.string.player_element_move, R.string.player_element_hide, R.string.player_element_restore),
    MINI_PLAYER("mini_element", R.string.player_element_move, R.string.player_element_hide, R.string.player_element_restore),
    BOTTOM_NAVIGATION("bottom_nav", R.string.library_button_move, R.string.library_button_hide, R.string.library_button_add),
}

@Composable
internal fun PanelItemMenu(
    page: PanelMenuPage?,
    setPage: (PanelMenuPage?) -> Unit,
    kind: PanelItemKind,
    id: String,
    visible: List<String>,
    hidden: List<String>,
    label: (String) -> Int,
    onMove: (String) -> Unit,
    onHide: (() -> Unit)?,
    onRestore: (String) -> Unit,
    canMove: Boolean = visible.size > 1,
    onLabelDisplay: ((UiLabelDisplay) -> Unit)? = null,
    moveOptions: @Composable () -> Unit = {},
) {
    val enterEdit = LocalUiEditEntry.current
    val workspace = LocalLibraryWorkspaceEntry.current
    DropdownMenu(expanded = page != null, onDismissRequest = { setPage(null) }) {
        when (page) {
            PanelMenuPage.ACTIONS -> {
                val prefix = when (kind) {
                    PanelItemKind.TAB -> "tab"
                    PanelItemKind.BUTTON -> "library"
                    PanelItemKind.PLAYER -> "now"
                    PanelItemKind.MINI_PLAYER -> "mini"
                    PanelItemKind.BOTTOM_NAVIGATION -> "nav"
                }
                if (enterEdit != null && enterEdit !== workspace) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_edit_mode)) },
                        onClick = { setPage(null); enterEdit(prefix + ":" + id) },
                        modifier = Modifier.testTag("ui_edit_mode"),
                    )
                }
                val workspaceSlot = workspaceSlotFor(prefix + ":" + id)
                if (workspace != null && workspaceSlot != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_edit_library_workspace)) },
                        onClick = {
                            setPage(null)
                            workspace(workspaceSlot)
                        },
                        modifier = Modifier.testTag("ui_edit_library_workspace"),
                    )
                }
                if (onLabelDisplay != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_edit_label_display)) },
                        onClick = { setPage(PanelMenuPage.LABEL) },
                        modifier = Modifier.testTag("ui_edit_tab_label"),
                    )
                }
                if (canMove) {
                    DropdownMenuItem(
                        text = { Text(stringResource(kind.move)) },
                        onClick = { setPage(PanelMenuPage.MOVE) },
                        modifier = Modifier.testTag("${kind.tag}_move"),
                    )
                }
                if (onHide != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(kind.hide)) },
                        onClick = {
                            setPage(null)
                            onHide()
                        },
                        modifier = Modifier.testTag("${kind.tag}_hide"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(kind.add)) },
                    onClick = { setPage(PanelMenuPage.ADD) },
                    enabled = hidden.isNotEmpty(),
                    modifier = Modifier.testTag("${kind.tag}_add"),
                )
            }
            PanelMenuPage.LABEL -> UiLabelDisplay.entries.forEach { display ->
                DropdownMenuItem(
                    text = { Text(stringResource(display.labelResource)) },
                    onClick = {
                        onLabelDisplay?.invoke(display)
                        setPage(null)
                    },
                    modifier = Modifier.testTag("ui_tab_label_" + display.name.lowercase()),
                )
            }
            PanelMenuPage.MOVE -> {
                moveOptions()
                visible.forEachIndexed { index, target ->
                    if (target != id) {
                        val direction = if (index < visible.indexOf(id)) R.string.library_tab_move_before
                        else R.string.library_tab_move_after
                        DropdownMenuItem(
                            text = { Text(stringResource(direction, stringResource(label(target)))) },
                            onClick = {
                                setPage(null)
                                onMove(target)
                            },
                            modifier = Modifier.testTag("${kind.tag}_move_to_$target"),
                        )
                    }
                }
            }
            PanelMenuPage.ADD -> RestoreItems(kind, hidden, label) {
                setPage(null)
                onRestore(it)
            }
            null -> Unit
        }
    }
}

@Composable
private fun RestoreItems(kind: PanelItemKind, hidden: List<String>, label: (String) -> Int, restore: (String) -> Unit) {
    for (target in hidden) {
        DropdownMenuItem(
            text = { Text(stringResource(label(target))) },
            onClick = { restore(target) },
            modifier = Modifier.testTag("${kind.tag}_restore_$target"),
        )
    }
}

@Composable
internal fun PanelRestoreButton(
    kind: PanelItemKind,
    hidden: List<String>,
    label: (String) -> Int,
    tag: String,
    onRestore: (String) -> Unit,
) {
    if (hidden.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) {
            Icon(painterResource(R.drawable.ic_add), stringResource(kind.add))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RestoreItems(kind, hidden, label) {
                open = false
                onRestore(it)
            }
        }
    }
}
