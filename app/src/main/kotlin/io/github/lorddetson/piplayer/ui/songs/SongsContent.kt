package io.github.lorddetson.piplayer.ui.songs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.songs.Song

/** What the song list shows (docs/playback.md §2.1, §2.2). */
sealed interface SongsState {
    data object Loading : SongsState

    /** No permission yet; [deniedForGood] once the system no longer shows its dialog. */
    data class NeedsPermission(val deniedForGood: Boolean) : SongsState

    data class Loaded(val songs: List<Song>) : SongsState
}

object SongsTags {
    const val LIST = "songs_list"
}

/** The song list without its state holder: the permission card, the list or its empty state. */
@Composable
fun SongsContent(
    state: SongsState,
    onAllowAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onSongClick: (row: Int, song: Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        SongsState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is SongsState.NeedsPermission -> PermissionCard(state.deniedForGood, onAllowAccess, onOpenSettings, modifier)
        is SongsState.Loaded -> if (state.songs.isEmpty()) {
            Text(
                text = stringResource(R.string.songs_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = modifier.padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(modifier.fillMaxSize().testTag(SongsTags.LIST)) {
                itemsIndexed(state.songs, key = { _, song -> song.id }) { row, song ->
                    SongRow(song, onClick = { onSongClick(row, song) })
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    deniedForGood: Boolean,
    onAllowAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(
                if (deniedForGood) R.string.songs_denied_explanation else R.string.songs_permission_explanation,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (deniedForGood) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.songs_open_settings)) }
                OutlinedButton(onClick = onAllowAccess) { Text(stringResource(R.string.songs_try_again)) }
            }
        } else {
            Button(onClick = onAllowAccess) { Text(stringResource(R.string.songs_allow_access)) }
        }
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
    val artist = song.artist ?: stringResource(R.string.songs_unknown_artist)
    ListItem(
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("$artist · ${formatDuration(song.durationMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier.clickable(
            onClickLabel = stringResource(R.string.songs_play),
            role = Role.Button,
            onClick = onClick,
        ),
    )
}

/** m:ss, or h:mm:ss from an hour on; "--:--" when the length is unknown. */
internal fun formatDuration(durationMs: Long?): String {
    if (durationMs == null) return "--:--"
    val seconds = durationMs / 1000
    val (hours, minutes, rest) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}
