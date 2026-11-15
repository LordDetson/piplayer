package io.github.lorddetson.piplayer.ui.playlists

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.playlists.PlaylistItem
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.ui.formatDuration
import io.github.lorddetson.piplayer.ui.library.LibraryHost
import io.github.lorddetson.piplayer.ui.player.MiniPlayer
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.ui.songs.PermissionCard
import io.github.lorddetson.piplayer.ui.songs.SongMenu
import io.github.lorddetson.piplayer.ui.songs.TrackMenuItems
import io.github.lorddetson.piplayer.ui.songs.TrackMenuPlace
import io.github.lorddetson.piplayer.ui.songs.rememberAudioAccess
import io.github.lorddetson.piplayer.userdata.PlaylistSummary
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import androidx.lifecycle.createSavedStateHandle
import io.github.lorddetson.piplayer.ui.player.QueueTarget
import io.github.lorddetson.piplayer.ui.selection.RowSelection
import io.github.lorddetson.piplayer.ui.selection.SavedSelections
import io.github.lorddetson.piplayer.ui.selection.SelectionBack
import io.github.lorddetson.piplayer.ui.selection.SelectionBar
import io.github.lorddetson.piplayer.ui.selection.SelectionCheck
import io.github.lorddetson.piplayer.ui.selection.SelectionHolder
import io.github.lorddetson.piplayer.ui.selection.SelectionMenuItems
import io.github.lorddetson.piplayer.ui.selection.SelectionUi
import io.github.lorddetson.piplayer.ui.selection.isSelected
import io.github.lorddetson.piplayer.ui.selection.isWholeList
import io.github.lorddetson.piplayer.ui.selection.selectableRow
import io.github.lorddetson.piplayer.ui.songs.FavoriteBatch
import io.github.lorddetson.piplayer.ui.songs.SelectionMenuState
import io.github.lorddetson.piplayer.ui.songs.SelectionPlace
import io.github.lorddetson.piplayer.ui.songs.TrackMenu
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry
import io.github.lorddetson.piplayer.ui.songs.TrackMenuOffers
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The playlist as the page's header knows it: still loading, gone, or found. */
sealed interface PlaylistHeader {
    data object Loading : PlaylistHeader

    data object Gone : PlaylistHeader

    data class Found(val playlist: PlaylistSummary) : PlaylistHeader
}

/**
 * A playlist's page (docs/library.md §16.3): the playlist's name and count, and its entries with their songs, read and
 * resolved off the main thread again whenever the entries, the library, its hidden files, a batch of the tag pass or the
 * identities change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPageViewModel(
    application: Application,
    private val id: Long,
    /** Keeps the selection of entries for process death (docs/library.md §20.5). */
    saved: SavedStateHandle? = null,
) : AndroidViewModel(application) {

    /** The selected entries by their IDs (docs/library.md §20.1): a playlist may hold a song twice. */
    val selection = SelectionHolder(SavedSelections.read(saved)) { SavedSelections.write(saved, it) }

    private val playlists = UserData.playlists(application)

    val header: StateFlow<PlaylistHeader> = playlists.playlistFlow(id)
        .map { it?.let(PlaylistHeader::Found) ?: PlaylistHeader.Gone }
        .catch { emit(PlaylistHeader.Gone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlaylistHeader.Loading)

    /** The entries in order; null until the first read. */
    val items: StateFlow<List<PlaylistItem>?> = combine(
        playlists.entryRowsFlow(id),
        SongList.changes(application),
        SongList.batches,
        IdentityAttacher.changes,
    ) { rows, _, _, _ -> rows }
        .mapLatest { rows -> withContext(Dispatchers.IO) { Library.playlists(application).itemsOf(rows) } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The page of the playlist [id] (docs/library.md §16.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPage(id: Long, host: LibraryHost, actions: PlaylistActions, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val access = rememberAudioAccess()
    val model = viewModel(key = "playlist $id") { PlaylistPageViewModel(app, id, createSavedStateHandle()) }
    val header by model.header.collectAsStateWithLifecycle()
    val items by model.items.collectAsStateWithLifecycle()
    val found = (header as? PlaylistHeader.Found)?.playlist
    val selected by model.selection.state.collectAsStateWithLifecycle()
    val pageScope = rememberCoroutineScope()
    // The selection is judged against the entries once they are read (docs/library.md §20.2, 8).
    LaunchedEffect(items) { items?.let { shown -> model.selection.onList(shown.map { it.entryId }, "") } }
    // A playlist deleted while its page is open, from its menu or elsewhere, takes the page with it.
    var wasFound by remember { mutableStateOf(false) }
    LaunchedEffect(header) {
        if (header is PlaylistHeader.Found) wasFound = true
        if (header == PlaylistHeader.Gone && wasFound) onBack()
    }
    Scaffold(
        topBar = {
            val selection = selected
            if (selection != null) {
                EntriesSelectionBar(id, selection, model.selection, items.orEmpty(), host, actions, pageScope)
            } else TopAppBar(
                title = {
                    Text(found?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
                },
                actions = {
                    found?.let { PlaylistMenuButton(id, it.name, host, actions, withPlay = false, description = stringResource(R.string.page_menu)) }
                },
            )
        },
        bottomBar = { MiniPlayer(host.player, host.cover, onOpen = host.openNowPlaying, coverName = host.coverName) },
        snackbarHost = { SnackbarHost(host.snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        val shown = items
        when {
            !access.granted -> PermissionCard(access.deniedForGood, access.request, access.openSettings, modifier)
            header == PlaylistHeader.Gone -> Text(
                stringResource(R.string.playlist_not_found),
                style = MaterialTheme.typography.bodyLarge,
                modifier = modifier.padding(16.dp),
            )
            shown == null || found == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> Entries(id, shown, host, modifier, model.selection, selected)
        }
    }
}

/** The header with the counts and "Play", and the entries, which drag and move by their accessibility actions. */
@Composable
private fun Entries(
    id: Long,
    items: List<PlaylistItem>,
    host: LibraryHost,
    modifier: Modifier,
    selection: SelectionHolder<Long>,
    selected: SelectionUi<Long>?,
) {
    val app = LocalContext.current.applicationContext as Application
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val choices = remember(app) { PlaylistChoices(app) }
    // The order while dragging, in memory; the database's again once it gives new entries. One state for the screen's
    // life, so that the drag callbacks of an earlier composition read the same one.
    val shownState = remember { mutableStateOf(items) }
    LaunchedEffect(items) { shownState.value = items }
    val shown = shownState.value
    val latest by rememberUpdatedState(items)
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    val list = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        val current = shownState.value
        val fromIndex = current.indexOfFirst { it.entryId == from.key }
        val toIndex = current.indexOfFirst { it.entryId == to.key }
        if (fromIndex >= 0 && toIndex >= 0) shownState.value = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }
    val failed = { scope.launch { host.snackbar.showSnackbar(resources.getString(R.string.playlist_failed)) }; Unit }
    // A drop moves to a place; "Move up" and "Move down" go one step from where the entry stands in the database, so
    // that a second one sent before the page shows the first goes on from there.
    val move = { item: PlaylistItem, to: Int ->
        PlaylistChoices.scope.launch { if (choices.move(item.entryId, to) == null) failed() }
        Unit
    }
    val step = { item: PlaylistItem, by: Int ->
        PlaylistChoices.scope.launch { if (choices.moveBy(item.entryId, by) == null) failed() }
        Unit
    }
    val remove = { item: PlaylistItem ->
        PlaylistChoices.scope.launch {
            val removed = choices.remove(item.entryId) ?: return@launch failed()
            scope.launch {
                val title = item.title ?: resources.getString(R.string.playlist_unknown_title)
                val result = host.snackbar.showSnackbar(
                    resources.getString(R.string.playlist_removed, title),
                    resources.getString(R.string.queue_undo),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) PlaylistChoices.scope.launch { choices.restore(removed) }
            }
        }
        Unit
    }
    val available = shown.count { it.available }
    LazyColumn(modifier.testTag(PlaylistTags.PAGE_LIST), state = list) {
        item(key = HEADER) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val counts = listOfNotNull(
                    pluralStringResource(R.plurals.songs_count, shown.size, shown.size),
                    (shown.size - available).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.playlist_unavailable_count, it, it) },
                )
                Text(counts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { host.play(0, null, SongQuery.playlist(id)) },
                    enabled = available > 0,
                    modifier = Modifier.padding(top = 8.dp).testTag(PlaylistTags.PLAY),
                ) { Text(stringResource(R.string.playlist_play)) }
            }
        }
        itemsIndexed(shown, key = { _, item -> item.entryId }) { place, item ->
            ReorderableItem(reorder, key = item.entryId) { dragging ->
                EntryRow(
                    item = item,
                    playing = item.song != null && item.song.id == host.currentSongId,
                    dragging = dragging,
                    onClick = {
                        val song = item.song
                        if (song == null) {
                            scope.launch { host.snackbar.showSnackbar(resources.getString(R.string.playlist_entry_unavailable)) }
                        } else {
                            // The row among the songs the playlist plays: the tapped copy of a song that stands twice.
                            host.play(shown.subList(0, place).count { it.available }, song, SongQuery.playlist(id))
                        }
                    },
                    menu = host.menu,
                    row = RowSelection(
                        selecting = selected != null,
                        selected = selected?.selection?.isSelected(item.entryId) == true,
                    ) { selection.toggle(item.entryId) },
                    onMoveUp = if (place > 0) ({ step(item, -1) }) else null,
                    onMoveDown = if (place < shown.lastIndex) ({ step(item, 1) }) else null,
                    onRemove = { remove(item) },
                    handle = Modifier.draggableHandle(
                        onDragStarted = { dragFrom = latest.indexOfFirst { it.entryId == item.entryId } },
                        onDragStopped = {
                            val from = dragFrom
                            val to = shownState.value.indexOfFirst { it.entryId == item.entryId }
                            dragFrom = null
                            if (from != null && from >= 0 && to >= 0 && from != to) move(item, to)
                        },
                    ),
                )
            }
        }
    }
}

private const val HEADER = "header"

@Composable
private fun EntryRow(
    item: PlaylistItem,
    playing: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    menu: SongMenu,
    row: RowSelection,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: () -> Unit,
    handle: Modifier,
) {
    val title = item.title ?: stringResource(R.string.playlist_unknown_title)
    val details = if (item.available) {
        "${item.artist ?: stringResource(R.string.songs_unknown_artist)} · ${formatDuration(item.durationMs)}"
    } else {
        stringResource(R.string.playlist_unavailable)
    }
    val playingText = stringResource(R.string.song_playing)
    val moveUp = stringResource(R.string.queue_move_up)
    val moveDown = stringResource(R.string.queue_move_down)
    ListItem(
        headlineContent = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    playing -> MaterialTheme.colorScheme.primary
                    item.available -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (playing) FontWeight.Bold else null,
            )
        },
        supportingContent = { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            if (row.selecting) {
                // Rows do not move while selected (docs/library.md §20.2, 3).
                SelectionCheck(row)
            } else {
                // A handle for the finger only: TalkBack moves the row with its own actions, so the handle says nothing.
                Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = null, modifier = handle.minimumInteractiveComponentSize())
            }
        },
        trailingContent = if (row.selecting) null else ({ EntryMenu(title, item.song, menu, onMoveUp, onMoveDown, onRemove) }),
        tonalElevation = if (dragging) 4.dp else 0.dp,
        shadowElevation = if (dragging) 4.dp else 0.dp,
        modifier = Modifier
            .testTag(PlaylistTags.ROW)
            .selectableRow(
                row,
                stringResource(R.string.songs_play),
                onClick,
                actions = listOfNotNull(
                    onMoveUp?.let { move -> CustomAccessibilityAction(moveUp) { move(); true } },
                    onMoveDown?.let { move -> CustomAccessibilityAction(moveDown) { move(); true } },
                ),
            )
            .semantics { if (playing && !row.selecting) stateDescription = playingText },
    )
}

/**
 * An entry's menu (docs/playback.md §9.1): the track menu of an available song, then "Move up", "Move down" and "Remove
 * from playlist"; an entry not in the library has only those three.
 */
@Composable
private fun EntryMenu(
    title: String,
    song: Song?,
    menu: SongMenu,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more), stringResource(R.string.song_menu, title)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item = @Composable { label: Int, action: () -> Unit ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    open = false
                    action()
                })
            }
            song?.let { TrackMenuItems(it, TrackMenuPlace.PLAYLIST, menu, close = { open = false }) }
            onMoveUp?.let { item(R.string.queue_move_up, it) }
            onMoveDown?.let { item(R.string.queue_move_down, it) }
            item(R.string.playlist_remove, onRemove)
        }
    }
}

/**
 * The selection bar of a playlist page (docs/library.md §20): its menu acts on the selected entries in the playlist's
 * order; songs not in the library are skipped by everything but "Remove from playlist", which has one "Undo" for all.
 */
@Composable
private fun EntriesSelectionBar(
    id: Long,
    state: SelectionUi<Long>,
    selection: SelectionHolder<Long>,
    items: List<PlaylistItem>,
    host: LibraryHost,
    actions: PlaylistActions,
    /** The page's scope: the message of a removal outlives the bar, which goes with the selection. */
    scope: CoroutineScope,
) {
    val app = LocalContext.current.applicationContext as Application
    val resources = LocalResources.current
    val choices = remember(app) { PlaylistChoices(app) }
    SelectionBack(selecting = true, onEnd = selection::end)
    SelectionBar(state, onClose = selection::end, onSelectAll = selection::selectAll, onDeselectAll = selection::end) { close ->
        val chosen = remember(state, items) { items.filter { state.selection.isSelected(it.entryId) } }
        val songs = chosen.mapNotNull { it.song }
        var favorites by remember(state) { mutableStateOf<FavoriteBatch?>(null) }
        LaunchedEffect(state) { favorites = host.favoriteBatch?.state?.invoke(songs.map { it.id }) }
        val offers = TrackMenuOffers(favorites = host.favoriteBatch != null, covers = false, details = host.menu.details != null)
        val entries = TrackMenu.selectionEntries(SelectionPlace.PLAYLIST, offers, SelectionMenuState(chosen.size, songs.size, favorites))
        SelectionMenuItems(entries, close) { entry ->
            // The whole playlist goes as the playlist (§20.6), any other selection as its songs in order.
            val target = if (state.selection.isWholeList && songs.size == items.count { it.available }) {
                QueueTarget.Playlist(id)
            } else {
                QueueTarget.Songs(songs.map { it.id })
            }
            when (entry) {
                TrackMenuEntry.Play -> host.playSelection(target)
                TrackMenuEntry.PlayNext -> host.addToQueue(target, true)
                TrackMenuEntry.AddToQueue -> host.addToQueue(target, false)
                TrackMenuEntry.AddToPlaylist -> return@SelectionMenuItems actions.addSelected(target, selection::end)
                is TrackMenuEntry.Favorite -> host.favoriteBatch?.set?.invoke(songs.map { it.id }, !entry.favorite)
                TrackMenuEntry.Details -> songs.singleOrNull()?.let { host.menu.details?.invoke(it) }
                TrackMenuEntry.RemoveFromPlaylist -> PlaylistChoices.scope.launch {
                    val removed = choices.removeAll(chosen.map { it.entryId })
                    if (removed == null) {
                        scope.launch { host.snackbar.showSnackbar(resources.getString(R.string.playlist_failed)) }
                        return@launch
                    }
                    scope.launch {
                        val result = host.snackbar.showSnackbar(
                            resources.getQuantityString(R.plurals.selection_removed_from_playlist, removed.size, removed.size),
                            resources.getString(R.string.queue_undo),
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) PlaylistChoices.scope.launch { choices.restoreAll(removed) }
                    }
                }
                else -> Unit
            }
            selection.end()
        }
    }
}
