package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.github.lorddetson.piplayer.ui.customization.StockUiSlot
import io.github.lorddetson.piplayer.ui.customization.UiCanvasComponent
import io.github.lorddetson.piplayer.ui.customization.UiControlColor
import io.github.lorddetson.piplayer.ui.customization.UiControlTextAlign
import io.github.lorddetson.piplayer.ui.customization.fillAlignedTextWidth
import io.github.lorddetson.piplayer.ui.customization.UiControlIcon
import io.github.lorddetson.piplayer.ui.customization.isStockUiSlotMasked
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
    moodBackground: Boolean = true,
) {
    val item = rememberCurrentItemState(player)
    val metadata = item.mediaMetadata
    val moodBackdrop = rememberMoodBackdrop(item.mediaItem, moodBackground)
    val swipe = rememberNowPlayingTrackSwipe(player)
    val swipeMotion = rememberTrackSwipeMotion(swipe)
    val protectedTouch = Modifier.guardTrackSwipe(swipe)
    val format = rememberAudioFormat(player)
    val unknownArtist = stringResource(R.string.songs_unknown_artist)
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
    val action: @Composable (NowPlayingElement, Modifier) -> Unit = { element, input ->
        val press = input.then(protectedTouch)
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
    val body: @Composable (NowPlayingElement, Modifier, TextAlign, Boolean) -> Unit = { element, press, textAlign, split ->
        when (element) {
            NowPlayingElement.TRACK_INFO -> TrackHeading(metadata, unknownArtist, onOpenArtist, onOpenAlbum,
                textAlign, press.fillMaxWidth(), split, swipeMotion)
            NowPlayingElement.FORMAT -> Column(press, horizontalAlignment = Alignment.CenterHorizontally) {
                if (split) StockUiSlot(PLAYING_BADGE_ID) { PlayingBadge(player) } else PlayingBadge(player)
                Spacer(Modifier.height(4.dp))
                AudioFormatReadout(format, split)
            }
            NowPlayingElement.SEEK -> SeekBar(player, press.fillMaxWidth().then(protectedTouch))
            else -> error("Expected a track content component")
        }
    }
    val artwork: @Composable (Modifier) -> Unit = { press ->
        val size = if (expanded) Modifier.size(104.dp) else {
            Modifier.widthIn(max = if (minimal) 320.dp else 480.dp).fillMaxWidth().aspectRatio(1f)
        }
        CoverImage(cover, size.then(press).then(swipeMotion).clip(RoundedCornerShape(12.dp)), if (expanded) 36.dp else 96.dp, coverName)
    }
    val collapse: @Composable () -> Unit = {
        IconButton(onClick = onClose, modifier = protectedTouch) {
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
    val standardComponents = available.map { element ->
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
            addable = element != NowPlayingElement.TRACK_INFO && element != NowPlayingElement.FORMAT,
        ) {
            when {
                element == NowPlayingElement.COVER -> CoverImage(
                    cover, Modifier.fillMaxSize().then(swipeMotion).clip(RoundedCornerShape(12.dp)),
                    if (expanded) 36.dp else 72.dp, coverName,
                )
                trackContent -> body(element, Modifier, TextAlign.Center, false)
                else -> action(element, Modifier.fillMaxSize())
            }
        }
    }
    val trackComponents = TrackField.entries.map { field ->
        UiCanvasComponent(field.nowPlayingId, field.label,
            height = if (field == TrackField.TITLE) 36.dp else 24.dp, fillWidth = true, alignableText = true) {
            field.value(metadata, unknownArtist)?.let { value ->
                TrackFieldText(field, value, onOpenArtist, onOpenAlbum, TextAlign.Center, swipeMotion)
            }
        }
    }
    val audioComponents = AudioFormatField.entries.map { field ->
        UiCanvasComponent(field.canvasId, field.label, width = 96.dp, height = 24.dp, alignableText = true) {
            val value = format?.let { field.value(it, LocalResources.current, includeStereo = true) }
            if (value != null) FormatText(value)
        }
    }
    val badgeComponent = UiCanvasComponent(PLAYING_BADGE_ID, R.string.player_metadata_badge, width = 72.dp, height = 48.dp) {
        PlayingBadge(player)
    }
    BoxWithConstraints(modifier.fillMaxSize().then(moodBackdrop).windowInsetsPadding(contentInsets)) {
        EditableUiPanel(
            surface = UiSurface.NOW_PLAYING,
            themeId = editor.themeId,
            components = standardComponents + trackComponents + audioComponents + badgeComponent,
            initialRows = initialRows.map { row -> row.flatMap { it.stockCanvasIds() } },
            height = (maxHeight - 144.dp).coerceAtLeast(96.dp),
            onSaveFailed = onPanelSaveFailed,
            legacyStockIds = setOf(NowPlayingElement.TRACK_INFO.canvasId, NowPlayingElement.FORMAT.canvasId),
            viewModeModifier = if (player == null) Modifier else Modifier.nowPlayingTrackSwipe(swipe),
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
private const val PLAYING_BADGE_ID = "now:audio_badge"

private fun NowPlayingElement.stockCanvasIds(): List<String> = when (this) {
    NowPlayingElement.TRACK_INFO -> TrackField.entries.map { it.nowPlayingId }
    NowPlayingElement.FORMAT -> listOf(PLAYING_BADGE_ID) + AudioFormatField.entries.map(AudioFormatField::canvasId)
    else -> listOf(canvasId)
}

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
    body: @Composable (NowPlayingElement, Modifier, TextAlign, Boolean) -> Unit,
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
                    body(NowPlayingElement.TRACK_INFO, press, TextAlign.Start, true)
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
                if (element == NowPlayingElement.COVER) artwork(press) else body(element, press, TextAlign.Center, true)
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
    unknownArtist: String,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    textAlign: TextAlign,
    modifier: Modifier,
    split: Boolean,
    titleMotion: Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        for (field in TrackField.entries) {
            val value = field.value(metadata, unknownArtist) ?: continue
            if (split) StockUiSlot(field.nowPlayingId) {
                TrackFieldText(field, value, onOpenArtist, onOpenAlbum, textAlign, titleMotion)
            } else TrackFieldText(field, value, onOpenArtist, onOpenAlbum, textAlign, titleMotion)
        }
    }
}

@Composable
private fun TrackFieldText(
    field: TrackField,
    value: String,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    alignment: TextAlign,
    titleMotion: Modifier,
) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val title = field == TrackField.TITLE
    val style = when (field) {
        TrackField.TITLE -> typography.headlineSmall
        TrackField.ARTIST -> typography.titleMedium
        TrackField.ALBUM -> typography.bodyLarge
    }
    val color = when (field) {
        TrackField.TITLE -> Color.Unspecified
        TrackField.ARTIST -> colors.primary
        TrackField.ALBUM -> colors.onSurfaceVariant
    }
    val modifier = when (field) {
        TrackField.TITLE -> Modifier.fillMaxWidth().then(titleMotion).semantics { heading() }
        TrackField.ARTIST -> Modifier.fillAlignedTextWidth().opens(onOpenArtist, R.string.open_artist)
        TrackField.ALBUM -> Modifier.fillAlignedTextWidth().opens(onOpenAlbum, R.string.open_album)
    }
    Text(value, modifier = modifier, style = style, color = UiControlColor(color),
        textAlign = UiControlTextAlign(alignment), maxLines = if (title) 2 else 1, overflow = TextOverflow.Ellipsis)
}

/** The bars and cutouts, the side areas of the back gesture, and at least a margin of 24 dp at the sides. */
private val contentInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing
        .union(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
        .union(WindowInsets(left = 24.dp, right = 24.dp))

@Composable
private fun rememberAudioFormat(player: Player?): FormatSummary? {
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
    return format?.let(FormatSummary::of)
}

/** Keep the compact line in the stock panel while recording each visible field's bounds. */
@Composable
private fun AudioFormatReadout(format: FormatSummary?, split: Boolean) {
    val resources = LocalResources.current
    if (format == null || !split) {
        FormatText(format?.let { formatLine(it, resources) }.orEmpty())
        return
    }
    val fields = AudioFormatField.entries.mapNotNull { field ->
        field.value(format, resources)?.let { field to it }
    }
    FlowRow(horizontalArrangement = Arrangement.Center) {
        var precedingFieldVisible = false
        fields.forEachIndexed { index, (field, value) ->
            val separatorVisible = precedingFieldVisible
            if (!isStockUiSlotMasked(field.canvasId)) precedingFieldVisible = true
            StockUiSlot(field.canvasId) {
                Row {
                    if (index > 0) FormatText(
                        AUDIO_FORMAT_SEPARATOR,
                        Modifier.alpha(if (separatorVisible) 1f else 0f).clearAndSetSemantics {},
                    )
                    FormatText(value)
                }
            }
        }
    }
}

@Composable
private fun FormatText(value: String, modifier: Modifier = Modifier) {
    Text(
        text = value,
        style = MaterialTheme.typography.labelLarge,
        color = UiControlColor(MaterialTheme.colorScheme.onSurfaceVariant),
        textAlign = UiControlTextAlign(),
        modifier = modifier.fillAlignedTextWidth(),
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
