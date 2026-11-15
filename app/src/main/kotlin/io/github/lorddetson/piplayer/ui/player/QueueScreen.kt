package io.github.lorddetson.piplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.playback.QueueIds
import io.github.lorddetson.piplayer.ui.formatDuration
import io.github.lorddetson.piplayer.ui.songs.SongMenu
import io.github.lorddetson.piplayer.ui.songs.TrackMenuItems
import io.github.lorddetson.piplayer.ui.songs.TrackMenuPlace
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.ui.selection.RowSelection
import io.github.lorddetson.piplayer.ui.selection.SelectionBack
import io.github.lorddetson.piplayer.ui.selection.SelectionBar
import io.github.lorddetson.piplayer.ui.selection.SelectionCheck
import io.github.lorddetson.piplayer.ui.selection.SelectionMenuItems
import io.github.lorddetson.piplayer.ui.selection.SelectionUi
import io.github.lorddetson.piplayer.ui.selection.isSelected
import io.github.lorddetson.piplayer.ui.selection.selectableRow
import io.github.lorddetson.piplayer.ui.songs.FavoriteBatch
import io.github.lorddetson.piplayer.ui.songs.SelectionMenuState
import io.github.lorddetson.piplayer.ui.songs.SelectionPlace
import io.github.lorddetson.piplayer.ui.songs.TrackMenu
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry
import io.github.lorddetson.piplayer.ui.songs.TrackMenuOffers
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Test tags of the queue screen (docs/playback.md §9.2). */
object QueueTags {
    const val LIST = "queue_list"
    const val ROW = "queue_row"

    /** The drag handle of the entry with [key]. */
    fun handle(key: String) = "queue_handle_$key"
}

/** An entry of the queue as the screen shows it: its [row] in the list, and a [key] for the lazy list. */
data class QueueEntry(
    val row: Int,
    val mediaId: String,
    val key: String,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
)

/** What the queue screen does to the queue. */
class QueueActions(
    val remove: (QueueEntry) -> Unit,
    /** Moves the entry from place `from` to place `to` of the order shown. */
    val move: (entry: QueueEntry, from: Int, to: Int) -> Unit,
)

/**
 * The queue screen (docs/playback.md §9.2): every entry in the order it plays, the current one marked and shown when the
 * screen opens. A tap plays an entry; a handle drags it, a swipe or its menu removes it. Each row's menu has the track
 * menu of [menu] before "Remove from queue" (§9.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    player: Player?,
    actions: QueueActions,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    menu: SongMenu? = null,
    /** What the selection's menu does; without it the entries cannot be selected (docs/library.md §20). */
    selectionActions: QueueSelectionActions? = null,
    /** Whether the saved queue's restore has ended, before which the selection is not judged (§20.2, 8). */
    restored: Boolean = true,
) {
    val queue = rememberQueue(player)
    val selectionModel = viewModel { QueueSelectionViewModel(createSavedStateHandle()) }
    val selected = selectionModel.holder.state.collectAsStateWithLifecycle().value.takeIf { selectionActions != null }
    LaunchedEffect(queue.entries, restored) { selectionModel.onQueue(queue.entries, restored) }
    // The order while dragging; the player's again once its timeline changes. One state for the screen's life, so that
    // the drag callbacks of an earlier composition read the same one.
    val shownState = remember { mutableStateOf(queue.entries) }
    var shown by shownState
    LaunchedEffect(queue.entries) { shownState.value = queue.entries }
    val latestEntries by rememberUpdatedState(queue.entries)
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    val list = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        shownState.value = shownState.value.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    var scrolled by remember(player) { mutableStateOf(false) }
    LaunchedEffect(queue.entries) {
        if (!scrolled && queue.entries.isNotEmpty()) {
            scrolled = true
            list.scrollToItem(queue.entries.indexOfFirst { it.row == queue.current }.coerceAtLeast(0))
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            if (selected != null && selectionActions != null) {
                QueueSelectionBar(selected, selectionModel, queue.entries, menu, selectionActions)
            } else TopAppBar(
                title = { Text(stringResource(R.string.queue_open), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag(QueueTags.LIST), state = list) {
            itemsIndexed(shown, key = { _, entry -> entry.key }) { place, entry ->
                ReorderableItem(reorder, key = entry.key) { dragging ->
                    QueueRow(
                        entry = entry,
                        playing = entry.row == queue.current,
                        dragging = dragging,
                        onPlay = {
                            player?.run {
                                seekToDefaultPosition(entry.row)
                                play()
                            }
                        },
                        onRemove = { actions.remove(entry) },
                        menu = menu,
                        row = selectionActions?.let {
                            RowSelection(selected != null, selected?.selection?.isSelected(entry.key) == true) {
                                selectionModel.holder.toggle(entry.key)
                            }
                        },
                        // Moves without dragging, for TalkBack and switch access.
                        onMoveUp = if (place > 0) ({ actions.move(entry, place, place - 1) }) else null,
                        onMoveDown = if (place < shown.lastIndex) ({ actions.move(entry, place, place + 1) }) else null,
                        handle = Modifier.draggableHandle(
                            onDragStarted = { dragFrom = latestEntries.indexOfFirst { it.key == entry.key } },
                            onDragStopped = {
                                val from = dragFrom
                                val to = shownState.value.indexOfFirst { it.key == entry.key }
                                dragFrom = null
                                if (from != null && from >= 0 && to >= 0 && from != to) actions.move(entry, from, to)
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** The queue of [player] in its play order and the current entry's row, kept up to date. */
private class ShownQueue(val entries: List<QueueEntry>, val current: Int)

@Composable
private fun rememberQueue(player: Player?): ShownQueue {
    var queue by remember(player) { mutableStateOf(player?.let(::queueOf) ?: ShownQueue(emptyList(), -1)) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    )
                ) {
                    queue = queueOf(player)
                }
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    return queue
}

/** The entries of [player] in the order they play: the shuffle order while shuffle is on. */
private fun queueOf(player: Player): ShownQueue {
    val timeline = player.currentTimeline
    val shuffle = player.shuffleModeEnabled
    val window = Timeline.Window()
    val seen = HashMap<String, Int>()
    val entries = ArrayList<QueueEntry>(timeline.windowCount)
    var index = timeline.getFirstWindowIndex(shuffle)
    while (index != C.INDEX_UNSET) {
        val item = timeline.getWindow(index, window).mediaItem
        val metadata = item.mediaMetadata
        // A file opened twice has one media ID twice; the lazy list needs keys of its own.
        val copy = seen.merge(item.mediaId, 1, Int::plus)!!
        entries += QueueEntry(
            row = index,
            mediaId = item.mediaId,
            key = if (copy == 1) item.mediaId else "${item.mediaId}#$copy",
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString(),
            durationMs = metadata.durationMs,
        )
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
    }
    return ShownQueue(entries, player.currentMediaItemIndex)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueRow(
    entry: QueueEntry,
    playing: Boolean,
    dragging: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    handle: Modifier,
    menu: SongMenu?,
    row: RowSelection? = null,
) {
    val selecting = row?.selecting == true
    // Not saved: an entry put back by "Undo" under the same key must not come back swiped away and be removed again.
    // Keyed by the entry, so that a refused removal or another entry under the key starts settled.
    val dismiss = remember(entry) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it / 2 }) }
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) onRemove()
    }
    val artist = entry.artist ?: stringResource(R.string.songs_unknown_artist)
    val playingText = stringResource(R.string.song_playing)
    val moveUp = stringResource(R.string.queue_move_up)
    val moveDown = stringResource(R.string.queue_move_down)
    SwipeToDismissBox(
        state = dismiss,
        backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)) },
        // Rows do not move or go while selected (docs/library.md §20.2, 3).
        gesturesEnabled = !selecting,
    ) {
        ListItem(
            headlineContent = {
                Text(
                    entry.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (playing) FontWeight.Bold else null,
                )
            },
            supportingContent = {
                Text("$artist · ${formatDuration(entry.durationMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = {
                if (selecting) {
                    SelectionCheck(row)
                } else {
                    // A handle for the finger only: TalkBack moves the row with its own actions, so the handle says nothing.
                    Icon(
                        painterResource(R.drawable.ic_drag_handle),
                        contentDescription = null,
                        modifier = handle.minimumInteractiveComponentSize().testTag(QueueTags.handle(entry.key)),
                    )
                }
            },
            trailingContent = if (selecting) null else ({ RowMenu(entry, onRemove, menu) }),
            tonalElevation = if (dragging) 4.dp else 0.dp,
            shadowElevation = if (dragging) 4.dp else 0.dp,
            modifier = Modifier
                .testTag(QueueTags.ROW)
                .selectableRow(
                    row,
                    stringResource(R.string.songs_play),
                    onPlay,
                    actions = listOfNotNull(
                        onMoveUp?.let { move -> CustomAccessibilityAction(moveUp) { move(); true } },
                        onMoveDown?.let { move -> CustomAccessibilityAction(moveDown) { move(); true } },
                    ),
                )
                .semantics { if (playing && !selecting) stateDescription = playingText },
        )
    }
}

/** A row's menu: the track menu of its song (or "Details" of an opened file), then "Remove from queue" (§9.1). */
@Composable
private fun RowMenu(entry: QueueEntry, onRemove: () -> Unit, menu: SongMenu?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.song_menu, entry.title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (menu != null) {
                // An added entry plays a library song too (§9.4); anything else is a file opened from another app.
                val song = QueueIds.fileId(entry.mediaId)?.let { id ->
                    Song(id = id, uri = entry.mediaId, title = entry.title, artist = entry.artist, album = null, durationMs = entry.durationMs)
                }
                TrackMenuItems(song, TrackMenuPlace.QUEUE, menu, close = { open = false }, openedUri = entry.mediaId.takeIf { song == null })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.queue_remove)) }, onClick = {
                open = false
                onRemove()
            })
        }
    }
}

/**
 * The queue screen's selection bar (docs/playback.md §9.6): its menu follows the row's track menu without "Play", then
 * "Remove from queue" of every selected entry at once, in the order the screen shows them.
 */
@Composable
private fun QueueSelectionBar(
    state: SelectionUi<String>,
    model: QueueSelectionViewModel,
    entries: List<QueueEntry>,
    menu: SongMenu?,
    actions: QueueSelectionActions,
) {
    SelectionBack(selecting = true, onEnd = model.holder::end)
    SelectionBar(state, onClose = model.holder::end, onSelectAll = model.holder::selectAll, onDeselectAll = model.holder::end) { close ->
        val chosen = remember(state, entries) { entries.filter { state.selection.isSelected(it.key) } }
        // An added entry plays a library song too (§9.4); anything else is a file opened from another app.
        val songIds = chosen.mapNotNull { QueueIds.fileId(it.mediaId) }
        var favorites by remember(state) { mutableStateOf<FavoriteBatch?>(null) }
        LaunchedEffect(state) { favorites = actions.favorites?.state?.invoke(songIds) }
        val offers = TrackMenuOffers(
            addToPlaylist = actions.addToPlaylist != null,
            favorites = actions.favorites != null,
            covers = false,
            details = menu?.details != null && menu.openedDetails != null,
        )
        val entriesOfMenu = TrackMenu.selectionEntries(SelectionPlace.QUEUE, offers, SelectionMenuState(chosen.size, songIds.size, favorites))
        SelectionMenuItems(entriesOfMenu, close) { entry ->
            val target = QueueTarget.Songs(songIds)
            when (entry) {
                TrackMenuEntry.PlayNext -> actions.addToQueue(target, true)
                TrackMenuEntry.AddToQueue -> actions.addToQueue(target, false)
                TrackMenuEntry.AddToPlaylist -> return@SelectionMenuItems actions.addToPlaylist?.invoke(target, model.holder::end) ?: Unit
                is TrackMenuEntry.Favorite -> actions.favorites?.set?.invoke(songIds, !entry.favorite)
                TrackMenuEntry.Details -> chosen.singleOrNull()?.let { one ->
                    val id = QueueIds.fileId(one.mediaId)
                    if (id != null) {
                        menu?.details?.invoke(Song(id, one.mediaId, one.title, one.artist, null, one.durationMs))
                    } else {
                        menu?.openedDetails?.invoke(one.mediaId)
                    }
                }
                TrackMenuEntry.RemoveFromQueue -> actions.removeAll(chosen)
                else -> Unit
            }
            model.holder.end()
        }
    }
}
