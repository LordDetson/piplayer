package io.github.lorddetson.piplayer.ui.search

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.ui.library.LibraryHost
import io.github.lorddetson.piplayer.ui.songs.SongsScreen
import io.github.lorddetson.piplayer.ui.songs.SongsViewModel
import io.github.lorddetson.piplayer.ui.songs.rememberAudioAccess

/** Test tags of the Search screen (docs/library.md §6.1). */
object SearchTags {
    const val FIELD = "search_field"

    /** The song list of the results. */
    const val LIST = "search_list"
}

/**
 * The Search place of the bottom bar (PIM-18, docs/library.md §6.1): the song search of §6.3 as a screen of its own,
 * bound to the same [SongsViewModel] the Songs tab searches through. The songs show in full until the user types, then
 * only the matching ones; a tap plays the list from that song, with the search shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(host: LibraryHost, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val access = rememberAudioAccess()
    var search by rememberSaveable { mutableStateOf("") }
    val songs = viewModel(key = "songs") { SongsViewModel(app, saved = createSavedStateHandle()) }
    LaunchedEffect(search) { songs.setSearch(search) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text(stringResource(R.string.library_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SearchTags.FIELD),
                    )
                },
            )
        },
        modifier = modifier,
        snackbarHost = { SnackbarHost(host.snackbar) },
    ) { padding ->
        SongsScreen(
            access = access,
            viewModel = songs,
            onSongClick = { row, song, list -> host.play(row, song, list) },
            modifier = Modifier.padding(padding).fillMaxSize(),
            currentSongId = host.currentSongId,
            menu = host.menu,
            listTag = SearchTags.LIST,
            emptyText = if (search.isBlank()) null else stringResource(R.string.search_nothing_found),
        )
    }
}
