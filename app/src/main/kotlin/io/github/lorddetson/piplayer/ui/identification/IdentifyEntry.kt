package io.github.lorddetson.piplayer.ui.identification

import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.playback.QueueIds
import io.github.lorddetson.piplayer.ui.customization.UiControlIcon

/** What "Identify" opens the review for (TI §1): a song of the library by its file ID, or an opened file by its URI. */
sealed interface IdentifyTarget {
    data class Song(val fileId: Long) : IdentifyTarget

    data class Opened(val uri: String) : IdentifyTarget

    companion object {
        /** The target of a queue item's media ID (docs/playback.md §9.4): a library song's, or an opened file's URI. */
        fun ofMediaId(mediaId: String): IdentifyTarget = QueueIds.fileId(mediaId)?.let(::Song) ?: Opened(mediaId)
    }
}

/**
 * Opens the review (TI §6), provided by the app around its screens; null where nothing can open it, which hides every
 * "Identify" entry.
 */
val LocalIdentify = staticCompositionLocalOf<((IdentifyTarget) -> Unit)?> { null }

/** Now Playing's "Identify" button (TI §1) for the current item of [player]: a library song's or an opened file's. */
@Composable
fun IdentifyButton(player: Player?, modifier: Modifier = Modifier) {
    val identify = LocalIdentify.current ?: return
    var mediaId by remember(player) { mutableStateOf(player?.currentMediaItem?.mediaId) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                mediaId = player.currentMediaItem?.mediaId
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    val id = mediaId?.takeIf { it.isNotEmpty() } ?: return
    IdentifyIcon(modifier) { identify(IdentifyTarget.ofMediaId(id)) }
}

/** The "Identify" action of track details (TI §1) for the song [fileId] or the opened file [uri]. */
@Composable
fun IdentifyAction(fileId: Long?, uri: String?) {
    val identify = LocalIdentify.current ?: return
    val target = fileId?.let { IdentifyTarget.Song(it) } ?: uri?.let { IdentifyTarget.Opened(it) } ?: return
    IdentifyIcon { identify(target) }
}

@Composable
private fun IdentifyIcon(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) { UiControlIcon(painterResource(R.drawable.ic_identify), stringResource(R.string.identify)) }
}
