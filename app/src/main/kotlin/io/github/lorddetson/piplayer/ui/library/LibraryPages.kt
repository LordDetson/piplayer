package io.github.lorddetson.piplayer.ui.library

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.FolderLevels
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.ui.player.MiniPlayer
import io.github.lorddetson.piplayer.ui.songs.PermissionCard
import io.github.lorddetson.piplayer.ui.songs.SongsScreen
import io.github.lorddetson.piplayer.ui.songs.SongsViewModel
import io.github.lorddetson.piplayer.ui.songs.rememberAudioAccess

/** A page's frame: the top bar with back, search and sort, and the mini player (docs/library.md §6.1). */
@Composable
private fun PageScaffold(
    title: String,
    host: LibraryHost,
    onBack: () -> Unit,
    searching: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    sort: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = { LibraryTopBar(title, onBack, searching, search, onSearch, onSearching, sort) },
        bottomBar = { MiniPlayer(host.player, host.cover, onOpen = host.openNowPlaying) },
        snackbarHost = { SnackbarHost(host.snackbar) },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

/** The page of the album [key]: its title, artist and year, then its songs by disc and track. */
@Composable
fun AlbumPage(key: String, host: LibraryHost, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val access = rememberAudioAccess()
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val header = viewModel(key = "album") { AlbumPageViewModel(app, key) }
    val songs = viewModel(key = "songs") { SongsViewModel(app, SongQuery.album(key), ListName.ALBUM_PAGE) }
    LaunchedEffect(search) { songs.setSearch(search) }
    LifecycleStartEffect(header, access.granted) {
        header.onStart(access.granted)
        onStopOrDispose { header.onStop() }
    }
    val state = header.state.collectAsStateWithLifecycle().value
    val album = (state as? GroupsState.Loaded)?.value
    PageScaffold(
        title = album?.title.orEmpty(),
        host = host,
        onBack = onBack,
        searching = searching,
        search = search,
        onSearch = { search = it },
        onSearching = { open -> searching = open; if (!open) search = "" },
        sort = { SortButton(ListName.ALBUM_PAGE, songs.order.collectAsStateWithLifecycle().value, songs::setOrder) },
    ) { modifier ->
        when {
            !access.granted -> PermissionCard(access.deniedForGood, access.request, access.openSettings, modifier)
            state is GroupsState.Loading -> Loading(modifier)
            album == null -> Empty(stringResource(R.string.album_not_found), modifier)
            else -> SongsScreen(
                access = access,
                viewModel = songs,
                onSongClick = { row, song, list -> host.play(row, song, list) },
                modifier = modifier,
                currentSongId = host.currentSongId,
                menu = host.menu,
                listTag = LibraryTags.LIST,
                emptyText = stringResource(R.string.search_nothing_found),
                discHeaders = true,
                headerItems = 1,
            ) {
                item(key = "album header") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(album.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        val details = listOfNotNull(
                            creditText(album.credit),
                            album.year?.toString(),
                            pluralStringResource(R.plurals.songs_count, album.songs, album.songs),
                        )
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** The page of the artist [key]: their albums by year, then all their songs. */
@Composable
fun ArtistPage(key: String, host: LibraryHost, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val access = rememberAudioAccess()
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val header = viewModel(key = "artist") { ArtistPageViewModel(app, key) }
    val songs = viewModel(key = "songs") { SongsViewModel(app, SongQuery.artist(key), ListName.ARTIST_PAGE) }
    LaunchedEffect(search) {
        songs.setSearch(search)
        header.setSearch(search)
    }
    LifecycleStartEffect(header, access.granted) {
        header.onStart(access.granted)
        onStopOrDispose { header.onStop() }
    }
    val state = header.state.collectAsStateWithLifecycle().value
    val page = (state as? GroupsState.Loaded)?.value
    PageScaffold(
        title = page?.artist?.name.orEmpty(),
        host = host,
        onBack = onBack,
        searching = searching,
        search = search,
        onSearch = { search = it },
        onSearching = { open -> searching = open; if (!open) search = "" },
        sort = { SortButton(ListName.ARTIST_PAGE, songs.order.collectAsStateWithLifecycle().value, songs::setOrder) },
    ) { modifier ->
        when {
            !access.granted -> PermissionCard(access.deniedForGood, access.request, access.openSettings, modifier)
            state is GroupsState.Loading -> Loading(modifier)
            page == null -> Empty(stringResource(R.string.artist_not_found), modifier)
            else -> SongsScreen(
                access = access,
                viewModel = songs,
                onSongClick = { row, song, list -> host.play(row, song, list) },
                modifier = modifier,
                currentSongId = host.currentSongId,
                menu = host.menu,
                listTag = LibraryTags.LIST,
                emptyText = stringResource(R.string.search_nothing_found),
                headerItems = page.albums.size + if (page.albums.isEmpty()) 1 else 2,
            ) {
                if (page.albums.isNotEmpty()) {
                    item(key = "albums heading") { SectionHeading(stringResource(R.string.artist_albums)) }
                    for (album in page.albums) {
                        item(key = "album " + album.key) { AlbumRow(album) { host.openAlbum(album.key) } }
                    }
                }
                item(key = "songs heading") { SectionHeading(stringResource(R.string.artist_songs)) }
            }
        }
    }
}

/** The page of a folder: "Play all, with subfolders", its subfolders, then its own songs. */
@Composable
fun FolderPage(volume: String, relativePath: String, host: LibraryHost, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val access = rememberAudioAccess()
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val folder = FolderKey(volume, relativePath)
    val page = viewModel(key = "folder") { FolderPageViewModel(app, folder) }
    LaunchedEffect(search) { page.setSearch(search) }
    val title = when {
        relativePath.isNotEmpty() -> relativePath.dropLast(1).substringAfterLast('/')
        volume == FolderLevels.PRIMARY -> stringResource(R.string.internal_storage)
        else -> volume
    }
    PageScaffold(
        title = title,
        host = host,
        onBack = onBack,
        searching = searching,
        search = search,
        onSearch = { search = it },
        onSearching = { open -> searching = open; if (!open) search = "" },
        sort = { SortButton(ListName.FOLDERS, page.order.collectAsStateWithLifecycle().value, page::setOrder) },
    ) { modifier -> FolderContent(access, page, folder, host, modifier, search) }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}
