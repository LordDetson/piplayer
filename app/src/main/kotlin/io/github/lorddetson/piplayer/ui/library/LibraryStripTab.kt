package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryButton
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabLayout
import io.github.lorddetson.piplayer.settings.UiLabelDisplay
import io.github.lorddetson.piplayer.settings.librarytabs.BuiltInLibraryTab
import io.github.lorddetson.piplayer.ui.customization.uiLabelDisplay
import io.github.lorddetson.piplayer.ui.customization.PanelItemKind
import io.github.lorddetson.piplayer.ui.customization.PanelItemMenu
import io.github.lorddetson.piplayer.ui.customization.PanelMenuPage
import io.github.lorddetson.piplayer.ui.customization.PanelRestoreButton
import io.github.lorddetson.piplayer.ui.customization.StockUiSlot
import io.github.lorddetson.piplayer.ui.customization.stockHoldUsesNative
import io.github.lorddetson.piplayer.ui.customization.uiGestureRuntimeForStock
import io.github.lorddetson.piplayer.ui.customization.UiControlColor
import io.github.lorddetson.piplayer.ui.customization.UiControlTextAlign
import io.github.lorddetson.piplayer.ui.customization.fillAlignedTextWidth
import io.github.lorddetson.piplayer.ui.customization.UiControlIcon

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
    vertical: Boolean = false,
    onLabelDisplay: ((UiLabelDisplay) -> Unit)? = null,
) {
    var menu by remember { mutableStateOf<PanelMenuPage?>(null) }
    val openMenu = { menu = PanelMenuPage.ACTIONS }
    val componentId = "tab:$id"
    val hold = openMenu.takeIf { canCustomize }
    val useNativeHold = stockHoldUsesNative(componentId, uiGestureRuntimeForStock(componentId))
    StockUiSlot(componentId, onClick = onClick, onHold = hold, externalHold = true) {
        val row = if (vertical) Modifier.fillMaxWidth().heightIn(min = 48.dp) else Modifier.heightIn(min = 48.dp)
        LibraryTabFace(id, selected, onClick, row, onLongClick = hold.takeIf { useNativeHold })
        PanelItemMenu(menu, { menu = it }, PanelItemKind.TAB, id, layout.visible,
            layout.order.filter(layout.hidden::contains), { builtInTabsById.getValue(it).labelResource },
            onMove, onHide.takeIf { layout.visible.size > 1 }, onRestore, onLabelDisplay = onLabelDisplay)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryTabFace(
    id: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.heightIn(min = 48.dp),
    onLongClick: (() -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
) {
    val tab = builtInTabsById.getValue(id)
    val label = stringResource(tab.labelResource)
    val display = uiLabelDisplay("tab:$id")
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier.combinedClickable(
            role = Role.Tab,
            onClick = onClick,
            onLongClickLabel = stringResource(R.string.library_tab_customize),
            onLongClick = onLongClick,
        ).semantics {
            this.selected = selected
            if (display == UiLabelDisplay.ICON_ONLY) contentDescription = label
        }.padding(horizontal = horizontalPadding, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (display != UiLabelDisplay.TEXT_ONLY) {
                UiControlIcon(painterResource(tab.icon), null, Modifier.size(18.dp), tint = color)
            }
            if (display != UiLabelDisplay.ICON_ONLY) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = UiControlColor(color),
                    modifier = Modifier.fillAlignedTextWidth(), textAlign = UiControlTextAlign(), maxLines = 1)
            }
        }
    }
}

private val BuiltInLibraryTab.icon: Int
    get() = when (this) {
        BuiltInLibraryTab.SONGS -> R.drawable.ic_library
        BuiltInLibraryTab.ALBUMS -> R.drawable.ic_album
        BuiltInLibraryTab.ARTISTS -> R.drawable.ic_artist
        BuiltInLibraryTab.FOLDERS -> R.drawable.ic_folder
        BuiltInLibraryTab.PLAYLISTS -> R.drawable.ic_playlist
        BuiltInLibraryTab.GENRES -> R.drawable.ic_genre
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
    val componentId = "library:" + button.id
    val hold = openMenu.takeIf { canCustomize }
    val useNativeHold = stockHoldUsesNative(componentId, uiGestureRuntimeForStock(componentId))
    StockUiSlot(componentId, onClick = onClick, onHold = hold, externalHold = true) {
        LibraryToolbarButtonFace(button, onClick, onLongClick = hold.takeIf { useNativeHold })
        PanelItemMenu(menu, { menu = it }, PanelItemKind.BUTTON, button.id, visible, hidden,
            { LibraryButton.byId.getValue(it).labelResource }, onMove, onHide, onRestore)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryToolbarButtonFace(
    button: LibraryButton,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier.combinedClickable(
            role = Role.Button,
            enabled = enabled,
            onClick = onClick,
            onLongClickLabel = stringResource(R.string.library_button_customize),
            onLongClick = onLongClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        val tint = LocalContentColor.current
        UiControlIcon(painterResource(button.icon), stringResource(button.labelResource), tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

@Composable
internal fun RestoreLibraryButtons(hidden: List<String>, onRestore: (String) -> Unit) {
    PanelRestoreButton(PanelItemKind.BUTTON, hidden, { LibraryButton.byId.getValue(it).labelResource }, "library_panel_add", onRestore)
}
