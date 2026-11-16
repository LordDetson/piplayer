package io.github.lorddetson.piplayer.ui.identification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.Player

/** What "Identify" opens the review for (TI §1): a song of the library by its file ID, or an opened file by its URI. */
sealed interface IdentifyTarget {
    data class Song(val fileId: Long) : IdentifyTarget

    data class Opened(val uri: String) : IdentifyTarget
}

/**
 * Opens the review (TI §6), provided by the app around its screens; null where nothing can open it, which hides every
 * "Identify" entry.
 */
val LocalIdentify = staticCompositionLocalOf<((IdentifyTarget) -> Unit)?> { null }

/** Now Playing's "Identify" button (TI §1) for the current item of [player]: a library song's or an opened file's. */
@Composable
fun IdentifyButton(player: Player?) = Unit

/** The "Identify" action of track details (TI §1) for the song [fileId] or the opened file [uri]. */
@Composable
fun IdentifyAction(fileId: Long?, uri: String?) = Unit
