package io.github.lorddetson.piplayer.ui.playlists

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.playlists.M3u
import io.github.lorddetson.piplayer.library.search.SearchTerms
import io.github.lorddetson.piplayer.library.search.SearchText
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.ListSorts
import io.github.lorddetson.piplayer.settings.PlaylistCatalogOrder
import io.github.lorddetson.piplayer.settings.PlaylistCatalogOrderSettings
import io.github.lorddetson.piplayer.settings.PlaylistSection
import io.github.lorddetson.piplayer.ui.library.CollectionTile
import io.github.lorddetson.piplayer.ui.library.LibraryHost
import io.github.lorddetson.piplayer.ui.library.rememberCollectionSelection
import io.github.lorddetson.piplayer.ui.player.QueueTarget
import io.github.lorddetson.piplayer.ui.selection.RowSelection
import io.github.lorddetson.piplayer.ui.selection.SelectionCheck
import io.github.lorddetson.piplayer.ui.selection.selectableRow
import io.github.lorddetson.piplayer.userdata.PlaylistSummary
import io.github.lorddetson.piplayer.userdata.UserData
import io.github.lorddetson.piplayer.userdata.byName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Test tags of the Playlists tab and the playlist page (docs/library.md §16.3). */
object PlaylistTags {
    const val NEW = "playlist_new"
    const val IMPORT = "playlist_import"
    const val LIST = "playlists_list"
    const val NAME_FIELD = "playlist_name_field"
    const val PAGE_LIST = "playlist_page_list"
    const val PLAY = "playlist_play"
    const val ROW = "playlist_entry_row"
}

/**
 * What the screens do with playlists (docs/library.md §16.3): the app opens the dialogs and the system file picker for
 * them and says how it went.
 */
class PlaylistActions(
    val open: (id: Long) -> Unit,
    /** Opens a smart list's page (docs/library.md §17.2). */
    val openSmart: (ListKind) -> Unit,
    val create: () -> Unit,
    val import: () -> Unit,
    val rename: (id: Long, name: String) -> Unit,
    val export: (id: Long, name: String) -> Unit,
    val delete: (id: Long, name: String) -> Unit,
    /** Opens the chooser for the songs of [QueueTarget]. */
    val addToPlaylist: (QueueTarget) -> Unit,
    /**
     * Opens the chooser for selected songs; [onChosen] once a playlist is chosen or made, not when the chooser is cancelled
     * (docs/library.md §20.3).
     */
    val addSelected: (target: QueueTarget, onChosen: () -> Unit) -> Unit = { target, _ -> addToPlaylist(target) },
)

/** A dialog of the playlists. */
private sealed interface PlaylistDialog {
    /** The name of a new playlist, which then gets [adding]'s songs, or the new name of [renaming]. */
    data class Name(
        val renaming: Long? = null,
        val initial: String = "",
        val adding: QueueTarget? = null,
        val onChosen: () -> Unit = {},
    ) : PlaylistDialog

    data class Delete(val id: Long, val name: String) : PlaylistDialog

    /** The chooser for [target]'s songs; [onChosen] once a playlist is chosen or made. */
    data class Choose(val target: QueueTarget, val onChosen: () -> Unit = {}) : PlaylistDialog
}

/**
 * The app's playlist actions with their dialogs and pickers; call it once, where the dialogs may show. A message on
 * [snackbar] says how each change went; [open] opens a playlist's page.
 */
@Composable
fun rememberPlaylistActions(snackbar: SnackbarHostState, open: (Long) -> Unit, openSmart: (ListKind) -> Unit = {}): PlaylistActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val choices = remember(context) { PlaylistChoices(context) }
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    // The playlist the export picker is choosing a document for; it outlives a recreated activity.
    var exporting by rememberSaveable { mutableStateOf<Long?>(null) }
    val say = { text: String -> scope.launch { snackbar.showSnackbar(text) }; Unit }

    /** Runs [change] outside the screen's scope, then says what [message] makes of its result while the screen is there. */
    fun <T> run(change: suspend () -> T, message: (T) -> String?) {
        val result = PlaylistChoices.scope.async { change() }
        scope.launch { message(result.await())?.let(say) }
    }

    fun add(playlistId: Long, target: QueueTarget) = run({ choices.add(playlistId, target) to choices.name(playlistId) }) { (count, name) ->
        when {
            count == null || name == null -> resources.getString(R.string.playlist_failed)
            count == 1 -> resources.getString(R.string.playlist_added, name)
            else -> resources.getQuantityString(R.plurals.playlist_added_count, count, count, name)
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            run({ choices.import(uri) }) { result ->
                if (result == null) {
                    resources.getString(R.string.playlist_import_failed)
                } else {
                    resources.getQuantityString(R.plurals.playlist_imported, result.total, result.name, result.found, result.total)
                }
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(M3u.MIME_TYPE)) { uri: Uri? ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) {
            run({ choices.export(id, uri) }) { count ->
                if (count == null) resources.getString(R.string.playlist_export_failed) else resources.getQuantityString(R.plurals.playlist_exported, count, count)
            }
        }
    }

    when (val shown = dialog) {
        is PlaylistDialog.Name -> NameDialog(
            initial = shown.initial,
            confirm = stringResource(if (shown.renaming == null) R.string.playlist_create else R.string.playlist_save),
            onDismiss = { dialog = null },
        ) { name ->
            dialog = null
            val renaming = shown.renaming
            if (renaming != null) {
                run({ choices.rename(renaming, name) }) { done -> if (done) null else resources.getString(R.string.playlist_failed) }
            } else {
                val adding = shown.adding
                if (adding != null) shown.onChosen()
                run({ choices.create(name) }) { id ->
                    when {
                        id == null -> resources.getString(R.string.playlist_failed)
                        adding != null -> {
                            add(id, adding)
                            null
                        }
                        else -> null
                    }
                }
            }
        }
        is PlaylistDialog.Delete -> DeleteDialog(shown.name, onDismiss = { dialog = null }) {
            dialog = null
            run({ choices.delete(shown.id) }) { done -> if (done) null else resources.getString(R.string.playlist_failed) }
        }
        is PlaylistDialog.Choose -> ChooseDialog(
            onDismiss = { dialog = null },
            onNew = { dialog = PlaylistDialog.Name(adding = shown.target, onChosen = shown.onChosen) },
        ) { id ->
            dialog = null
            shown.onChosen()
            add(id, shown.target)
        }
        null -> Unit
    }

    return remember(choices) {
        PlaylistActions(
            open = open,
            openSmart = openSmart,
            create = { dialog = PlaylistDialog.Name() },
            import = { importer.launch(M3u.OPEN_TYPES) },
            rename = { id, name -> dialog = PlaylistDialog.Name(renaming = id, initial = name) },
            export = { id, name ->
                exporting = id
                exporter.launch(fileName(name) + M3u.EXTENSION)
            },
            delete = { id, name -> dialog = PlaylistDialog.Delete(id, name) },
            addToPlaylist = { target -> dialog = PlaylistDialog.Choose(target) },
            addSelected = { target, onChosen -> dialog = PlaylistDialog.Choose(target, onChosen) },
        )
    }
}

/** [name] without the characters file systems refuse. */
private fun fileName(name: String) = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().ifEmpty { "playlist" }

/** A dialog with a name field; [onConfirm] gets the name, and its button stays off while the name is blank. */
@Composable
private fun NameDialog(initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.isEmpty()) R.string.playlist_new else R.string.playlist_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.playlist_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(PlaylistTags.NAME_FIELD),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/** The confirmation of "Delete" (docs/library.md §16.3): it names the playlist and says its songs stay. */
@Composable
private fun DeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete_title, name)) },
        text = { Text(stringResource(R.string.playlist_delete_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.playlist_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/** The chooser of "Add to playlist": "New playlist" first, then the playlists by name. */
@Composable
private fun ChooseDialog(onDismiss: () -> Unit, onNew: () -> Unit, onChoose: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model = viewModel(key = "playlist chooser") { PlaylistsViewModel(app) }
    val playlists by model.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item(key = "new") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.playlist_new)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                        modifier = Modifier.clickable(role = Role.Button, onClick = onNew),
                    )
                }
                items(playlists.orEmpty(), key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(pluralStringResource(R.plurals.songs_count, playlist.entries, playlist.entries)) },
                        modifier = Modifier.clickable(role = Role.Button) { onChoose(playlist.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/**
 * The playlists by name (the locale's collator); null until the first read (docs/library.md §16.3).
 */
class PlaylistsViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<List<PlaylistSummary>?> =
        UserData.playlists(application).playlistsFlow().map { it.byName() }
            .flowOn(Dispatchers.Default)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The Playlists tab shows manual and smart playlists in independently reorderable sections. */
@Composable
fun PlaylistsContent(host: LibraryHost, actions: PlaylistActions, search: String, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val model = viewModel(key = "playlists") { PlaylistsViewModel(app) }
    val playlists by model.state.collectAsStateWithLifecycle()
    val smart = viewModel(key = "smart lists") { SmartListsViewModel(app) }
    val counts by smart.counts.collectAsStateWithLifecycle()
    val sorts = remember(app) { ListSorts(app) }
    val gridFlow = remember(sorts) { sorts.grid(ListName.PLAYLISTS) }
    val grid by gridFlow.collectAsStateWithLifecycle(initialValue = false)
    val settings = remember(app) { PlaylistCatalogOrderSettings(app) }
    val order by settings.order.collectAsStateWithLifecycle(initialValue = PlaylistCatalogOrder())
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.playlist_failed)
    fun saveOrder(write: suspend () -> Boolean) = scope.launch {
        if (!write()) host.snackbar.showSnackbar(saveFailed)
    }
    val manuals = remember(playlists) { playlists.orEmpty().associateBy(PlaylistSummary::id) }
    val manualKeys = remember(manuals) { manuals.keys.toList() }
    val manualOrder = remember(order, manuals, search) {
        val terms = SearchTerms.of(search)
        order.manual(manualKeys).filter { id ->
            terms.isEmpty() || SearchText.of(manuals.getValue(id).name).let { name -> terms.all { it in name } }
        }
    }
    val smartRows = smartListRows(counts, search).associateBy(SmartListRow::kind)
    val smartOrder = order.smart(smartRows.keys.toList())
    val targets: List<QueueTarget.Collection> = remember(order, manualOrder, smartOrder) {
        order.sections.flatMap { section ->
            when (section) {
                PlaylistSection.MANUAL -> manualOrder.map { QueueTarget.Playlist(it) }
                PlaylistSection.SMART -> smartOrder.map { QueueTarget.Smart(it) }
            }
        }
    }
    val selection = rememberCollectionSelection(targets, search)
    Column(modifier) {
        selection.Actions(host)
        if (!selection.selecting) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = actions.create, modifier = Modifier.testTag(PlaylistTags.NEW)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Text(stringResource(R.string.playlist_new), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = actions.import, modifier = Modifier.testTag(PlaylistTags.IMPORT)) {
                Text(stringResource(R.string.playlist_import))
            }
        }
        LazyVerticalGrid(
            columns = if (grid) GridCells.Adaptive(152.dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize().testTag(PlaylistTags.LIST),
        ) {
            for (section in order.sections) {
                val manual = section == PlaylistSection.MANUAL
                val visible = if (manual) search.isBlank() || manualOrder.isNotEmpty() else smartRows.isNotEmpty()
                if (visible) item(key = section.name, span = { GridItemSpan(maxLineSpan) }) {
                    val isFirst = section == order.sections.first()
                    PlaylistSectionHeader(
                        if (manual) R.string.tab_playlists else R.string.smart_lists,
                        isFirst,
                        canMove = search.isBlank() && !selection.selecting,
                        move = { saveOrder { settings.moveSectionToTop(if (isFirst) order.sections.last() else section) } },
                    )
                }
                if (manual) {
                    when {
                        playlists == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                        manualOrder.isEmpty() && (search.isBlank() || smartRows.isEmpty()) ->
                            item(key = "none", span = { GridItemSpan(maxLineSpan) }) { PlaylistCatalogEmpty(search) }
                        else -> items(manualOrder.size, key = { manualOrder[it] }) { place ->
                            val id = manualOrder[place]
                            val move: (Int) -> Unit = { by -> saveOrder { settings.moveManual(manualKeys, id, by) } }
                            val moves = movesInSection(place, manualOrder.lastIndex, search.isBlank(), move)
                            PlaylistRow(manuals.getValue(id), host, actions, grid, selection.row(QueueTarget.Playlist(id)), moves)
                        }
                    }
                } else {
                    items(smartOrder.size, key = { "smart " + smartOrder[it].token }) { place ->
                        val kind = smartOrder[place]
                        val move: (Int) -> Unit = { by -> saveOrder { settings.moveSmart(kind, by) } }
                        val moves = movesInSection(place, smartOrder.lastIndex, search.isBlank(), move)
                        SmartCatalogRow(smartRows.getValue(kind), actions, grid, selection.row(QueueTarget.Smart(kind)), moves)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSectionHeader(name: Int, first: Boolean, canMove: Boolean, move: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(name), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (canMove) CatalogMoveMenu(stringResource(name), CatalogMoves(up = move.takeUnless { first }, down = move.takeIf { first }))
    }
}

@Composable
private fun PlaylistCatalogEmpty(search: String) {
    Text(stringResource(if (search.isBlank()) R.string.playlists_none else R.string.search_nothing_found),
        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
}

private class CatalogMoves(val up: (() -> Unit)? = null, val down: (() -> Unit)? = null)

private fun movesInSection(place: Int, last: Int, enabled: Boolean, move: (Int) -> Unit) = CatalogMoves(
    up = if (enabled && place > 0) ({ move(-1) }) else null,
    down = if (enabled && place < last) ({ move(1) }) else null,
)

@Composable
private fun CatalogMoveMenu(name: String, moves: CatalogMoves) {
    if (moves.up == null && moves.down == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.song_menu, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CatalogMoveItems(moves) { open = false }
        }
    }
}

@Composable
private fun CatalogMoveItems(moves: CatalogMoves, close: () -> Unit) {
    for ((label, action) in listOf(R.string.queue_move_up to moves.up, R.string.queue_move_down to moves.down)) {
        action?.let {
            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { close(); it() })
        }
    }
}

@Composable
private fun PlaylistRow(playlist: PlaylistSummary, host: LibraryHost, actions: PlaylistActions, grid: Boolean, row: RowSelection, moves: CatalogMoves) {
    val details = pluralStringResource(R.plurals.songs_count, playlist.entries, playlist.entries)
    CatalogRow(playlist.name, details, R.drawable.ic_playlist, R.string.open_playlist, grid, row, { actions.open(playlist.id) }) {
        PlaylistMenuButton(playlist.id, playlist.name, host, actions, withPlay = true, onMoveUp = moves.up, onMoveDown = moves.down)
    }
}

@Composable
private fun SmartCatalogRow(smart: SmartListRow, actions: PlaylistActions, grid: Boolean, row: RowSelection, moves: CatalogMoves) {
    val details = smart.count?.let { pluralStringResource(R.plurals.songs_count, it, it) }
    CatalogRow(smart.name, details, smartListIcon(smart.kind), R.string.open_smart_list, grid, row,
        { actions.openSmart(smart.kind) }, Modifier.testTag(SmartListTags.ROW)) {
        CatalogMoveMenu(smart.name, moves)
    }
}

@Composable
private fun CatalogRow(
    name: String,
    details: String?,
    icon: Int,
    openLabel: Int,
    grid: Boolean,
    row: RowSelection,
    open: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit,
) {
    if (grid) {
        CollectionTile(name, details, icon, stringResource(openLabel), open, modifier, row, menu)
    } else {
        ListItem(
            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = details?.let { { Text(it) } },
            leadingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionCheck(row)
                Icon(painterResource(icon), contentDescription = null)
            } },
            trailingContent = menu.takeUnless { row.selecting },
            modifier = modifier.selectableRow(row, stringResource(openLabel), open),
        )
    }
}

/**
 * The menu of a playlist row ([withPlay]) or of its page's top bar: "Play", "Play next", "Add to queue", "Rename",
 * "Export" and "Delete".
 */
@Composable
internal fun PlaylistMenuButton(
    id: Long,
    name: String,
    host: LibraryHost,
    actions: PlaylistActions,
    withPlay: Boolean,
    description: String? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more), description ?: stringResource(R.string.song_menu, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item = @Composable { label: Int, action: () -> Unit ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    open = false
                    action()
                })
            }
            if (withPlay) item(R.string.playlist_play) { host.play(0, null, SongQuery.playlist(id)) }
            item(R.string.play_next) { host.addToQueue(QueueTarget.Playlist(id), true) }
            item(R.string.add_to_queue) { host.addToQueue(QueueTarget.Playlist(id), false) }
            CatalogMoveItems(CatalogMoves(onMoveUp, onMoveDown)) { open = false }
            item(R.string.playlist_rename) { actions.rename(id, name) }
            item(R.string.playlist_export) { actions.export(id, name) }
            item(R.string.playlist_delete) { actions.delete(id, name) }
        }
    }
}
