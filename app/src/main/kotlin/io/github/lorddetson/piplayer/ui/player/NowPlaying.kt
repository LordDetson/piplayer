package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.NowPlayingElement
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.NowPlayingRow
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.ui.customization.EditableUiPanel
import io.github.lorddetson.piplayer.ui.customization.UiCanvasComponent
import io.github.lorddetson.piplayer.ui.customization.UiControlColor
import io.github.lorddetson.piplayer.ui.customization.UiControlTextAlign
import io.github.lorddetson.piplayer.ui.customization.fillAlignedTextWidth
import io.github.lorddetson.piplayer.ui.customization.UiControlIcon
import io.github.lorddetson.piplayer.ui.details.PlayingBadge
import io.github.lorddetson.piplayer.ui.identification.IdentifyButton
import io.github.lorddetson.piplayer.ui.identification.LocalIdentify

/**
 * Now Playing (docs/playback.md §3.3): the current song of [player] with its [cover] and every control. It keeps its
 * content inside the safe drawing area and its sides out of the system's back gesture areas.
 */
@OptIn(UnstableApi::class)
@Composable
fun NowPlaying(
    player: Player?,
    cover: ImageBitmap?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    controls: ExtraControls = ExtraControls(),
    onOpenArtist: (() -> Unit)? = null,
    onOpenAlbum: (() -> Unit)? = null,
    onOpenQueue: (() -> Unit)? = null,
    coverName: String? = null,
    /** The current song's favorite toggle (docs/library.md §15.2); null for an opened file or no song. */
    favorite: FavoriteState? = null,
    /** The entries of "More options", the current song's track menu (docs/playback.md §9.1); null hides the button. */
    more: (@Composable (close: () -> Unit) -> Unit)? = null,
    layout: NowPlayingLayout = NowPlayingLayout.STANDARD,
    onPanelSaveFailed: suspend () -> Unit = {},
) {
    val metadata = rememberCurrentItemState(player).mediaMetadata
    val pane = stringResource(R.string.player_now_playing)
    var controlsRevealed by rememberSaveable(layout) { mutableStateOf(false) }
    val minimal = layout == NowPlayingLayout.MINIMAL
    val expanded = layout == NowPlayingLayout.EXPANDED
    val canIdentify = LocalIdentify.current != null && !player?.currentMediaItem?.mediaId.isNullOrBlank()
    val available = NowPlayingElement.entries.filter { element ->
        when (element) {
            NowPlayingElement.IDENTIFY -> canIdentify
            NowPlayingElement.FAVORITE -> favorite != null
            NowPlayingElement.QUEUE -> onOpenQueue != null
            NowPlayingElement.MORE -> more != null
            NowPlayingElement.EQUALIZER -> controls.openEqualizer != null
            else -> true
        }
    }.toSet()
    val editor = rememberNowPlayingCustomization(available, !minimal || controlsRevealed, { controlsRevealed = true }, onPanelSaveFailed)
    val action: @Composable (NowPlayingElement, Modifier) -> Unit = { element, press ->
        when (element) {
            NowPlayingElement.IDENTIFY -> IdentifyButton(player, press)
            NowPlayingElement.FAVORITE -> favorite?.let { FavoriteButton(it, press.testTag(PlayerTags.FAVORITE)) }
            NowPlayingElement.QUEUE -> onOpenQueue?.let { open ->
                IconButton(onClick = open, modifier = press) {
                    UiControlIcon(painterResource(R.drawable.ic_queue), stringResource(R.string.queue_open))
                }
            }
            NowPlayingElement.MORE -> more?.let { TrackMenuButton(it, press) }
            NowPlayingElement.SHUFFLE -> ShuffleButton(player, press)
            NowPlayingElement.PREVIOUS -> PreviousButton(player, press)
            NowPlayingElement.PLAY_PAUSE -> PlayPauseButton(player, press, large = true)
            NowPlayingElement.NEXT -> NextButton(player, press)
            NowPlayingElement.REPEAT -> RepeatButton(player, press)
            NowPlayingElement.TIMER -> SleepTimerButton(controls, press)
            NowPlayingElement.STOP_AFTER -> StopAfterCurrentButton(controls, press)
            NowPlayingElement.EQUALIZER -> controls.openEqualizer?.let { EqualizerButton(it, press) }
            else -> error("Expected a playback action")
        }
    }
    val body: @Composable (NowPlayingElement, Modifier, TextAlign) -> Unit = { element, press, textAlign ->
        when (element) {
            NowPlayingElement.TRACK_INFO -> TrackHeading(metadata, onOpenArtist, onOpenAlbum, textAlign, press.fillMaxWidth())
            NowPlayingElement.FORMAT -> Column(press, horizontalAlignment = Alignment.CenterHorizontally) {
                PlayingBadge(player)
                Spacer(Modifier.height(4.dp))
                FormatLineText(player)
            }
            NowPlayingElement.SEEK -> SeekBar(player, press.fillMaxWidth())
            else -> error("Expected a track content component")
        }
    }
    val artwork: @Composable (Modifier) -> Unit = { press ->
        val size = if (expanded) Modifier.size(104.dp) else {
            Modifier.widthIn(max = if (minimal) 320.dp else 480.dp).fillMaxWidth().aspectRatio(1f)
        }
        CoverImage(cover, size.then(press).clip(RoundedCornerShape(12.dp)), if (expanded) 36.dp else 96.dp, coverName)
    }
    val collapse: @Composable () -> Unit = {
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_player_collapse), stringResource(R.string.player_close_now_playing))
        }
    }
    val top = editor.shown(NowPlayingRow.TOP)
    val content = editor.shown(NowPlayingRow.CONTENT)
    val transport = editor.shown(NowPlayingRow.CONTROLS)
    val extras = editor.shown(NowPlayingRow.EXTRAS)
    val compactPair = expanded && content.take(2) == listOf(NowPlayingElement.COVER, NowPlayingElement.TRACK_INFO)
    val contentRows = if (compactPair) listOf(content.take(2)) + content.drop(2).map { listOf(it) }
    else content.map { listOf(it) }
    val initialRows = (listOf(top) + contentRows + listOf(transport, extras)).filter { it.isNotEmpty() }
    val components = available.map { element ->
        val width = when (element) {
            NowPlayingElement.COVER -> if (expanded) 104.dp else 220.dp
            NowPlayingElement.PLAY_PAUSE -> 72.dp
            NowPlayingElement.PREVIOUS, NowPlayingElement.NEXT -> 56.dp
            NowPlayingElement.TIMER -> 96.dp
            else -> 48.dp
        }
        val height = when (element) {
            NowPlayingElement.TRACK_INFO -> 88.dp
            NowPlayingElement.SEEK, NowPlayingElement.FORMAT -> 64.dp
            else -> width
        }
        val trackContent = element.fits(NowPlayingRow.CONTENT)
        UiCanvasComponent(
            element.canvasId, element.label, width, height,
            fillWidth = element in fullWidthElements, customizableIcon = !trackContent,
            alignableText = element in fullWidthElements || element == NowPlayingElement.TIMER,
        ) {
            when {
                element == NowPlayingElement.COVER -> CoverImage(
                    cover, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    if (expanded) 36.dp else 72.dp, coverName,
                )
                trackContent -> body(element, Modifier, TextAlign.Center)
                else -> action(element, Modifier.fillMaxSize())
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(contentInsets)) {
        EditableUiPanel(
            surface = UiSurface.NOW_PLAYING,
            themeId = editor.themeId,
            components = components,
            initialRows = initialRows.map { row -> row.map { it.canvasId } },
            height = (maxHeight - 144.dp).coerceAtLeast(96.dp),
            onSaveFailed = onPanelSaveFailed,
            canvasChrome = { Row(Modifier.fillMaxWidth().semantics { paneTitle = pane }) { collapse() } },
        ) {
            StockNowPlaying(
                editor, pane, layout, controlsRevealed, { controlsRevealed = !controlsRevealed }, collapse,
                top, content, transport, extras, compactPair, artwork, body, action,
            )
        }
    }
}

internal val NowPlayingElement.canvasId: String get() = "now:$id"

private val fullWidthElements = setOf(NowPlayingElement.TRACK_INFO, NowPlayingElement.FORMAT, NowPlayingElement.SEEK)

@Composable
private fun StockNowPlaying(
    editor: NowPlayingCustomization,
    pane: String,
    layout: NowPlayingLayout,
    controlsRevealed: Boolean,
    onToggleControls: () -> Unit,
    collapse: @Composable () -> Unit,
    top: List<NowPlayingElement>,
    content: List<NowPlayingElement>,
    transport: List<NowPlayingElement>,
    extras: List<NowPlayingElement>,
    compactPair: Boolean,
    artwork: @Composable (Modifier) -> Unit,
    body: @Composable (NowPlayingElement, Modifier, TextAlign) -> Unit,
    action: @Composable (NowPlayingElement, Modifier) -> Unit,
) {
    val minimal = layout == NowPlayingLayout.MINIMAL
    val expanded = layout == NowPlayingLayout.EXPANDED
    val button: @Composable (NowPlayingElement) -> Unit = { element ->
        PlayerElement(editor, element) { press -> action(element, press) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .testTag(PlayerTags.NOW_PLAYING)
            .semantics { paneTitle = pane }
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) {
            collapse()
            if (minimal) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onToggleControls,
                    modifier = Modifier.testTag("now-playing-show-controls"),
                ) {
                    Text(stringResource(if (controlsRevealed) R.string.player_hide_controls else R.string.player_show_controls))
                }
            } else {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    top.forEach { button(it) }
                }
            }
            RestorePlayerElements(editor)
        }
        if (minimal && top.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                top.forEach { button(it) }
            }
        }
        if (compactPair) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerElement(editor, NowPlayingElement.COVER, content = artwork)
                PlayerElement(editor, NowPlayingElement.TRACK_INFO, Modifier.weight(1f)) { press ->
                    body(NowPlayingElement.TRACK_INFO, press, TextAlign.Start)
                }
            }
        }
        val remaining = if (compactPair) content.drop(2) else content
        remaining.forEachIndexed { index, element ->
            if (!expanded && remaining.getOrNull(index - 1) == NowPlayingElement.COVER) Spacer(Modifier.height(24.dp))
            if (element == NowPlayingElement.SEEK && (compactPair || index > 0)) {
                Spacer(Modifier.height(if (expanded) 8.dp else 16.dp))
            }
            PlayerElement(editor, element) { press ->
                if (element == NowPlayingElement.COVER) artwork(press) else body(element, press, TextAlign.Center)
            }
        }
        if (transport.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth().testTag("now_playing_transport"),
                horizontalArrangement = Arrangement.SpaceEvenly,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                transport.forEach { button(it) }
            }
        }
        if (extras.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                extras.forEach { button(it) }
            }
        }
    }
}

@Composable
private fun TrackMenuButton(entries: @Composable (close: () -> Unit) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = modifier.testTag(PlayerTags.MORE)) {
            UiControlIcon(painterResource(R.drawable.ic_more), stringResource(R.string.page_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { entries { open = false } }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TrackHeading(
    metadata: MediaMetadata,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    textAlign: TextAlign,
    modifier: Modifier,
) {
    val alignment = UiControlTextAlign(textAlign)
    Column(
        modifier,
        horizontalAlignment = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            text = metadata.title?.toString().orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            color = UiControlColor(Color.Unspecified),
            textAlign = alignment,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Text(
            text = metadata.artist?.toString() ?: stringResource(R.string.songs_unknown_artist),
            style = MaterialTheme.typography.titleMedium,
            color = UiControlColor(MaterialTheme.colorScheme.primary),
            textAlign = alignment,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillAlignedTextWidth().opens(onOpenArtist, R.string.open_artist),
        )
        metadata.albumTitle?.let { album ->
            Text(
                text = album.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = UiControlColor(MaterialTheme.colorScheme.onSurfaceVariant),
                textAlign = alignment,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillAlignedTextWidth().opens(onOpenAlbum, R.string.open_album),
            )
        }
    }
}

/** The bars and cutouts, the side areas of the back gesture, and at least a margin of 24 dp at the sides. */
private val contentInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing
        .union(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
        .union(WindowInsets(left = 24.dp, right = 24.dp))

/** The format of the audio track that plays, once the player knows it (§3.5). */
@Composable
private fun FormatLineText(player: Player?) {
    var format by remember(player) { mutableStateOf(player?.currentTracks?.let(::selectedAudioFormat)) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                format = selectedAudioFormat(tracks)
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    val resources = LocalResources.current
    Text(
        text = format?.let(FormatSummary::of)?.let { formatLine(it, resources) }.orEmpty(),
        style = MaterialTheme.typography.labelLarge,
        color = UiControlColor(MaterialTheme.colorScheme.onSurfaceVariant),
        textAlign = UiControlTextAlign(),
        modifier = Modifier.fillAlignedTextWidth(),
        minLines = 1,
    )
}

private fun selectedAudioFormat(tracks: Tracks): Format? =
    tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { group ->
        (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
    }

/**
 * The seek bar with the elapsed and the remaining time. It moves in steps of a pixel, but never more often than every
 * 16 ms, so that it waits on timers rather than on every frame; the times change once a second.
 */
@OptIn(UnstableApi::class)
@Composable
private fun SeekBar(player: Player?, modifier: Modifier = Modifier) {
    var widthPx by remember { mutableIntStateOf(0) }
    val time = rememberProgressInterval(player, tickIntervalMs = 1_000)
    val progress = rememberProgressTicks(player, progressTicks(widthPx, time.durationMs))
    var dragged by remember(player) { mutableStateOf<Float?>(null) }
    val positionMs = dragged?.let(progress::progressToPosition) ?: time.currentPositionMs
    val locale = LocalConfiguration.current.locales[0]
    val label = stringResource(R.string.player_position)
    val spoken = if (time.durationMs > 0) {
        stringResource(R.string.player_position_state, PlayerTime.spoken(positionMs, locale), PlayerTime.spoken(time.durationMs, locale))
    } else {
        PlayerTime.spoken(positionMs, locale)
    }
    val sliderColors = SliderDefaults.colors()
    Column(modifier) {
        Slider(
            value = dragged ?: progress.currentPositionProgress,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let(progress::updateCurrentPositionProgress)
                dragged = null
            },
            enabled = progress.changingProgressEnabled,
            colors = sliderColors.copy(
                thumbColor = UiControlColor(sliderColors.thumbColor),
                activeTrackColor = UiControlColor(sliderColors.activeTrackColor),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { widthPx = it.width }
                .testTag(PlayerTags.SEEK_BAR)
                .semantics {
                    contentDescription = label
                    stateDescription = spoken
                },
        )
        Row(Modifier.fillMaxWidth()) {
            val color = UiControlColor(Color.Unspecified)
            val alignment = UiControlTextAlign()
            val customized = alignment != TextAlign.Unspecified
            val timeWidth = if (customized) Modifier.weight(1f) else Modifier
            Text(PlayerTime.elapsed(positionMs), modifier = timeWidth, style = MaterialTheme.typography.labelMedium,
                color = color, textAlign = alignment)
            if (!customized) Spacer(Modifier.weight(1f))
            Text(PlayerTime.remaining(positionMs, time.durationMs), modifier = timeWidth,
                style = MaterialTheme.typography.labelMedium, color = color, textAlign = alignment)
        }
    }
}

/** One step per pixel of [widthPx], at most one per 16 ms of a song of [durationMs]; none before both are known. */
internal fun progressTicks(widthPx: Int, durationMs: Long): Int =
    if (widthPx <= 0 || durationMs <= 0) 0 else minOf(widthPx.toLong(), durationMs / MIN_TICK_MS).toInt().coerceAtLeast(1)

private const val MIN_TICK_MS = 16L

/** A text that opens a page when [open] is given (docs/library.md §6.1). */
@Composable
private fun Modifier.opens(open: (() -> Unit)?, label: Int): Modifier =
    if (open == null) {
        this
    } else {
        minimumInteractiveComponentSize().clickable(onClickLabel = stringResource(label), role = Role.Button, onClick = open)
    }
