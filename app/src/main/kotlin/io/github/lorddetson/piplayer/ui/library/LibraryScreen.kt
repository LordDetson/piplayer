package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.compose.foundation.layout.size
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.debug.DebugEntry
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.AlbumCredit
import io.github.lorddetson.piplayer.library.lists.AlbumGroup
import io.github.lorddetson.piplayer.library.lists.ArtistGroup
import io.github.lorddetson.piplayer.library.lists.FolderEntry
import io.github.lorddetson.piplayer.library.lists.FolderLevels
import io.github.lorddetson.piplayer.library.lists.GenreGroup
import androidx.compose.ui.text.font.FontStyle
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.ListOrder
import io.github.lorddetson.piplayer.ui.songs.AudioAccess
import io.github.lorddetson.piplayer.ui.songs.NoMusic
import io.github.lorddetson.piplayer.ui.songs.PermissionCard
import io.github.lorddetson.piplayer.ui.songs.Scanning
import io.github.lorddetson.piplayer.ui.songs.SongMenu
import io.github.lorddetson.piplayer.ui.songs.SelectionFavorites
import io.github.lorddetson.piplayer.ui.player.QueueTarget
import io.github.lorddetson.piplayer.ui.songs.SongsScreen
import io.github.lorddetson.piplayer.ui.songs.SongsState
import io.github.lorddetson.piplayer.ui.songs.SongsViewModel
import io.github.lorddetson.piplayer.ui.songs.rememberAudioAccess
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.ui.covers.CoverActions
import io.github.lorddetson.piplayer.ui.covers.LibraryCover
import io.github.lorddetson.piplayer.ui.playlists.PlaylistActions
import io.github.lorddetson.piplayer.ui.playlists.PlaylistsContent

object LibraryTags {
    const val TABS = "library_tabs"
    const val LIST = "library_list"
    const val TOP_BAR = "library_top_bar"
    const val GRID = "library_grid"
    const val ALBUM_FAVORITE = "album_favorite"
    const val FAVORITES_CHIP = "favorites_chip"
}

/** Test tags of the Genres tab and the genre pages (docs/library.md §19.2, §19.3). */
object GenreTags {
    /** A genre's row on the Genres tab. */
    const val ROW = "genre_row"
    const val PLAY = "genre_play"
    const val SHUFFLE = "genre_shuffle"
}

/** What the library screens need from the app: playing, the mini player, and the way to the pages. */
class LibraryHost(
    val player: Player?,
    val cover: ImageBitmap?,
    /** The current song's album, which colors the mini player's placeholder (docs/library.md §13.4). */
    val coverName: String? = null,
    val snackbar: SnackbarHostState,
    val currentSongId: Long?,
    val menu: SongMenu,
    val play: (row: Int, song: Song?, list: SongQuery) -> Unit,
    /** "Play next" ([next]) and "Add to queue" of an album, an artist or a folder (docs/playback.md §9.1). */
    val addToQueue: (target: QueueTarget, next: Boolean) -> Unit,
    val openNowPlaying: () -> Unit,
    val openAlbum: (key: String) -> Unit,
    val openArtist: (key: String) -> Unit,
    val openFolder: (volume: String, relativePath: String) -> Unit,
    /** Own covers of songs and albums (docs/library.md §13.2). */
    val covers: CoverActions? = null,
    /** Playlists: their tab, their pages and "Add to playlist" (docs/library.md §16.3). */
    val playlists: PlaylistActions? = null,
    /** Turns shuffle on and plays a list from a song: a smart list's "Shuffle" (docs/library.md §17.2). */
    val shuffle: (row: Int, song: Song?, list: SongQuery) -> Unit = { _, _, _ -> },
    /** Opens the page of the genre [key] (docs/library.md §19.3). */
    val openGenre: (key: String) -> Unit = {},
    /** "Play" of selected songs (docs/library.md §20.3). */
    val playSelection: (QueueTarget) -> Unit = {},
    /** Favorites of selected songs (docs/library.md §20.3). */
    val favoriteBatch: SelectionFavorites? = null,
)

/**
 * The tabs in their order (docs/library.md §19.2): the first three are the root of the controller tree, the last three its
 * node "More" (docs/playback.md §10.1).
 */
private enum class LibraryTab(val label: Int) {
    SONGS(R.string.tab_songs),
    ALBUMS(R.string.tab_albums),
    ARTISTS(R.string.tab_artists),
    PLAYLISTS(R.string.tab_playlists),
    GENRES(R.string.tab_genres),
    FOLDERS(R.string.tab_folders),
}

/**
 * The library screen (docs/library.md §6.1): the top bar with search, sort and the settings, then the tabs Songs, Albums,
 * Artists, Playlists, Genres and Folders. The search filters the tab shown. The mini player hangs above the bottom bar
 * at the app's root (PIM-18).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(host: LibraryHost, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val access = rememberAudioAccess()
    // Kept by its name, so that a new tab never turns a kept choice into another one (docs/library.md §19.2).
    var tabName by rememberSaveable { mutableStateOf(LibraryTab.SONGS.name) }
    val tab = LibraryTab.entries.firstOrNull { it.name == tabName } ?: LibraryTab.SONGS
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }

    val songs = viewModel(key = "songs") { SongsViewModel(app, saved = createSavedStateHandle()) }
    // The songs the Folders tab shows itself, while it shows some (docs/library.md §20.1).
    var folderSongs by remember { mutableStateOf<SongsViewModel?>(null) }
    val selecting = when (tab) {
        LibraryTab.SONGS -> songs
        LibraryTab.FOLDERS -> folderSongs
        else -> null
    }
    val selected = selecting?.selection?.collectAsStateWithLifecycle()?.value
    val albums = viewModel(key = "albums") { AlbumsViewModel(app) }
    val artists = viewModel(key = "artists") { ArtistsViewModel(app) }
    val genres = viewModel(key = "genres") { GenresViewModel(app) }
    val folders = viewModel(key = "folders") { FolderPageViewModel(app, folder = null) }
    LaunchedEffect(search) {
        songs.setSearch(search)
        albums.setSearch(search)
        artists.setSearch(search)
        genres.setSearch(search)
        folders.setSearch(search)
    }

    val sortControl: @Composable () -> Unit = {
        when (tab) {
            LibraryTab.SONGS -> SortButton(ListName.SONGS, songs.order.collectAsStateWithLifecycle().value, songs::setOrder)
            LibraryTab.ALBUMS -> SortButton(
                ListName.ALBUMS, albums.order.collectAsStateWithLifecycle().value, albums::setOrder,
                grid = albums.grid.collectAsStateWithLifecycle().value, onGrid = albums::setGrid,
            )
            LibraryTab.ARTISTS -> SortButton(
                ListName.ARTISTS, artists.order.collectAsStateWithLifecycle().value, artists::setOrder,
                grid = artists.grid.collectAsStateWithLifecycle().value, onGrid = artists::setGrid,
            )
            LibraryTab.FOLDERS -> SortButton(ListName.FOLDERS, folders.order.collectAsStateWithLifecycle().value, folders::setOrder)
            // Playlists sort by name only (docs/library.md §16.3).
            LibraryTab.PLAYLISTS -> Unit
            LibraryTab.GENRES -> SortButton(ListName.GENRES, genres.order.collectAsStateWithLifecycle().value, genres::setOrder)
        }
    }
    Scaffold(
        topBar = {
            Column {
                if (selecting != null && selected != null) SongsSelectionBar(host, selecting, selected) else LibraryTopBar(
                    title = stringResource(R.string.app_name),
                    onBack = null,
                    searching = searching,
                    search = search,
                    onSearch = { search = it },
                    onSearching = { open ->
                        searching = open
                        if (!open) search = ""
                    },
                    sort = sortControl,
                ) {
                    DebugEntry()
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_open))
                    }
                }
                PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.testTag(LibraryTags.TABS), edgePadding = 8.dp) {
                    LibraryTab.entries.forEach { entry ->
                        Tab(
                            selected = entry == tab,
                            onClick = {
                                // The selection belongs to the list it was made in (docs/library.md §20.2, 9).
                                if (entry != tab) {
                                    songs.endSelection()
                                    folderSongs?.endSelection()
                                }
                                tabName = entry.name
                            },
                            text = { Text(stringResource(entry.label)) },
                        )
                    }
                }
            }
        },
        // The mini player now hangs above the bottom bar at the app's root, with it on the three top-level places
        // (PIM-18, docs/library.md §6.1); the pages keep their own (docs/library.md §6.1).
        snackbarHost = { SnackbarHost(host.snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        // The empty states of the tabs (docs/library.md §14.1).
        val scanning by songs.scanning.collectAsStateWithLifecycle()
        val empty = TabEmptyStates(scanning, onOpenSettings)
        when (tab) {
            LibraryTab.SONGS -> SongsScreen(
                access = access,
                viewModel = songs,
                onSongClick = { row, song, list -> host.play(row, song, list) },
                modifier = modifier,
                currentSongId = host.currentSongId,
                menu = host.menu,
                emptyText = if (search.isBlank()) null else stringResource(R.string.search_nothing_found),
                onOpenLibrarySettings = onOpenSettings,
            )
            LibraryTab.ALBUMS -> {
                val favoritesOnly by albums.favoritesOnly.collectAsStateWithLifecycle()
                Groups(
                    access, albums, modifier, search, empty,
                    sections = { list -> list.map { it.section } },
                    tiles = { list -> albumTiles(list, host) },
                    header = { FavoritesChip(favoritesOnly, albums::setFavoritesOnly) },
                    emptyText = if (favoritesOnly) stringResource(R.string.favorite_albums_none) else null,
                ) { list -> albumItems(list, host) }
            }
            LibraryTab.ARTISTS -> Groups(
                access, artists, modifier, search, empty,
                sections = { list -> list.map { it.section } },
                tiles = { list -> artistTiles(list, host) },
            ) { list -> artistItems(list, host) }
            LibraryTab.FOLDERS -> FolderContent(access, folders, folder = null, host, modifier, search, empty, onSongs = { folderSongs = it })
            LibraryTab.PLAYLISTS -> host.playlists?.let { PlaylistsContent(host, it, search, modifier) }
            // No grid: genres have no cover (docs/library.md §19.2).
            LibraryTab.GENRES -> Groups(
                access, genres, modifier, search, empty,
                sections = { list -> list.map { it.section } },
                tiles = {},
            ) { list -> genreItems(list, host) }
        }
    }
}

private fun LazyListScope.genreItems(genres: List<GenreGroup>, host: LibraryHost) {
    items(genres, key = { "genre " + it.key }) { genre -> GenreRow(genre, host) }
}

/** "Unknown genre" in italics, so that a real genre of that name is never taken for it (docs/library.md §19.2). */
@Composable
internal fun genreName(genre: GenreGroup): String = if (genre.unknown) stringResource(R.string.genre_unknown) else genre.name

/** A genre with its number of songs and the menu of §19.2; "Unknown genre" in italics. */
@Composable
private fun GenreRow(genre: GenreGroup, host: LibraryHost) {
    val name = genreName(genre)
    ListItem(
        headlineContent = {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontStyle = if (genre.unknown) FontStyle.Italic else null,
            )
        },
        supportingContent = { Text(pluralStringResource(R.plurals.songs_count, genre.songs, genre.songs)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_genre), contentDescription = null) },
        trailingContent = { QueueMenuButton(stringResource(R.string.song_menu, name), queue(host, QueueTarget.Genre(genre.key))) },
        modifier = Modifier
            .clickable(onClickLabel = stringResource(R.string.open_genre), role = Role.Button) { host.openGenre(genre.key) }
            .testTag(GenreTags.ROW),
    )
}

/**
 * What a tab shows instead of an empty list (docs/library.md §14.1): while [scanning], the scan; once the library is ready
 * and has nothing, "No music found" with [openSettings].
 */
internal class TabEmptyStates(val scanning: Boolean, val openSettings: () -> Unit)

/**
 * A list or grid of groups bound to [viewModel] (docs/library.md §11.3), or the permission card without access; a long one
 * has the scroll thumb, whose bubble shows the groups' [sections].
 */
@Composable
private fun <T : List<*>> Groups(
    access: AudioAccess,
    viewModel: GroupsViewModel<T>,
    modifier: Modifier,
    search: String,
    empty: TabEmptyStates,
    sections: (T) -> List<String?>,
    tiles: LazyGridScope.(T) -> Unit,
    /** Above the list or grid, also when it is empty: the Albums tab's filter (docs/library.md §15.2). */
    header: (@Composable () -> Unit)? = null,
    /** What an empty list without a search says instead of "No music found". */
    emptyText: String? = null,
    items: LazyListScope.(T) -> Unit,
) {
    if (!access.granted) return PermissionCard(access.deniedForGood, access.request, access.openSettings, modifier)
    LifecycleStartEffect(viewModel, access.granted) {
        viewModel.onStart(access.granted)
        onStopOrDispose { viewModel.onStop() }
    }
    if (header == null) {
        GroupsContent(viewModel, modifier, search, empty, sections, tiles, emptyText, items)
    } else {
        Column(modifier) {
            header()
            GroupsContent(viewModel, Modifier.weight(1f).fillMaxWidth(), search, empty, sections, tiles, emptyText, items)
        }
    }
}

@Composable
private fun <T : List<*>> GroupsContent(
    viewModel: GroupsViewModel<T>,
    modifier: Modifier,
    search: String,
    empty: TabEmptyStates,
    sections: (T) -> List<String?>,
    tiles: LazyGridScope.(T) -> Unit,
    emptyText: String?,
    items: LazyListScope.(T) -> Unit,
) {
    when (val state = viewModel.state.collectAsStateWithLifecycle().value) {
        GroupsState.Loading -> if (empty.scanning) Scanning(modifier) else Loading(modifier)
        is GroupsState.Loaded -> if (state.value.isEmpty()) {
            when {
                search.isNotBlank() -> Empty(stringResource(R.string.search_nothing_found), modifier)
                emptyText != null -> Empty(emptyText, modifier)
                else -> NoMusic(empty.openSettings, modifier)
            }
        } else {
            val grid by viewModel.grid.collectAsStateWithLifecycle()
            val labels = remember(state.value) { sections(state.value) }
            Box(modifier.fillMaxSize()) {
                if (grid) {
                    val gridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(TILE_MIN_WIDTH),
                        state = gridState,
                        contentPadding = PaddingValues(GRID_PADDING),
                        modifier = Modifier.fillMaxSize().testTag(LibraryTags.GRID),
                    ) { tiles(state.value) }
                    ScrollThumb(gridState, { labels.getOrNull(it) }, Modifier.align(Alignment.TopEnd))
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize().testTag(LibraryTags.LIST), state = listState) { items(state.value) }
                    ScrollThumb(listState, { labels.getOrNull(it) }, Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }
}

/** The Albums tab's "Favorites" filter (docs/library.md §15.2): on, only favorite albums show. */
@Composable
private fun FavoritesChip(on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(stringResource(R.string.favorites_filter)) },
        leadingIcon = if (on) {
            { Icon(painterResource(R.drawable.ic_favorite), contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = Modifier.padding(horizontal = 16.dp).testTag(LibraryTags.FAVORITES_CHIP),
    )
}

/** Grid columns are at least this wide: two on a phone held upright (docs/library.md §11.3). */
internal val TILE_MIN_WIDTH = 150.dp

/** The padding around a grid's tiles. */
private val GRID_PADDING = 8.dp

/** The columns of a grid as wide as the window, as [GridCells.Adaptive] gives them inside [GRID_PADDING]. */
internal fun gridColumns(widthDp: Int): Int =
    ((widthDp - 2 * GRID_PADDING.value) / TILE_MIN_WIDTH.value).toInt().coerceAtLeast(1)

private fun LazyGridScope.albumTiles(albums: List<AlbumGroup>, host: LibraryHost) {
    items(albums, key = { "album " + it.key }) { album -> AlbumTile(album, host) }
}

private fun LazyGridScope.artistTiles(artists: List<ArtistGroup>, host: LibraryHost) {
    items(artists, key = { "artist " + it.key }) { artist -> ArtistTile(artist, host) }
}

/** An album in a grid: its cover as a square (docs/library.md §13.5), its title, then its artist and year. */
@Composable
internal fun AlbumTile(album: AlbumGroup, host: LibraryHost) {
    val details = listOfNotNull(creditText(album.credit), album.year?.toString()).joinToString(" · ")
    Tile(album.title, details, stringResource(R.string.open_album), albumMenu(host, album.key), { host.openAlbum(album.key) }) {
        LibraryCover(
            CoverRequest.Album(album.key),
            album.title,
            Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
            noteSize = 48.dp,
        )
    }
}

/** An artist in a grid: a circle with their cover or else their letter, their name, then their numbers of albums and songs. */
@Composable
internal fun ArtistTile(artist: ArtistGroup, host: LibraryHost) {
    val counts = pluralStringResource(R.plurals.albums_count, artist.albums, artist.albums) + " · " +
        pluralStringResource(R.plurals.songs_count, artist.songs, artist.songs)
    Tile(artist.name, counts, stringResource(R.string.open_artist), queue(host, QueueTarget.Artist(artist.key)), { host.openArtist(artist.key) }) {
        LibraryCover(CoverRequest.Artist(artist.key), null, Modifier.fillMaxWidth().aspectRatio(1f), shape = CircleShape) { modifier ->
            Box(contentAlignment = Alignment.Center, modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    artist.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** A tile of a grid: its picture, then its title and details beside the menu of step 9 ("Play next", "Add to queue"). */
@Composable
private fun Tile(
    title: String,
    details: String,
    clickLabel: String,
    menu: QueueMenu,
    onClick: () -> Unit,
    picture: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .padding(8.dp),
    ) {
        picture()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(details, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            QueueMenuButton(stringResource(R.string.song_menu, title), menu)
        }
    }
}

private fun LazyListScope.albumItems(albums: List<AlbumGroup>, host: LibraryHost) {
    items(albums, key = { "album " + it.key }) { album -> AlbumRow(album, host) }
}

private fun LazyListScope.artistItems(artists: List<ArtistGroup>, host: LibraryHost) {
    items(artists, key = { "artist " + it.key }) { artist ->
        val counts = pluralStringResource(R.plurals.albums_count, artist.albums, artist.albums) + " · " +
            pluralStringResource(R.plurals.songs_count, artist.songs, artist.songs)
        GroupRow(artist.name, counts, stringResource(R.string.open_artist), queue(host, QueueTarget.Artist(artist.key)), leading = {
            LibraryCover(CoverRequest.Artist(artist.key), null, size = 48.dp, shape = CircleShape) { modifier ->
                Box(modifier, contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_artist), contentDescription = null) }
            }
        }) {
            host.openArtist(artist.key)
        }
    }
}

@Composable
internal fun AlbumRow(album: AlbumGroup, host: LibraryHost) {
    val credit = creditText(album.credit)
    val details = listOfNotNull(credit, album.year?.toString(), pluralStringResource(R.plurals.songs_count, album.songs, album.songs))
    GroupRow(album.title, details.joinToString(" · "), stringResource(R.string.open_album), albumMenu(host, album.key), leading = {
        LibraryCover(CoverRequest.Album(album.key), album.title, size = 48.dp, shape = RoundedCornerShape(6.dp))
    }) {
        host.openAlbum(album.key)
    }
}

/**
 * What a row's or a page's menu adds to the queue (docs/playback.md §9.1); an album's menu also chooses or removes its
 * own cover (docs/library.md §13.2).
 */
internal class QueueMenu(
    val playNext: () -> Unit,
    val addToQueue: () -> Unit,
    val cover: CoverMenu? = null,
    /** "Add to playlist" (docs/library.md §16.3). */
    val addToPlaylist: (() -> Unit)? = null,
)

/** "Choose cover", and "Remove own cover" while [hasOwn]. */
internal class CoverMenu(val choose: () -> Unit, val remove: () -> Unit, val hasOwn: suspend () -> Boolean)

internal fun queue(host: LibraryHost, target: QueueTarget) = QueueMenu(
    playNext = { host.addToQueue(target, true) },
    addToQueue = { host.addToQueue(target, false) },
    addToPlaylist = host.playlists?.let { playlists -> { playlists.addToPlaylist(target) } },
)

/** The menu of the album [key]: the queue, and its own cover. */
internal fun albumMenu(host: LibraryHost, key: String) = QueueMenu(
    playNext = { host.addToQueue(QueueTarget.Album(key), true) },
    addToQueue = { host.addToQueue(QueueTarget.Album(key), false) },
    cover = host.covers?.let { covers ->
        CoverMenu(choose = { covers.chooseForAlbum(key) }, remove = { covers.removeForAlbum(key) }, hasOwn = { covers.albumHasOwn(key) })
    },
    addToPlaylist = host.playlists?.let { playlists -> { playlists.addToPlaylist(QueueTarget.Album(key)) } },
)

/** The menu button of a row or a page with "Play next" and "Add to queue". */
@Composable
internal fun QueueMenuButton(description: String, menu: QueueMenu) {
    var open by remember { mutableStateOf(false) }
    var ownCover by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) ownCover = menu.cover?.hasOwn?.invoke() == true
    }
    Box {
        IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more), description) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.play_next)) }, onClick = {
                open = false
                menu.playNext()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = {
                open = false
                menu.addToQueue()
            })
            menu.addToPlaylist?.let { add ->
                DropdownMenuItem(text = { Text(stringResource(R.string.add_to_playlist)) }, onClick = {
                    open = false
                    add()
                })
            }
            menu.cover?.let { cover ->
                DropdownMenuItem(text = { Text(stringResource(R.string.cover_choose)) }, onClick = {
                    open = false
                    cover.choose()
                })
                if (ownCover) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.cover_remove)) }, onClick = {
                        open = false
                        cover.remove()
                    })
                }
            }
        }
    }
}

@Composable
internal fun creditText(credit: AlbumCredit): String = when (credit) {
    is AlbumCredit.Named -> credit.name
    AlbumCredit.Various -> stringResource(R.string.various_artists)
    AlbumCredit.Unknown -> stringResource(R.string.songs_unknown_artist)
}

@Composable
private fun GroupRow(
    title: String,
    details: String?,
    clickLabel: String,
    menu: QueueMenu? = null,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = details?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = leading,
        trailingContent = menu?.let { { QueueMenuButton(stringResource(R.string.song_menu, title), it) } },
        modifier = Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
    )
}

/**
 * The Folders tab ([folder] null) or a folder page: "Play all, with subfolders", the subfolders (or, with a search, the
 * matching folders below), then the folder's own songs.
 */
@Composable
internal fun FolderContent(
    access: AudioAccess,
    viewModel: FolderPageViewModel,
    folder: FolderKey?,
    host: LibraryHost,
    modifier: Modifier,
    search: String,
    empty: TabEmptyStates? = null,
    /** The holder of the folder's own songs while they show, null otherwise: the screen's selection bar follows it. */
    onSongs: (SongsViewModel?) -> Unit = {},
) {
    // Null unless the songs below say otherwise in the same composition.
    var shownSongs: SongsViewModel? = null
    SideEffect { onSongs(shownSongs) }
    if (!access.granted) return PermissionCard(access.deniedForGood, access.request, access.openSettings, modifier)
    val app = LocalContext.current.applicationContext as Application
    LifecycleStartEffect(viewModel, access.granted) {
        viewModel.onStart(access.granted)
        onStopOrDispose { viewModel.onStop() }
    }
    val page = when (val state = viewModel.state.collectAsStateWithLifecycle().value) {
        GroupsState.Loading -> return if (empty?.scanning == true) Scanning(modifier) else Loading(modifier)
        is GroupsState.Loaded -> state.value
    }
    if (!page.exists) return Empty(stringResource(R.string.folder_not_found), modifier)
    val internal = stringResource(R.string.internal_storage)
    val folderItems: LazyListScope.() -> Unit = {
        items(page.entries, key = { "folder " + it.volume + "/" + it.relativePath }) { entry ->
            val name = if (entry.relativePath.isEmpty()) volumeName(entry.volume, internal) else entry.name
            val details = pluralStringResource(R.plurals.songs_count, entry.songs, entry.songs)
            GroupRow(name, if (page.searching) entry.relativePath + " · " + details else details,
                stringResource(R.string.open_folder), queue(host, QueueTarget.Folder(entry.volume, entry.relativePath)),
                leading = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) }) {
                host.openFolder(entry.volume, entry.relativePath)
            }
        }
    }
    val at = page.folder ?: folder
    if (at == null) {
        // A top of volumes: no songs of its own.
        if (page.entries.isEmpty()) {
            if (search.isBlank() && empty != null) return NoMusic(empty.openSettings, modifier)
            return Empty(stringResource(if (search.isBlank()) R.string.list_empty else R.string.search_nothing_found), modifier)
        }
        return Box(modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().testTag(LibraryTags.LIST), state = listState) { folderItems() }
            ScrollThumb(listState, { page.entries.getOrNull(it)?.section }, Modifier.align(Alignment.TopEnd))
        }
    }
    val songs = viewModel(key = "folder songs " + at.key) {
        SongsViewModel(app, SongQuery.folder(at.volume, at.relativePath), ListName.FOLDERS, createSavedStateHandle())
    }
    shownSongs = songs
    LaunchedEffect(songs, search) { songs.setSearch(search) }
    val order by songs.order.collectAsStateWithLifecycle()
    val songsState by songs.state.collectAsStateWithLifecycle()
    val playAll = page.entries.isNotEmpty() || (songsState as? SongsState.Loaded)?.songs?.isNotEmpty() == true
    SongsScreen(
        access = access,
        viewModel = songs,
        onSongClick = { row, song, list -> host.play(row, song, list) },
        modifier = modifier,
        currentSongId = host.currentSongId,
        menu = host.menu,
        listTag = LibraryTags.LIST,
        emptyText = stringResource(if (search.isBlank()) R.string.list_empty else R.string.search_nothing_found),
        headerItems = page.entries.size + if (playAll) 1 else 0,
        headerSections = (if (playAll) listOf(null) else emptyList()) + page.entries.map { it.section },
    ) {
        if (playAll) item(key = "play all") {
            Button(
                onClick = {
                    host.play(0, null, SongQuery.tree(at.volume, at.relativePath).copy(sort = SongSort.of(order.field) ?: SongSort.FILE_NAME, reversed = order.reversed, search = search.trim()))
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.folder_play_all)) }
        }
        folderItems()
    }
}

private fun volumeName(volume: String, internal: String) = if (volume == FolderLevels.PRIMARY) internal else volume

@Composable
internal fun Loading(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun Empty(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier.padding(16.dp))
}

/** The fields of each list in its sort menu, by their tokens in the settings. */
private val SORT_LABELS = mapOf(
    "title" to R.string.sort_title,
    "artist" to R.string.sort_artist,
    "album" to R.string.sort_album,
    "length" to R.string.sort_length,
    "name" to R.string.sort_name,
    "songs" to R.string.sort_songs,
    "track" to R.string.sort_track,
    "file" to R.string.sort_file_name,
)

/**
 * The sort action of a top bar: the list's fields and "Reverse order" (docs/library.md §6.3), and "Show as grid" when
 * [grid] says whether the list shows as one (§11.3).
 */
@Composable
internal fun SortButton(list: ListName, order: ListOrder, onOrder: (ListOrder) -> Unit, grid: Boolean? = null, onGrid: (Boolean) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.library_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (list.fields.size > 1) {
                for (field in list.fields) {
                    val label = when {
                        field == "year" && list == ListName.ARTIST_PAGE -> R.string.sort_album_year
                        field == "year" -> R.string.sort_year
                        else -> SORT_LABELS.getValue(field)
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { RadioButton(selected = order.field == field, onClick = null) },
                        onClick = {
                            open = false
                            onOrder(order.copy(field = field))
                        },
                    )
                }
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_reverse)) },
                leadingIcon = { Checkbox(checked = order.reversed, onCheckedChange = null) },
                modifier = Modifier.checkbox(order.reversed),
                onClick = {
                    open = false
                    onOrder(order.copy(reversed = !order.reversed))
                },
            )
            if (grid != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_show_as_grid)) },
                    leadingIcon = { Checkbox(checked = grid, onCheckedChange = null) },
                    modifier = Modifier.checkbox(grid),
                    onClick = {
                        open = false
                        onGrid(!grid)
                    },
                )
            }
        }
    }
}

/** A menu item with a check box says to TalkBack whether it is on; the box alone, without a click of its own, does not. */
private fun Modifier.checkbox(checked: Boolean) = semantics {
    role = Role.Checkbox
    toggleableState = ToggleableState(checked)
}

/** A top bar with an optional back action, a search field that opens over the title, the sort action and [actions]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryTopBar(
    title: String,
    onBack: (() -> Unit)?,
    searching: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    sort: @Composable () -> Unit,
    titleStyle: FontStyle? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                TextField(
                    value = search,
                    onValueChange = onSearch,
                    placeholder = { Text(stringResource(R.string.library_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontStyle = titleStyle, modifier = Modifier.semantics { heading() })
            }
        },
        navigationIcon = {
            onBack?.let { back ->
                IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
            }
        },
        actions = {
            if (searching) {
                IconButton(onClick = { onSearching(false) }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.library_search_close))
                }
            } else {
                IconButton(onClick = { onSearching(true) }) {
                    Icon(painterResource(R.drawable.ic_search), stringResource(R.string.library_search))
                }
            }
            sort()
            actions()
        },
        modifier = Modifier.testTag(LibraryTags.TOP_BAR),
    )
}
