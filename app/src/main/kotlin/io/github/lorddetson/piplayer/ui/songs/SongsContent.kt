package io.github.lorddetson.piplayer.ui.songs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.tagpass.PassProgress
import io.github.lorddetson.piplayer.ui.formatDuration

/** What the song list shows (docs/playback.md §2.1, §2.2). */
sealed interface SongsState {
    data object Loading : SongsState

    /** No permission yet; [deniedForGood] once the system no longer shows its dialog. */
    data class NeedsPermission(val deniedForGood: Boolean) : SongsState

    /** [scrollTo] is the song to show at the top when the list has just been sorted anew. */
    data class Loaded(val songs: List<Song>, val scrollTo: Long? = null) : SongsState
}

object SongsTags {
    const val LIST = "songs_list"
    const val LOADING = "songs_loading"
    const val PROGRESS = "songs_progress"
}

/** The song list without its state holder: the permission card, the list or its empty state. */
@Composable
fun SongsContent(
    state: SongsState,
    onAllowAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onSongClick: (row: Int, song: Song) -> Unit,
    modifier: Modifier = Modifier,
    passProgress: PassProgress = PassProgress(),
    onFirstVisibleSong: (Long?) -> Unit = {},
    onScrolled: () -> Unit = {},
) {
    when (state) {
        SongsState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag(SongsTags.LOADING))
        }
        is SongsState.NeedsPermission -> PermissionCard(state.deniedForGood, onAllowAccess, onOpenSettings, modifier)
        is SongsState.Loaded -> if (state.songs.isEmpty()) {
            Text(
                text = stringResource(R.string.songs_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = modifier.padding(vertical = 16.dp),
            )
        } else {
            val list = rememberLazyListState()
            val songs by rememberUpdatedState(state.songs)
            LaunchedEffect(list) {
                snapshotFlow { songs.getOrNull(list.firstVisibleItemIndex)?.id }.collect(onFirstVisibleSong)
            }
            LaunchedEffect(state.scrollTo) {
                val song = state.scrollTo ?: return@LaunchedEffect
                val row = state.songs.indexOfFirst { it.id == song }
                if (row >= 0) list.scrollToItem(row)
                onScrolled()
            }
            // Over the list, so that its coming and going moves no row.
            Box(modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag(SongsTags.LIST), state = list) {
                    itemsIndexed(state.songs, key = { _, song -> song.id }) { row, song ->
                        SongRow(song, onClick = { onSongClick(row, song) })
                    }
                }
                if (passProgress.running && passProgress.total >= MIN_FILES_FOR_PROGRESS) {
                    val description = stringResource(R.string.songs_tag_progress, passProgress.done, passProgress.total)
                    LinearProgressIndicator(
                        progress = { passProgress.done.toFloat() / passProgress.total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .testTag(SongsTags.PROGRESS)
                            .semantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

/** A run over fewer files is too short to be worth a line (docs/library.md §5.4). */
private const val MIN_FILES_FOR_PROGRESS = 50

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
