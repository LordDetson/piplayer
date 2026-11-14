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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.playlists.M3u
import io.github.lorddetson.piplayer.library.search.SearchTerms
import io.github.lorddetson.piplayer.library.search.SearchText
import io.github.lorddetson.piplayer.ui.library.LibraryHost
import io.github.lorddetson.piplayer.ui.player.QueueTarget
import io.github.lorddetson.piplayer.userdata.PlaylistSummary
import io.github.lorddetson.piplayer.userdata.UserData
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
    val create: () -> Unit,
    val import: () -> Unit,
    val rename: (id: Long, name: String) -> Unit,
    val export: (id: Long, name: String) -> Unit,
    val delete: (id: Long, name: String) -> Unit,
    /** Opens the chooser for the songs of [QueueTarget]. */
    val addToPlaylist: (QueueTarget) -> Unit,
)

/** A dialog of the playlists. */
private sealed interface PlaylistDialog {
    /** The name of a new playlist, which then gets [adding]'s songs, or the new name of [renaming]. */
    data class Name(val renaming: Long? = null, val initial: String = "", val adding: QueueTarget? = null) : PlaylistDialog

    data class Delete(val id: Long, val name: String) : PlaylistDialog

    data class Choose(val target: QueueTarget) : PlaylistDialog
}

/**
 * The app's playlist actions with their dialogs and pickers; call it once, where the dialogs may show. A message on
 * [snackbar] says how each change went; [open] opens a playlist's page.
 */
@Composable
fun rememberPlaylistActions(snackbar: SnackbarHostState, open: (Long) -> Unit): PlaylistActions {
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
            onNew = { dialog = PlaylistDialog.Name(adding = shown.target) },
        ) { id ->
            dialog = null
            add(id, shown.target)
        }
        null -> Unit
    }

    return remember(choices) {
        PlaylistActions(
            open = open,
            create = { dialog = PlaylistDialog.Name() },
            import = { importer.launch(M3u.OPEN_TYPES) },
            rename = { id, name -> dialog = PlaylistDialog.Name(renaming = id, initial = name) },
            export = { id, name ->
                exporting = id
                exporter.launch(fileName(name) + M3u.EXTENSION)
            },
            delete = { id, name -> dialog = PlaylistDialog.Delete(id, name) },
            addToPlaylist = { target -> dialog = PlaylistDialog.Choose(target) },
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
 * The playlists by name (the locale's collator), filtered by a search over their names; null until the first read
 * (docs/library.md §16.3).
 */
class PlaylistsViewModel(application: Application) : AndroidViewModel(application) {

    private val search = MutableStateFlow("")

    val state: StateFlow<List<PlaylistSummary>?> =
        combine(UserData.playlists(application).playlistsFlow(), search) { playlists, query -> shown(playlists, query) }
            .flowOn(Dispatchers.Default)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setSearch(query: String) {
        search.value = query
    }

    private fun shown(playlists: List<PlaylistSummary>, query: String): List<PlaylistSummary> {
        val terms = SearchTerms.of(query)
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return playlists
            .filter { playlist -> terms.isEmpty() || SearchText.of(playlist.name).let { text -> terms.all { it in text } } }
            .sortedWith(compareBy<PlaylistSummary, String>(collator) { it.name }.thenBy { it.id })
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * The Playlists tab (docs/library.md §16.3): "New playlist" and "Import", then the playlists by name. One composable,
 * so that the browse categories of PIM-64 can show it elsewhere.
 */
@Composable
fun PlaylistsContent(host: LibraryHost, actions: PlaylistActions, search: String, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val model = viewModel(key = "playlists") { PlaylistsViewModel(app) }
    LaunchedEffect(search) { model.setSearch(search) }
    val playlists by model.state.collectAsStateWithLifecycle()
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = actions.create, modifier = Modifier.testTag(PlaylistTags.NEW)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Text(stringResource(R.string.playlist_new), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = actions.import, modifier = Modifier.testTag(PlaylistTags.IMPORT)) {
                Text(stringResource(R.string.playlist_import))
            }
        }
        val list = playlists
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(
                stringResource(if (search.isBlank()) R.string.playlists_none else R.string.search_nothing_found),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize().testTag(PlaylistTags.LIST)) {
                items(list, key = { it.id }) { playlist -> PlaylistRow(playlist, host, actions) }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: PlaylistSummary, host: LibraryHost, actions: PlaylistActions) {
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(pluralStringResource(R.plurals.songs_count, playlist.entries, playlist.entries)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_playlist), contentDescription = null) },
        trailingContent = { PlaylistMenuButton(playlist.id, playlist.name, host, actions, withPlay = true) },
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.open_playlist), role = Role.Button) { actions.open(playlist.id) },
    )
}

/**
 * The menu of a playlist row ([withPlay]) or of its page's top bar: "Play", "Play next", "Add to queue", "Rename",
 * "Export" and "Delete".
 */
@Composable
internal fun PlaylistMenuButton(id: Long, name: String, host: LibraryHost, actions: PlaylistActions, withPlay: Boolean, description: String? = null) {
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
            item(R.string.playlist_rename) { actions.rename(id, name) }
            item(R.string.playlist_export) { actions.export(id, name) }
            item(R.string.playlist_delete) { actions.delete(id, name) }
        }
    }
}
