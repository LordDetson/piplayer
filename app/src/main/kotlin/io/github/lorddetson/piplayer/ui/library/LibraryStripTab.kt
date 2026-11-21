package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import io.github.lorddetson.piplayer.ui.customization.PanelItemKind
import io.github.lorddetson.piplayer.ui.customization.PanelItemMenu
import io.github.lorddetson.piplayer.ui.customization.PanelMenuPage
import io.github.lorddetson.piplayer.ui.customization.PanelRestoreButton

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
    PanelRestoreButton(PanelItemKind.BUTTON, hidden, { LibraryButton.byId.getValue(it).labelResource }, "library_panel_add", onRestore)
}
