package io.github.lorddetson.piplayer.ui.details

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.details.Badge
import io.github.lorddetson.piplayer.library.details.BadgeState
import io.github.lorddetson.piplayer.library.details.TrackDetailsSources
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.QueueIds
import io.github.lorddetson.piplayer.ui.identification.IdentifyTarget
import io.github.lorddetson.piplayer.ui.identification.LocalIdentify
import java.text.NumberFormat
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest

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

/**
 * The metadata badge (TI §6, docs/library.md §18.7): its state as text, read by TalkBack as "Metadata" with the state; a
 * tap does [onClick], named "Identify" from tags and "Show the identification" otherwise.
 */
@Composable
fun MetadataBadge(badge: Badge, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = badgeLabel(badge)
    val description = stringResource(R.string.badge_description, label)
    val clickLabel = stringResource(if (badge.state == BadgeState.FROM_TAGS) R.string.identify else R.string.badge_show_identification)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (badge.state == BadgeState.FROM_TAGS) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .testTag(BadgeTags.BADGE)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** The badge's state in words (§18.7): the provider and confidence of an identification when known. */
@Composable
internal fun badgeLabel(badge: Badge): String = when (badge.state) {
    BadgeState.FROM_TAGS -> stringResource(R.string.badge_from_tags)
    BadgeState.EDITED -> stringResource(R.string.badge_edited)
    BadgeState.IDENTIFIED -> {
        val provider = badge.provider
        val confidence = badge.confidence
        if (provider != null && confidence != null) {
            val locale = LocalConfiguration.current.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.ROOT
            stringResource(R.string.badge_identified, providerName(provider), NumberFormat.getPercentInstance(locale).format(confidence))
        } else {
            stringResource(R.string.badge_identified_plain)
        }
    }
}

/** A provider's name, the same in every language. */
@Composable
internal fun providerName(provider: ProviderId): String = stringResource(
    when (provider) {
        ProviderId.MUSICBRAINZ -> R.string.identification_name_musicbrainz
        ProviderId.ACOUSTID -> R.string.identification_name_acoustid
        ProviderId.COVER_ART_ARCHIVE -> R.string.details_cover_caa
    },
)

/**
 * Now Playing's badge for the current item of [player], a song of the library (docs/library.md §18.7): read through
 * [LocalBadgeHost]; a tap opens the review from tags, otherwise the song's details. An opened file has none.
 */
@Composable
fun PlayingBadge(player: Player?) {
    val host = LocalBadgeHost.current ?: return
    val identify = LocalIdentify.current
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
    val fileId = mediaId?.let(QueueIds::fileId) ?: return
    val badge by remember(host, fileId) { host.badges(fileId) }.collectAsState(initial = null)
    val shown = badge ?: return
    MetadataBadge(
        shown,
        onClick = { if (shown.state == BadgeState.FROM_TAGS) identify?.invoke(IdentifyTarget.Song(fileId)) else host.showDetails(fileId) },
        Modifier.padding(top = 8.dp),
    )
}

/** The badges of songs of the library as the app reads them (docs/library.md §18.7). */
object MetadataBadges {

    /**
     * The badge of the file [fileId], read off the main thread at first and again after a sync that changed the library,
     * a batch of the tag pass (both write display rows) and every run of the decision mirror that changed decided
     * values; a read that fails keeps what was shown.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun of(context: Context, fileId: Long): Flow<Badge?> {
        val app = context.applicationContext
        if (!Library.mayRead(app)) return flowOf(null)
        // Each emits its present value at first, so the first read needs no start of its own.
        return combine(SongList.changes(app), SongList.batches, Library.decisionVersion(app)) { _, _, _ -> }
            .transformLatest {
                try {
                    emit(TrackDetailsSources.badges(app).of(fileId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "The metadata badge could not be read", e)
                }
            }
            .flowOn(Dispatchers.IO)
    }

    private const val TAG = "MetadataBadges"
}
