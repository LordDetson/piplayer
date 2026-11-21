package io.github.lorddetson.piplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.BottomDestination
import io.github.lorddetson.piplayer.settings.BottomNavigationLayout
import io.github.lorddetson.piplayer.settings.PlayerPanelState
import io.github.lorddetson.piplayer.settings.bottomNavigationLayouts
import io.github.lorddetson.piplayer.ui.customization.PanelItemKind
import io.github.lorddetson.piplayer.ui.customization.PanelItemMenu
import io.github.lorddetson.piplayer.ui.customization.PanelMenuPage
import io.github.lorddetson.piplayer.ui.customization.PanelRestoreButton
import io.github.lorddetson.piplayer.ui.customization.customizeOnHold
import kotlinx.coroutines.launch

object BottomBarTags {
    const val BAR = "bottom_bar"
    const val LIBRARY = "bottom_library"
    const val SEARCH = "bottom_search"
    const val FIX = "bottom_fix"
}

/** The base route stays selected while visible. Changing the buttons does not change the navigation stack. */
@Composable
internal fun PiPlayerBottomBar(current: NavKey?, onSelect: (NavKey) -> Unit, snackbar: SnackbarHostState) {
    val app = LocalContext.current.applicationContext
    val settings = remember(app) { bottomNavigationLayouts.forContext(app) }
    val state by settings.state.collectAsStateWithLifecycle<PlayerPanelState<BottomNavigationLayout>?>(initialValue = null)
    val theme = state?.themeId
    val layout = state?.panel ?: BottomNavigationLayout()
    val scope = rememberCoroutineScope()
    val failure = stringResource(R.string.bottom_navigation_save_failed)

    fun edit(change: (BottomNavigationLayout) -> BottomNavigationLayout) {
        if (theme != null) scope.launch {
            if (!settings.edit(theme, change)) snackbar.showSnackbar(failure)
        }
    }
    fun restore(id: String) = edit { it.copy(hidden = it.hidden - BottomDestination.byId.getValue(id)) }

    val visible = layout.visible.map(BottomDestination::id)
    val hidden = layout.order.filter(layout.hidden::contains).map(BottomDestination::id)
    NavigationBar(Modifier.testTag(BottomBarTags.BAR)) {
        for (item in layout.visible) {
            key(theme, item) {
                BottomBarItem(item, current, theme != null, visible, hidden, onSelect, ::edit, ::restore)
            }
        }
        key(theme) {
            PanelRestoreButton(PanelItemKind.BOTTOM_NAVIGATION, hidden, ::destinationLabel, "bottom_nav_recovery", ::restore)
        }
    }
}

@Composable
private fun RowScope.BottomBarItem(
    item: BottomDestination,
    current: NavKey?,
    canCustomize: Boolean,
    visible: List<String>,
    hidden: List<String>,
    onSelect: (NavKey) -> Unit,
    edit: ((BottomNavigationLayout) -> BottomNavigationLayout) -> Unit,
    restore: (String) -> Unit,
) {
    var menu by remember { mutableStateOf<PanelMenuPage?>(null) }
    val hold = if (canCustomize) Modifier.customizeOnHold { menu = PanelMenuPage.ACTIONS } else Modifier
    NavigationBarItem(
        selected = item.route == current,
        onClick = { onSelect(item.route) },
        icon = {
            Box {
                Icon(painterResource(item.icon), contentDescription = null)
                if (menu != null) {
                    val hide = { edit { it.hide(item) } }
                    PanelItemMenu(
                        menu, { menu = it }, PanelItemKind.BOTTOM_NAVIGATION, item.id, visible, hidden, ::destinationLabel,
                        onMove = { target -> edit { it.move(item, BottomDestination.byId.getValue(target)) } },
                        onHide = hide.takeIf { visible.size > 1 },
                        onRestore = restore,
                    )
                }
            }
        },
        label = { Text(stringResource(item.labelResource)) },
        modifier = hold.testTag(item.tag),
    )
}

internal val BottomDestination.labelResource: Int
    get() = when (this) {
        BottomDestination.LIBRARY -> R.string.bottom_library
        BottomDestination.SEARCH -> R.string.bottom_search
        BottomDestination.FIX -> R.string.bottom_fix
    }

private fun destinationLabel(id: String): Int = BottomDestination.byId.getValue(id).labelResource

private val BottomDestination.route: NavKey
    get() = when (this) {
        BottomDestination.LIBRARY -> SongsRoute
        BottomDestination.SEARCH -> SearchRoute
        BottomDestination.FIX -> FixRoute
    }

private val BottomDestination.icon: Int
    get() = when (this) {
        BottomDestination.LIBRARY -> R.drawable.ic_library
        BottomDestination.SEARCH -> R.drawable.ic_search
        BottomDestination.FIX -> R.drawable.ic_fix
    }

private val BottomDestination.tag: String
    get() = when (this) {
        BottomDestination.LIBRARY -> BottomBarTags.LIBRARY
        BottomDestination.SEARCH -> BottomBarTags.SEARCH
        BottomDestination.FIX -> BottomBarTags.FIX
    }
