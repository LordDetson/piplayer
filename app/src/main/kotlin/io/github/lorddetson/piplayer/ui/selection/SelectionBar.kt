package io.github.lorddetson.piplayer.ui.selection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry
import io.github.lorddetson.piplayer.ui.songs.label

/** Test tags of the selection bar (docs/library.md §20.2). */
object SelectionTags {
    const val BAR = "selection_bar"
    const val TITLE = "selection_title"
    const val MENU = "selection_menu"
}

/**
 * The selection bar that takes the top bar's place while selecting (docs/library.md §20.2, 5): close, "<N> selected" as a
 * polite live region, "Select all" or "Deselect all" when every row shown is selected, and "More options" with [menu],
 * whose entries close it by the function they are given before they act.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(
    state: SelectionUi<*>,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                // Before the first loaded list an "all except" has no count yet (docs/library.md §20.2, 8).
                state.count?.let { pluralStringResource(R.plurals.selection_count, it, it) } ?: stringResource(R.string.selection_count_waiting),
                modifier = Modifier.testTag(SelectionTags.TITLE).semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.selection_close)) }
        },
        actions = {
            if (state.everything) {
                TextButton(onClick = onDeselectAll) { Text(stringResource(R.string.selection_deselect_all)) }
            } else {
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.selection_select_all)) }
            }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag(SelectionTags.MENU)) {
                    Icon(painterResource(R.drawable.ic_more), stringResource(R.string.page_menu))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
            }
        },
        modifier = Modifier.testTag(SelectionTags.BAR),
    )
}

/** The entries of a selection's menu (docs/library.md §20.3); one that cannot act now is shown disabled. */
@Composable
fun SelectionMenuItems(entries: List<TrackMenuEntry>, close: () -> Unit, onEntry: (TrackMenuEntry) -> Unit) {
    for (entry in entries) {
        DropdownMenuItem(
            text = {
                Text(if (entry == TrackMenuEntry.Play) stringResource(R.string.selection_replace_queue) else label(entry))
            },
            enabled = entry !is TrackMenuEntry.FavoriteUnavailable,
            onClick = {
                close()
                onEntry(entry)
            },
        )
    }
}

/** While [selecting], back ends selection and does nothing else (docs/library.md §20.4). */
@Composable
fun SelectionBack(selecting: Boolean, onEnd: () -> Unit) {
    BackHandler(enabled = selecting, onBack = onEnd)
}

/** A row's part in a selection: whether the list is [selecting], whether the row is [selected], and how it is toggled. */
class RowSelection(val selecting: Boolean, val selected: Boolean, val toggle: () -> Unit)

/** The check box a row shows before its cover while selecting; it says nothing itself, the row does. */
@Composable
fun SelectionCheck(row: RowSelection?) {
    if (row?.selecting == true) Checkbox(checked = row.selected, onCheckedChange = null)
}

/**
 * A row's click (docs/library.md §20.2): outside selection a tap does [onClick] (labelled [clickLabel]) and a long press,
 * labelled "Select" and also offered as an accessibility action, starts selection; while selecting a tap or a long press
 * selects or deselects the row, which has the role of a check box and says whether it is selected. Without [row] the
 * row only does [onClick]. The row's own accessibility [actions] (moves) are offered only outside selection, since rows do
 * not move while selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.selectableRow(
    row: RowSelection?,
    clickLabel: String,
    onClick: () -> Unit,
    actions: List<CustomAccessibilityAction> = emptyList(),
): Modifier {
    val select = stringResource(R.string.selection_select)
    val deselect = stringResource(R.string.selection_deselect)
    if (row == null) {
        return combinedClickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .semantics { if (actions.isNotEmpty()) customActions = actions }
    }
    val toggleLabel = if (row.selected) deselect else select
    return combinedClickable(
        onClickLabel = if (row.selecting) toggleLabel else clickLabel,
        role = if (row.selecting) Role.Checkbox else Role.Button,
        onLongClickLabel = if (row.selecting) toggleLabel else select,
        onLongClick = row.toggle,
        onClick = if (row.selecting) row.toggle else onClick,
    ).semantics {
        if (row.selecting) {
            toggleableState = ToggleableState(row.selected)
        } else {
            customActions = actions + CustomAccessibilityAction(select) { row.toggle(); true }
        }
    }
}
