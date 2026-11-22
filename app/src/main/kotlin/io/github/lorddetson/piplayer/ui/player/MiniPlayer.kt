package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.MiniPlayerElement
import io.github.lorddetson.piplayer.settings.MiniPlayerPanel
import io.github.lorddetson.piplayer.settings.PlayerPanelState
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.miniPlayerPanels
import io.github.lorddetson.piplayer.ui.customization.EditableUiPanel
import io.github.lorddetson.piplayer.ui.customization.PanelItemKind
import io.github.lorddetson.piplayer.ui.customization.PanelItemMenu
import io.github.lorddetson.piplayer.ui.customization.PanelMenuPage
import io.github.lorddetson.piplayer.ui.customization.PanelRestoreButton
import io.github.lorddetson.piplayer.ui.customization.UiCanvasComponent
import io.github.lorddetson.piplayer.ui.customization.UiControlColor
import io.github.lorddetson.piplayer.ui.customization.customizeOnHold
import kotlinx.coroutines.launch

/**
 * The mini player at the bottom of the list (docs/playback.md §3.2): nothing while the queue of [player] is empty. At the
 * bottom edge of the window its background reaches that edge and its content sits above the navigation bar; above the
 * bottom bar of the top-level places (PIM-18) it gives the inset up ([bottomInset] false) and the bar below keeps the
 * content clear of the navigation bar.
 */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(
    player: Player?,
    cover: ImageBitmap?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    coverName: String? = null,
    bottomInset: Boolean = true,
    snackbar: SnackbarHostState? = null,
) {
    val item = rememberCurrentItemState(player)
    if (player == null || item.mediaItem == null) return
    val metadata = item.mediaMetadata
    val app = LocalContext.current.applicationContext
    val settings = remember(app) { miniPlayerPanels.forContext(app) }
    val state by settings.state.collectAsStateWithLifecycle<PlayerPanelState<MiniPlayerPanel>?>(initialValue = null)
    val theme = state?.themeId
    val panel = state?.panel ?: MiniPlayerPanel()
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.mini_player_panel_save_failed)
    fun edit(change: (MiniPlayerPanel) -> MiniPlayerPanel) {
        if (theme != null) scope.launch {
            if (!settings.edit(theme, change)) snackbar?.showSnackbar(saveFailed)
        }
    }
    fun restore(id: String) = edit { it.copy(hidden = it.hidden - MiniPlayerElement.byId.getValue(id)) }
    val hidden = MiniPlayerElement.entries.filter(panel.hidden::contains).map(MiniPlayerElement::id)
    val artwork: @Composable (Modifier) -> Unit = { press ->
        CoverImage(cover, press.clip(RoundedCornerShape(6.dp)), placeholderName = coverName)
    }
    val details: @Composable (Modifier) -> Unit = { press ->
        Column(press.fillMaxWidth()) {
            Text(
                text = metadata.title?.toString().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = UiControlColor(Color.Unspecified),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metadata.artist?.toString() ?: stringResource(R.string.songs_unknown_artist),
                style = MaterialTheme.typography.bodyMedium,
                color = UiControlColor(MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val openLabel = stringResource(R.string.player_open_now_playing)
    val openTrack = Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
    val components = listOf(
        UiCanvasComponent(MiniPlayerElement.COVER.canvasId, R.string.player_element_cover) {
            artwork(Modifier.fillMaxSize().then(openTrack))
        },
        UiCanvasComponent(MiniPlayerElement.TRACK_INFO.canvasId, R.string.player_element_track, fillWidth = true) {
            details(openTrack)
        },
        UiCanvasComponent(MiniPlayerElement.PLAY_PAUSE.canvasId, R.string.player_element_play_pause, customizableIcon = true) {
            PlayPauseButton(player, Modifier.fillMaxSize())
        },
        UiCanvasComponent(MiniPlayerElement.PROGRESS.canvasId, R.string.player_position, height = 8.dp, fillWidth = true) {
            Box(Modifier.fillMaxSize().testTag("mini_player_progress"), contentAlignment = Alignment.Center) {
                ProgressLine(player, item.durationMs)
            }
        },
    )
    val trackRow = listOf(panel.visible.map { it.canvasId })
    val progressRow = listOf(listOf(MiniPlayerElement.PROGRESS.canvasId))
    val initialRows = when {
        MiniPlayerElement.PROGRESS in panel.hidden -> trackRow
        panel.progressAtBottom -> trackRow + progressRow
        else -> progressRow + trackRow
    }
    val progress: @Composable () -> Unit = {
        if (MiniPlayerElement.PROGRESS !in panel.hidden) {
            MiniPlayerItem(MiniPlayerElement.PROGRESS, theme, panel, hidden, ::edit, ::restore, Modifier.fillMaxWidth()) { press ->
                Box(
                    Modifier.fillMaxWidth().height(8.dp).then(press).testTag("mini_player_progress"),
                    contentAlignment = if (panel.progressAtBottom) Alignment.BottomCenter else Alignment.TopCenter,
                ) { ProgressLine(player, item.durationMs) }
            }
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag(PlayerTags.MINI_PLAYER),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(if (bottomInset) Modifier.windowInsetsPadding(barInsets) else Modifier) {
            EditableUiPanel(
                surface = UiSurface.MINI_PLAYER,
                themeId = theme,
                components = components,
                initialRows = initialRows.filter { it.isNotEmpty() },
                height = 104.dp,
                onSaveFailed = { snackbar?.showSnackbar(saveFailed) },
                canvasChrome = {
                    IconButton(onClick = onOpen) {
                        Icon(painterResource(R.drawable.ic_player_collapse), openLabel, Modifier.rotate(180f))
                    }
                },
            ) {
                Column {
                    if (!panel.progressAtBottom) progress()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .then(openTrack)
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (element in panel.visible) {
                            val width = if (element == MiniPlayerElement.TRACK_INFO) Modifier.weight(1f) else Modifier
                            MiniPlayerItem(element, theme, panel, hidden, ::edit, ::restore, width) { press ->
                                when (element) {
                                    MiniPlayerElement.COVER -> artwork(press.size(48.dp))
                                    MiniPlayerElement.TRACK_INFO -> details(press)
                                    MiniPlayerElement.PLAY_PAUSE -> PlayPauseButton(player, press)
                                    MiniPlayerElement.PROGRESS -> error("Progress belongs at the edge of the mini player")
                                }
                            }
                        }
                        if (MiniPlayerElement.TRACK_INFO !in panel.visible) Spacer(Modifier.weight(1f))
                        key(theme) {
                            PanelRestoreButton(PanelItemKind.MINI_PLAYER, hidden, ::miniPlayerLabel, "mini_player_restore", ::restore)
                        }
                    }
                    if (panel.progressAtBottom) progress()
                }
            }
        }
    }
}

private val MiniPlayerElement.canvasId: String get() = "mini:$id"

@Composable
private fun MiniPlayerItem(
    element: MiniPlayerElement,
    theme: String?,
    panel: MiniPlayerPanel,
    hidden: List<String>,
    edit: ((MiniPlayerPanel) -> MiniPlayerPanel) -> Unit,
    restore: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    key(theme, element) {
        var menu by remember { mutableStateOf<PanelMenuPage?>(null) }
        Box(modifier) {
            content(if (theme == null) Modifier else Modifier.customizeOnHold { menu = PanelMenuPage.ACTIONS })
            if (menu != null) {
                val progress = element == MiniPlayerElement.PROGRESS
                PanelItemMenu(
                    menu, { menu = it }, PanelItemKind.MINI_PLAYER, element.id,
                    if (progress) emptyList() else panel.visible.map(MiniPlayerElement::id), hidden, ::miniPlayerLabel,
                    onMove = { target -> edit { it.move(element, MiniPlayerElement.byId.getValue(target)) } },
                    onHide = { edit { it.copy(hidden = it.hidden + element) } },
                    onRestore = restore,
                    canMove = progress || panel.visible.size > 1,
                    moveOptions = {
                        if (progress) {
                            val below = !panel.progressAtBottom
                            DropdownMenuItem(
                                text = { Text(stringResource(if (below) R.string.mini_progress_bottom else R.string.mini_progress_top)) },
                                modifier = Modifier.testTag(if (below) "mini_progress_bottom" else "mini_progress_top"),
                                onClick = {
                                    menu = null
                                    edit { it.copy(progressAtBottom = below) }
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun miniPlayerLabel(id: String): Int = when (MiniPlayerElement.byId.getValue(id)) {
    MiniPlayerElement.COVER -> R.string.player_element_cover
    MiniPlayerElement.TRACK_INFO -> R.string.player_element_track
    MiniPlayerElement.PLAY_PAUSE -> R.string.player_element_play_pause
    MiniPlayerElement.PROGRESS -> R.string.player_position
}

private val barInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

/**
 * How far the current song has played, along the top edge. Hidden from accessibility services: inside the bar it would
 * be read as a bare percentage.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ProgressLine(player: Player, durationMs: Long) {
    var widthPx by remember { mutableIntStateOf(0) }
    val progress = rememberProgressTicks(player, progressTicks(widthPx, durationMs))
    LinearProgressIndicator(
        progress = { progress.currentPositionProgress },
        color = UiControlColor(ProgressIndicatorDefaults.linearColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .onSizeChanged { widthPx = it.width }
            .clearAndSetSemantics {},
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
