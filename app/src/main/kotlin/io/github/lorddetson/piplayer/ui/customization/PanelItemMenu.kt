package io.github.lorddetson.piplayer.ui.customization

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lorddetson.piplayer.R

internal enum class PanelMenuPage { ACTIONS, MOVE, ADD }

internal enum class PanelItemKind(val tag: String, val move: Int, val hide: Int, val add: Int) {
    TAB("library_tab", R.string.library_tab_move, R.string.library_tab_hide, R.string.library_tab_add),
    BUTTON("library_button", R.string.library_button_move, R.string.library_button_hide, R.string.library_button_add),
    PLAYER("player_element", R.string.player_element_move, R.string.player_element_hide, R.string.player_element_restore),
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
    moveOptions: @Composable () -> Unit = {},
) {
    DropdownMenu(expanded = page != null, onDismissRequest = { setPage(null) }) {
        when (page) {
            PanelMenuPage.ACTIONS -> {
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
internal fun RestoreItems(kind: PanelItemKind, hidden: List<String>, label: (String) -> Int, restore: (String) -> Unit) {
    for (target in hidden) {
        DropdownMenuItem(
            text = { Text(stringResource(label(target))) },
            onClick = { restore(target) },
            modifier = Modifier.testTag("${kind.tag}_restore_$target"),
        )
    }
}
