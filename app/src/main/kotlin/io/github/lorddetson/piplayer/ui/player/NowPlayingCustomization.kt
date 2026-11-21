package io.github.lorddetson.piplayer.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.NowPlayingElement
import io.github.lorddetson.piplayer.settings.NowPlayingPanel
import io.github.lorddetson.piplayer.settings.PlayerPanelState
import io.github.lorddetson.piplayer.settings.nowPlayingPanels
import io.github.lorddetson.piplayer.settings.NowPlayingRow
import io.github.lorddetson.piplayer.ui.customization.PanelItemKind
import io.github.lorddetson.piplayer.ui.customization.PanelItemMenu
import io.github.lorddetson.piplayer.ui.customization.PanelMenuPage
import io.github.lorddetson.piplayer.ui.customization.PanelRestoreButton
import io.github.lorddetson.piplayer.ui.customization.customizeOnHold
import kotlinx.coroutines.launch

internal class NowPlayingCustomization(
    val themeId: String?,
    private val panel: NowPlayingPanel,
    private val available: Set<NowPlayingElement>,
    private val showSecondary: Boolean,
    val edit: ((NowPlayingPanel) -> NowPlayingPanel) -> Unit,
    private val revealSecondary: () -> Unit,
) {
    val hidden = NowPlayingRow.entries.flatMap { panel[it] }.filter { it in panel.hidden && it in available }

    fun shown(row: NowPlayingRow): List<NowPlayingElement> = panel[row].filter {
        it in available && it !in panel.hidden && (showSecondary || it.primary)
    }

    fun destinations(element: NowPlayingElement) = NowPlayingRow.entries.filter { it != panel.rowOf(element) && element.fits(it) }

    fun restore(id: String) {
        val element = NowPlayingElement.byId.getValue(id)
        edit { it.copy(hidden = it.hidden - element) }
        if (!element.primary) revealSecondary()
    }
}

@Composable
internal fun rememberNowPlayingCustomization(
    available: Set<NowPlayingElement>,
    showSecondary: Boolean,
    revealSecondary: () -> Unit,
    onSaveFailed: suspend () -> Unit,
): NowPlayingCustomization {
    val app = LocalContext.current.applicationContext
    val settings = remember(app) { nowPlayingPanels.forContext(app) }
    val state by settings.state.collectAsStateWithLifecycle<PlayerPanelState<NowPlayingPanel>?>(initialValue = null)
    val scope = rememberCoroutineScope()
    val theme = state?.themeId
    return NowPlayingCustomization(theme, state?.panel ?: NowPlayingPanel.DEFAULT, available, showSecondary, { change ->
        if (theme != null) scope.launch { if (!settings.edit(theme, change)) onSaveFailed() }
    }, revealSecondary)
}

@Composable
internal fun PlayerElement(
    editor: NowPlayingCustomization,
    element: NowPlayingElement,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    key(editor.themeId, element) {
        var menu by remember { mutableStateOf<PanelMenuPage?>(null) }
        Box(modifier) {
            content(if (editor.themeId == null) Modifier else Modifier.customizeOnHold { menu = PanelMenuPage.ACTIONS })
            if (menu != null) {
                val visible = NowPlayingRow.entries.filter(element::fits).flatMap(editor::shown).map { it.id }
                val rows = editor.destinations(element)
                PanelItemMenu(
                    menu, { menu = it }, PanelItemKind.PLAYER, element.id, visible, editor.hidden.map { it.id },
                    { NowPlayingElement.byId.getValue(it).label },
                    onMove = { target -> editor.edit { it.move(element, NowPlayingElement.byId.getValue(target)) } },
                    onHide = { editor.edit { it.copy(hidden = it.hidden + element) } },
                    onRestore = editor::restore,
                    canMove = visible.size > 1 || rows.isNotEmpty(),
                    moveOptions = {
                        for (row in rows) {
                            DropdownMenuItem(
                                text = { Text(stringResource(row.moveLabel)) },
                                onClick = {
                                    menu = null
                                    editor.edit { it.moveToEnd(element, row) }
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun RestorePlayerElements(editor: NowPlayingCustomization) {
    key(editor.themeId) {
        PanelRestoreButton(PanelItemKind.PLAYER, editor.hidden.map { it.id }, { NowPlayingElement.byId.getValue(it).label },
            "now_playing_restore", editor::restore)
    }
}

internal val NowPlayingElement.label: Int
    get() = when (this) {
        NowPlayingElement.IDENTIFY -> R.string.identify
        NowPlayingElement.FAVORITE -> R.string.player_element_favorite
        NowPlayingElement.QUEUE -> R.string.queue_open
        NowPlayingElement.MORE -> R.string.page_menu
        NowPlayingElement.COVER -> R.string.player_element_cover
        NowPlayingElement.TRACK_INFO -> R.string.player_element_track
        NowPlayingElement.FORMAT -> R.string.player_element_format
        NowPlayingElement.SEEK -> R.string.player_position
        NowPlayingElement.SHUFFLE -> R.string.player_shuffle
        NowPlayingElement.PREVIOUS -> R.string.player_previous
        NowPlayingElement.PLAY_PAUSE -> R.string.player_element_play_pause
        NowPlayingElement.NEXT -> R.string.player_next
        NowPlayingElement.REPEAT -> R.string.player_repeat
        NowPlayingElement.TIMER -> R.string.player_sleep_timer
        NowPlayingElement.STOP_AFTER -> R.string.player_stop_after_current
        NowPlayingElement.EQUALIZER -> R.string.player_equalizer
    }

private val NowPlayingRow.moveLabel: Int
    get() = when (this) {
        NowPlayingRow.TOP -> R.string.player_move_top
        NowPlayingRow.CONTROLS -> R.string.player_move_controls
        NowPlayingRow.EXTRAS -> R.string.player_move_extras
        NowPlayingRow.CONTENT -> error("Content has no cross-row destination")
    }
