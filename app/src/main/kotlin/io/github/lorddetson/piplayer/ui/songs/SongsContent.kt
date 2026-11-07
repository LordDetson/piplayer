package io.github.lorddetson.piplayer.ui.songs

import io.github.lorddetson.piplayer.ui.library.ScrollThumb
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.lists.SongLinks
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.tagpass.PassProgress
import io.github.lorddetson.piplayer.ui.formatDuration

/** What a song list shows (docs/playback.md §2.1, §2.2, docs/library.md §6). */
sealed interface SongsState {
    data object Loading : SongsState

    /** No permission yet; [deniedForGood] once the system no longer shows its dialog. */
    data class NeedsPermission(val deniedForGood: Boolean) : SongsState

    /**
     * [songs] are [query]'s, which a tap plays. [scrollTo] is the song to show at the top when the list has just been
     * sorted anew.
     */
    data class Loaded(val songs: List<Song>, val query: SongQuery = SongQuery.SONGS, val scrollTo: Long? = null) : SongsState
}

object SongsTags {
    const val LIST = "songs_list"
    const val LOADING = "songs_loading"
    const val PROGRESS = "songs_progress"
}

/**
 * The places a song's menu goes to (docs/library.md §6.1): [links] finds them for a song, off the main thread, the
 * others open the pages.
 */
class SongMenu(
    val links: suspend (Song) -> SongLinks?,
    val openAlbum: (key: String) -> Unit,
    val openArtist: (key: String) -> Unit,
    val openFolder: (volume: String, relativePath: String) -> Unit,
    /** "Play next" and "Add to queue" (docs/playback.md §9.1). */
    val playNext: (Song) -> Unit = {},
    val addToQueue: (Song) -> Unit = {},
)

/**
 * A song list without its state holder: the permission card, the list or its empty state. [header] comes before the
 * songs, as a page's albums or subfolders; [discHeaders] starts each disc with a header when the songs have more than one.
 * A long list has the scroll thumb (docs/library.md §11.2), whose bubble shows the songs' sections and [headerSections]
 * for the items of [header].
 */
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
    currentSongId: Long? = null,
    menu: SongMenu? = null,
    listTag: String = SongsTags.LIST,
    emptyText: String? = null,
    discHeaders: Boolean = false,
    headerItems: Int = 0,
    headerSections: List<String?> = emptyList(),
    header: LazyListScope.() -> Unit = {},
) {
    when (state) {
        SongsState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag(SongsTags.LOADING))
        }
        is SongsState.NeedsPermission -> PermissionCard(state.deniedForGood, onAllowAccess, onOpenSettings, modifier)
        is SongsState.Loaded -> if (state.songs.isEmpty() && headerItems == 0) {
            Text(
                text = emptyText ?: stringResource(R.string.songs_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = modifier.padding(16.dp),
            )
        } else {
            val list = rememberLazyListState()
            // Disc headers only on album pages, which are small; other lists stay one run of rows.
            val discRows = remember(state.songs, discHeaders) { if (discHeaders) discRows(state.songs) else null }
            val withDiscs = discRows != null
            LaunchedEffect(list) {
                snapshotFlow { list.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { it.key as? Long } }.collect(onFirstVisibleSong)
            }
            // Another order or search starts at the top; a sort anew after the tag pass keeps its song (scrollTo).
            var shownQuery by remember { mutableStateOf(state.query) }
            LaunchedEffect(state.query) {
                if (state.query != shownQuery) {
                    shownQuery = state.query
                    list.scrollToItem(0)
                }
            }
            LaunchedEffect(state.scrollTo) {
                val song = state.scrollTo ?: return@LaunchedEffect
                val row = state.songs.indexOfFirst { it.id == song }
                if (row >= 0) list.scrollToItem(row + headerItems + if (withDiscs) discsBefore(state.songs, row) else 0)
                onScrolled()
            }
            // Over the list, so that its coming and going moves no row.
            Box(modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag(listTag), state = list) {
                    header()
                    if (state.songs.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = emptyText ?: stringResource(R.string.songs_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    if (discRows == null) {
                        itemsIndexed(state.songs, key = { _, song -> song.id }) { row, song ->
                            SongRow(song, playing = song.id == currentSongId, menu = menu, onClick = { onSongClick(row, song) })
                        }
                    } else {
                        items(discRows, key = { it.key }) { entry ->
                            when (entry) {
                                is DiscRow.Header -> DiscHeader(entry.disc)
                                is DiscRow.Song -> SongRow(
                                    entry.song,
                                    playing = entry.song.id == currentSongId,
                                    menu = menu,
                                    onClick = { onSongClick(entry.row, entry.song) },
                                )
                            }
                        }
                    }
                }
                ScrollThumb(
                    list,
                    label = { item ->
                        if (item < headerItems) {
                            headerSections.getOrNull(item)
                        } else {
                            val row = item - headerItems
                            if (discRows == null) state.songs.getOrNull(row)?.section else (discRows.getOrNull(row) as? DiscRow.Song)?.song?.section
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
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

/** A row of an album page: a disc's header or a song at its [row] in the list. */
private sealed interface DiscRow {
    val key: Any

    data class Header(val disc: Int, override val key: Any) : DiscRow

    data class Song(val song: io.github.lorddetson.piplayer.library.songs.Song, val row: Int) : DiscRow {
        override val key: Any get() = song.id
    }
}

/** The songs with a header before each disc, when they have more than one disc; otherwise null. */
private fun discRows(songs: List<Song>): List<DiscRow>? {
    if (songs.mapNotNullTo(HashSet()) { it.discNumber }.size < 2) return null
    val rows = ArrayList<DiscRow>(songs.size + 4)
    var disc: Int? = null
    songs.forEachIndexed { row, song ->
        val number = song.discNumber
        if (number != null && number != disc) {
            disc = number
            rows += DiscRow.Header(number, "disc $number $row")
        }
        rows += DiscRow.Song(song, row)
    }
    return rows
}

/** The disc headers before the song at [row]. */
private fun discsBefore(songs: List<Song>, row: Int): Int {
    var count = 0
    var disc: Int? = null
    for (i in 0..row) {
        val number = songs[i].discNumber ?: continue
        if (number != disc) {
            count++
            disc = number
        }
    }
    return count
}

/** A run over fewer files is too short to be worth a line (docs/library.md §5.4). */
private const val MIN_FILES_FOR_PROGRESS = 50

@Composable
private fun DiscHeader(number: Int) {
    Text(
        text = stringResource(R.string.album_disc, number),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** The card that asks for access to music (docs/playback.md §2.2). */
@Composable
internal fun PermissionCard(
    deniedForGood: Boolean,
    onAllowAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** A song: its title, artist and length; the playing one is marked, and its menu goes to its album, artists and folder. */
@Composable
private fun SongRow(song: Song, playing: Boolean, menu: SongMenu?, onClick: () -> Unit) {
    val artist = song.artist ?: stringResource(R.string.songs_unknown_artist)
    val playingText = stringResource(R.string.song_playing)
    ListItem(
        headlineContent = {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (playing) FontWeight.Bold else null,
            )
        },
        supportingContent = {
            Text("$artist · ${formatDuration(song.durationMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = menu?.let { { SongMenuButton(song, it) } },
        modifier = Modifier
            .clickable(onClickLabel = stringResource(R.string.songs_play), role = Role.Button, onClick = onClick)
            .semantics { if (playing) stateDescription = playingText },
    )
}

@Composable
private fun SongMenuButton(song: Song, menu: SongMenu) {
    var open by remember { mutableStateOf(false) }
    var links by remember { mutableStateOf<SongLinks?>(null) }
    LaunchedEffect(open) {
        if (open) links = menu.links(song)
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.song_menu, song.title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.play_next)) }, onClick = {
                open = false
                menu.playNext(song)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = {
                open = false
                menu.addToQueue(song)
            })
            val found = links
            found?.albumKey?.let { key ->
                DropdownMenuItem(text = { Text(stringResource(R.string.go_to_album)) }, onClick = {
                    open = false
                    menu.openAlbum(key)
                })
            }
            found?.artists?.forEach { artist ->
                DropdownMenuItem(text = { Text(stringResource(R.string.go_to_artist, artist.name)) }, onClick = {
                    open = false
                    menu.openArtist(artist.key)
                })
            }
            found?.folder?.let { folder ->
                DropdownMenuItem(text = { Text(stringResource(R.string.go_to_folder)) }, onClick = {
                    open = false
                    menu.openFolder(folder.volume, folder.relativePath)
                })
            }
        }
    }
}
