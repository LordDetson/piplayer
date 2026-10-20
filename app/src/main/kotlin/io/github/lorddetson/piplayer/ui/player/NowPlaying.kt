package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.github.lorddetson.piplayer.R

/**
 * Now Playing (docs/playback.md §3.3): the current song of [player] with its [cover] and every control. It keeps its
 * content inside the safe drawing area and its sides out of the system's back gesture areas.
 */
@OptIn(UnstableApi::class)
@Composable
fun NowPlaying(player: Player?, cover: ImageBitmap?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val metadata = rememberCurrentItemState(player).mediaMetadata
    val pane = stringResource(R.string.player_now_playing)
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(PlayerTags.NOW_PLAYING)
            .semantics { paneTitle = pane }
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(contentInsets)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.Start)) {
            Icon(painterResource(R.drawable.ic_player_collapse), stringResource(R.string.player_close_now_playing))
        }
        CoverImage(
            cover,
            Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            noteSize = 96.dp,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = metadata.title?.toString().orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Text(
            text = metadata.artist?.toString() ?: stringResource(R.string.songs_unknown_artist),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        metadata.albumTitle?.let { album ->
            Text(
                text = album.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        FormatLineText(player)
        Spacer(Modifier.height(16.dp))
        SeekBar(player, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShuffleButton(player)
            PreviousButton(player)
            PlayPauseButton(player, large = true)
            NextButton(player)
            RepeatButton(player)
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
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Column(modifier) {
        Slider(
            value = dragged ?: progress.currentPositionProgress,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let(progress::updateCurrentPositionProgress)
                dragged = null
            },
            enabled = progress.changingProgressEnabled,
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
            Text(PlayerTime.elapsed(positionMs), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(PlayerTime.remaining(positionMs, time.durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** One step per pixel of [widthPx], at most one per 16 ms of a song of [durationMs]; none before both are known. */
internal fun progressTicks(widthPx: Int, durationMs: Long): Int =
    if (widthPx <= 0 || durationMs <= 0) 0 else minOf(widthPx.toLong(), durationMs / MIN_TICK_MS).toInt().coerceAtLeast(1)

private const val MIN_TICK_MS = 16L
