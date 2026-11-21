package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabLayout

private enum class TabMenu { ACTIONS, MOVE, ADD }

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
    var menu by remember { mutableStateOf<TabMenu?>(null) }
    val openMenu = { menu = TabMenu.ACTIONS }
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
        DropdownMenu(expanded = menu != null, onDismissRequest = { menu = null }) {
            when (menu) {
                TabMenu.ACTIONS -> {
                    if (layout.visible.size > 1) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_tab_move)) },
                            onClick = { menu = TabMenu.MOVE },
                            modifier = Modifier.testTag("library_tab_move"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_tab_hide)) },
                            onClick = {
                                menu = null
                                onHide()
                            },
                            modifier = Modifier.testTag("library_tab_hide"),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_tab_add)) },
                        onClick = { menu = TabMenu.ADD },
                        enabled = layout.hidden.isNotEmpty(),
                        modifier = Modifier.testTag("library_tab_add"),
                    )
                }
                TabMenu.MOVE -> layout.visible.forEachIndexed { index, target ->
                    if (target != id) {
                        val targetLabel = stringResource(builtInTabsById.getValue(target).labelResource)
                        val direction = if (index < layout.visible.indexOf(id)) R.string.library_tab_move_before
                        else R.string.library_tab_move_after
                        DropdownMenuItem(
                            text = { Text(stringResource(direction, targetLabel)) },
                            onClick = {
                                menu = null
                                onMove(target)
                            },
                            modifier = Modifier.testTag("library_tab_move_to_$target"),
                        )
                    }
                }
                TabMenu.ADD -> layout.order.filter(layout.hidden::contains).forEach { target ->
                    val targetLabel = stringResource(builtInTabsById.getValue(target).labelResource)
                    DropdownMenuItem(
                        text = { Text(targetLabel) },
                        onClick = {
                            menu = null
                            onRestore(target)
                        },
                        modifier = Modifier.testTag("library_tab_restore_$target"),
                    )
                }
                null -> Unit
            }
        }
    }
}
