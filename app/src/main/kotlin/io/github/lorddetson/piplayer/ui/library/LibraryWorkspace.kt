package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.ui.customization.EditableUiPanel
import io.github.lorddetson.piplayer.ui.customization.LocalStockUiSlots
import io.github.lorddetson.piplayer.ui.customization.LocalLibraryWorkspaceEntry
import io.github.lorddetson.piplayer.ui.customization.StockUiSlot
import io.github.lorddetson.piplayer.ui.customization.StockUiSlots
import io.github.lorddetson.piplayer.ui.customization.UiCanvasComponent

internal class LibraryWorkspaceChrome(
    val miniPlayer: @Composable () -> Unit,
    val navigation: @Composable () -> Unit,
)

@Composable
internal fun LibraryWorkspace(
    themeId: String?,
    snackbar: SnackbarHostState,
    onSaveFailed: suspend () -> Unit,
    chrome: LibraryWorkspaceChrome?,
    toolbar: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    header: @Composable (StockUiSlots?) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var needsRecovery by remember(themeId) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneHeight = maxHeight
        val components = buildList {
            add(UiCanvasComponent("workspace:toolbar", R.string.ui_edit_workspace_toolbar, height = 64.dp,
                fillWidth = true, preserveGestures = true, scaleBlock = true, content = toolbar))
            add(UiCanvasComponent("workspace:tabs", R.string.ui_edit_workspace_tabs, height = 64.dp,
                fillWidth = true, tabDirection = true, preserveGestures = true, content = tabs))
            add(UiCanvasComponent("workspace:content", R.string.ui_edit_workspace_content, height = paneHeight * 0.58f,
                fillWidth = true, preserveGestures = true) {
                content(Modifier.fillMaxSize())
            })
            if (chrome != null) {
                add(UiCanvasComponent("workspace:mini_player", R.string.ui_edit_workspace_mini_player, height = 56.dp,
                    fillWidth = true, preserveGestures = true, scaleBlock = true, content = chrome.miniPlayer))
                add(UiCanvasComponent("workspace:navigation", R.string.ui_edit_workspace_navigation, height = 80.dp,
                    fillWidth = true, preserveGestures = true, scaleBlock = true, content = chrome.navigation))
            }
        }
        EditableUiPanel(
            surface = UiSurface.LIBRARY_WORKSPACE,
            themeId = themeId,
            components = components,
            initialRows = components.map { listOf(it.id) },
            height = paneHeight,
            onSaveFailed = onSaveFailed,
            onAppliedIds = { visible ->
                needsRecovery = visible != null && visible.none { it in workspaceEntryPanels }
            },
        ) {
            val owner = LocalStockUiSlots.current
            val edit = LocalLibraryWorkspaceEntry.current
            Scaffold(
                topBar = {
                    if (needsRecovery && edit != null) Column {
                        header(owner)
                        IconButton(onClick = { edit("workspace:content") }) {
                            Icon(painterResource(R.drawable.ic_ui_edit_pen), stringResource(R.string.ui_edit_library_workspace))
                        }
                    } else header(owner)
                },
                bottomBar = {
                    if (chrome != null) Column(Modifier.fillMaxWidth()) {
                        StockUiSlot("workspace:mini_player", content = chrome.miniPlayer)
                        StockUiSlot("workspace:navigation", content = chrome.navigation)
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                StockUiSlot("workspace:content", Modifier.padding(padding).fillMaxSize()) {
                    content(Modifier.fillMaxSize())
                }
            }
        }
    }
}

private val workspaceEntryPanels = setOf("workspace:toolbar", "workspace:tabs", "workspace:navigation")
