package io.github.lorddetson.piplayer.ui.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import io.github.lorddetson.piplayer.library.details.Badge
import kotlinx.coroutines.flow.Flow

/** Test tags of the metadata badge (TI §6, docs/library.md §18.7). */
object BadgeTags {
    const val BADGE = "metadata_badge"
}

/**
 * What Now Playing's badge needs from the app (docs/library.md §18.7): the badge of a library song by its file ID, read
 * again as decisions change, and the way to its details.
 */
class BadgeHost(val badges: (fileId: Long) -> Flow<Badge?>, val showDetails: (fileId: Long) -> Unit)

/** Provided by the app around its screens; null hides Now Playing's badge. */
val LocalBadgeHost = staticCompositionLocalOf<BadgeHost?> { null }

/** The metadata badge (TI §6): its state as text, a tap does [onClick]. */
@Composable
fun MetadataBadge(badge: Badge, onClick: () -> Unit, modifier: Modifier = Modifier) {
}

/** Now Playing's badge for the current item of [player], a song of the library (docs/library.md §18.7). */
@Composable
fun PlayingBadge(player: Player?) {
}
