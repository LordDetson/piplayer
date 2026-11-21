package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryButton
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabLayout

private enum class PanelMenuPage { ACTIONS, MOVE, ADD }

private enum class PanelItemKind(val tag: String, val move: Int, val hide: Int, val add: Int) {
    TAB("library_tab", R.string.library_tab_move, R.string.library_tab_hide, R.string.library_tab_add),
    BUTTON("library_button", R.string.library_button_move, R.string.library_button_hide, R.string.library_button_add),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryStripTab(
    id: String,
    selected: Boolean,
    layout: LibraryTabLayout,
    canCustomize: Boolean,
    onClick: () -> Unit,
    onMove: (String) -> Unit,
    onHide: () -> Unit,
    onRestore: (String) -> Unit,
) {
    var menu by remember { mutableStateOf<PanelMenuPage?>(null) }
    val openMenu = { menu = PanelMenuPage.ACTIONS }
    val label = stringResource(builtInTabsById.getValue(id).labelResource)
    Box {
        Box(
            Modifier.heightIn(min = 48.dp)
                .combinedClickable(
                    role = Role.Tab,
                    onClick = onClick,
                    onLongClickLabel = stringResource(R.string.library_tab_customize),
                    onLongClick = openMenu.takeIf { canCustomize },
                )
                .semantics { this.selected = selected }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        PanelItemMenu(menu, { menu = it }, PanelItemKind.TAB, id, layout.visible,
            layout.order.filter(layout.hidden::contains), { builtInTabsById.getValue(it).labelResource },
            onMove, onHide.takeIf { layout.visible.size > 1 }, onRestore)
    }
}

internal val LibraryButton.labelResource: Int
    get() = when (this) {
        LibraryButton.SEARCH -> R.string.library_search
        LibraryButton.SORT -> R.string.library_sort
        LibraryButton.SETTINGS -> R.string.settings_open
    }

private val LibraryButton.icon: Int
    get() = when (this) {
        LibraryButton.SEARCH -> R.drawable.ic_search
        LibraryButton.SORT -> R.drawable.ic_sort
        LibraryButton.SETTINGS -> R.drawable.ic_settings
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryToolbarButton(
    button: LibraryButton,
    visible: List<String>,
    hidden: List<String>,
    canCustomize: Boolean,
    onClick: () -> Unit,
    onMove: (String) -> Unit,
    onHide: () -> Unit,
    onRestore: (String) -> Unit,
) {
    var menu by remember { mutableStateOf<PanelMenuPage?>(null) }
    val openMenu = { menu = PanelMenuPage.ACTIONS }
    Box {
        Box(
            Modifier.size(48.dp).combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.library_button_customize),
                onLongClick = openMenu.takeIf { canCustomize },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(button.icon), stringResource(button.labelResource))
        }
        PanelItemMenu(menu, { menu = it }, PanelItemKind.BUTTON, button.id, visible, hidden,
            { LibraryButton.byId.getValue(it).labelResource }, onMove, onHide, onRestore)
    }
}

@Composable
internal fun RestoreLibraryButtons(hidden: List<String>, onRestore: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("library_panel_add")) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.library_button_add))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RestoreItems(PanelItemKind.BUTTON, hidden, { LibraryButton.byId.getValue(it).labelResource }) {
                open = false
                onRestore(it)
            }
        }
    }
}

@Composable
private fun PanelItemMenu(
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
) {
    DropdownMenu(expanded = page != null, onDismissRequest = { setPage(null) }) {
        when (page) {
            PanelMenuPage.ACTIONS -> {
                if (visible.size > 1) {
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
            PanelMenuPage.MOVE -> visible.forEachIndexed { index, target ->
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
