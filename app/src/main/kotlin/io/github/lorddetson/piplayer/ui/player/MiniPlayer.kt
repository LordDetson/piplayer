package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.lorddetson.piplayer.R

/**
 * The mini player at the bottom of the list (docs/playback.md §3.2): nothing while the queue of [player] is empty. Its
 * background reaches the bottom edge of the window; its content sits above the navigation bar.
 */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(player: Player?, cover: ImageBitmap?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val item = rememberCurrentItemState(player)
    if (player == null || item.mediaItem == null) return
    val metadata = item.mediaMetadata
    Surface(
        modifier = modifier.fillMaxWidth().testTag(PlayerTags.MINI_PLAYER),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            ProgressLine(player, item.durationMs)
            Row(
                modifier = Modifier
                    .windowInsetsPadding(barInsets)
                    .clickable(onClickLabel = stringResource(R.string.player_open_now_playing), role = Role.Button, onClick = onOpen)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(cover, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = metadata.title?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metadata.artist?.toString() ?: stringResource(R.string.songs_unknown_artist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PlayPauseButton(player)
            }
        }
    }
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
