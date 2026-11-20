package io.github.lorddetson.piplayer.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import io.github.lorddetson.piplayer.R as AppR
import io.github.lorddetson.piplayer.playback.PlaybackConnection

@Composable
internal fun RadioCurrentStream() {
    val context = LocalContext.current.applicationContext
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        var attached = true
        val connection = PlaybackConnection(context, object : MediaController.Listener {
            override fun onDisconnected(disconnected: MediaController) {
                if (attached && controller === disconnected) controller = null
            }
        })
        connection.whenConnected { if (attached) controller = this }
        onDispose {
            attached = false
            connection.release()
        }
    }

    val player = controller ?: return
    var stream by remember(player) { mutableStateOf(player.radioStream()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                stream = player.radioStream()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val current = stream ?: return
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    current.title.station,
                    modifier = Modifier.testTag("radio-current-station"),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    current.failed -> Text(stringResource(R.string.radio_playback_error), color = MaterialTheme.colorScheme.error)
                    current.buffering -> Text(stringResource(R.string.radio_connecting))
                    current.title.song != null -> Text(
                        current.title.song,
                        modifier = Modifier.testTag("radio-current-song"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val showPause = current.playWhenReady && !current.failed
            IconButton(
                modifier = Modifier.testTag("radio-play-pause"),
                enabled = player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
                onClick = {
                    if (showPause) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        player.play()
                    }
                },
            ) {
                Icon(
                    painterResource(if (showPause) AppR.drawable.ic_player_pause else AppR.drawable.ic_player_play),
                    stringResource(if (showPause) R.string.radio_pause else R.string.radio_play),
                )
            }
        }
    }
}

private data class CurrentRadioStream(
    val title: RadioStreamTitle,
    val playWhenReady: Boolean,
    val buffering: Boolean,
    val failed: Boolean,
)

private fun Player.radioStream(): CurrentRadioStream? {
    val item = currentMediaItem ?: return null
    val title = radioStreamTitle(item.mediaId, item.mediaMetadata.station ?: item.mediaMetadata.title, mediaMetadata.title, mediaMetadata.artist)
        ?: return null
    return CurrentRadioStream(title, playWhenReady, playbackState == Player.STATE_BUFFERING, playerError != null)
}
