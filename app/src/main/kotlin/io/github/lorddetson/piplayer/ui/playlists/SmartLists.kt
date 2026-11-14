package io.github.lorddetson.piplayer.ui.playlists

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.MissingReasons
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.search.SearchTerms
import io.github.lorddetson.piplayer.library.search.SearchText
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.ui.library.LibraryHost
import io.github.lorddetson.piplayer.ui.library.PageScaffold
import io.github.lorddetson.piplayer.ui.library.SortButton
import io.github.lorddetson.piplayer.ui.songs.PermissionCard
import io.github.lorddetson.piplayer.ui.songs.SongsScreen
import io.github.lorddetson.piplayer.ui.songs.SongsState
import io.github.lorddetson.piplayer.ui.songs.SongsViewModel
import io.github.lorddetson.piplayer.ui.songs.rememberAudioAccess
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Test tags of the smart lists on the Playlists tab and their pages (docs/library.md §17.2). */
object SmartListTags {
    /** A smart list's row on the Playlists tab. */
    const val ROW = "smart_list_row"
    const val PAGE_LIST = "smart_list_page_list"
    const val PLAY = "smart_list_play"
    const val SHUFFLE = "smart_list_shuffle"

    /** A reason's filter chip of Songs missing information. */
    const val CHIP = "smart_list_reason_chip"
}

/**
 * The reasons chosen on the page of Songs missing information (docs/library.md §17.1): one choice for the process, which
 * the page and the number on the Playlists tab share; it lasts while the app runs and is not saved.
 */
object MissingReasonChoice {
    val chosen = MutableStateFlow(MissingReasons.DEFAULT)

    /** Turns [reason] on or off. */
    fun toggle(reason: Int) {
        chosen.value = chosen.value xor reason
    }
}

/** The name of the smart list [kind]. */
internal fun smartListName(kind: ListKind): Int = when (kind) {
    ListKind.FAVORITES -> R.string.smart_favorites
    ListKind.RECENTLY_ADDED -> R.string.smart_recently_added
    ListKind.RECENTLY_PLAYED -> R.string.smart_recently_played
    ListKind.MOST_PLAYED -> R.string.smart_most_played
    else -> R.string.smart_missing_info
}

/** The kind of list whose order [kind] keeps (docs/library.md §17.1). */
internal fun smartListOrder(kind: ListKind): ListName = when (kind) {
    ListKind.FAVORITES -> ListName.FAVORITES
    ListKind.RECENTLY_ADDED -> ListName.RECENTLY_ADDED
    ListKind.RECENTLY_PLAYED -> ListName.RECENTLY_PLAYED
    ListKind.MOST_PLAYED -> ListName.MOST_PLAYED
    else -> ListName.MISSING_INFO
}

private fun smartListIcon(kind: ListKind): Int = when (kind) {
    ListKind.FAVORITES -> R.drawable.ic_favorite
    ListKind.RECENTLY_ADDED -> R.drawable.ic_add
    ListKind.RECENTLY_PLAYED -> R.drawable.ic_queue
    ListKind.MOST_PLAYED -> R.drawable.ic_player_repeat
    else -> R.drawable.ic_search
}

/** The label of a reason of missing information (TI §1). */
internal fun reasonLabel(reason: Int): Int = when (reason) {
    MissingReasons.TITLE -> R.string.reason_title
    MissingReasons.ARTIST -> R.string.reason_artist
    MissingReasons.ALBUM -> R.string.reason_album
    MissingReasons.COVER -> R.string.reason_cover
    else -> R.string.reason_recording_id
}

/**
 * The number of songs of each smart list (docs/library.md §17.2), read off the main thread again after every sync that
 * changed the library, a change of the hidden files, every run of the mirror and every new choice of reasons; null until
 * the first read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartListsViewModel(application: Application) : AndroidViewModel(application) {

    val counts: StateFlow<Map<ListKind, Int>?> =
        smartListCounts(SongList.changes(application, SongQuery.favorites()), MissingReasonChoice.chosen) { chosen ->
            withContext(Dispatchers.IO) {
                ListKind.SMART.associateWith { kind -> SongList.smartCount(application, SongQuery.smart(kind, chosen)) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * The numbers [read] gives for the chosen reasons, read again on every value of [changes] and [chosen]. A read that fails
 * shows no numbers until the next one: the flow goes on (docs/library.md §17.4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun smartListCounts(changes: Flow<Any>, chosen: Flow<Int>, read: suspend (Int) -> Map<ListKind, Int>): Flow<Map<ListKind, Int>> =
    combine(changes, chosen) { _, reasons -> reasons }
        .mapLatest { reasons ->
            try {
                read(reasons)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
        }

/** What the header of a smart list's page shows of its list (docs/library.md §17.2). */
internal object SmartListHeader {
    /** The number of songs, once the whole list is read: the first page that shows ahead of it is not the number. */
    fun count(loaded: SongsState.Loaded?): Int? = loaded?.takeIf { it.complete }?.songs?.size

    /** "Play" starts at the first song, which the first page already holds. */
    fun mayPlay(loaded: SongsState.Loaded?): Boolean = !loaded?.songs.isNullOrEmpty()

    /** "Shuffle" picks its song from the whole list, so it waits for it. */
    fun mayShuffle(loaded: SongsState.Loaded?): Boolean = loaded != null && loaded.complete && loaded.songs.isNotEmpty()
}

/**
 * The number of songs once the whole list is read, then "Play" and "Shuffle" (docs/library.md §17.2): a smart list's page
 * and a genre page share them (§19.3).
 */
@Composable
internal fun PlayAndShuffle(loaded: SongsState.Loaded?, host: LibraryHost, playTag: String, shuffleTag: String) {
    val count = SmartListHeader.count(loaded)
    if (count != null) Text(pluralStringResource(R.plurals.songs_count, count, count), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { loaded?.let { host.play(0, it.songs.first(), it.query) } },
            enabled = SmartListHeader.mayPlay(loaded),
            modifier = Modifier.testTag(playTag),
        ) {
            Icon(painterResource(R.drawable.ic_player_play), contentDescription = null)
            Text(stringResource(R.string.playlist_play), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = {
                loaded?.let {
                    val row = Random.nextInt(it.songs.size)
                    host.shuffle(row, it.songs[row], it.query)
                }
            },
            enabled = SmartListHeader.mayShuffle(loaded),
            modifier = Modifier.testTag(shuffleTag),
        ) {
            Icon(painterResource(R.drawable.ic_player_shuffle), contentDescription = null)
            Text(stringResource(R.string.smart_shuffle), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A smart list as the Playlists tab shows it: its kind, its name and its number of songs, when known. */
class SmartListRow(val kind: ListKind, val name: String, val count: Int?)

/** The smart lists in their order, those whose names hold every word of [search] (docs/library.md §17.2). */
@Composable
fun smartListRows(counts: Map<ListKind, Int>?, search: String): List<SmartListRow> {
    val terms = SearchTerms.of(search)
    return ListKind.SMART.map { kind -> SmartListRow(kind, stringResource(smartListName(kind)), counts?.get(kind)) }
        .filter { row -> terms.isEmpty() || SearchText.of(row.name).let { text -> terms.all { it in text } } }
}

/**
 * The rows of the smart lists, each opening its page with [open]: the Playlists tab shows them above the user's
 * playlists, and PIM-64 can show them where it puts the playlists (docs/library.md §17.2).
 */
fun LazyListScope.smartListItems(rows: List<SmartListRow>, open: (ListKind) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "smart lists heading") {
        Text(
            stringResource(R.string.smart_lists),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
        )
    }
    items(rows, key = { "smart " + it.kind.token }) { row ->
        ListItem(
            headlineContent = { Text(row.name) },
            supportingContent = row.count?.let { count -> { Text(pluralStringResource(R.plurals.songs_count, count, count)) } },
            leadingContent = { Icon(painterResource(smartListIcon(row.kind)), contentDescription = null) },
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.open_smart_list), role = Role.Button) { open(row.kind) }
                .testTag(SmartListTags.ROW),
        )
    }
}

/**
 * The page of the smart list [kind] (docs/library.md §17.2): the top bar with back, search and sort, the number of songs,
 * "Play" and "Shuffle", for Songs missing information the chips of its reasons, and the songs, each playing the list from
 * itself.
 */
@Composable
fun SmartListPage(kind: ListKind, host: LibraryHost, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val access = rememberAudioAccess()
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val chosen by MissingReasonChoice.chosen.collectAsStateWithLifecycle()
    val order = smartListOrder(kind)
    val songs = viewModel(key = "smart ${kind.token}") { SongsViewModel(app, SongQuery.smart(kind, MissingReasonChoice.chosen.value), order) }
    LaunchedEffect(search) { songs.setSearch(search) }
    if (kind == ListKind.MISSING_INFO) LaunchedEffect(chosen) { songs.setKey(chosen.toString()) }
    val state by songs.state.collectAsStateWithLifecycle()
    val loaded = state as? SongsState.Loaded
    PageScaffold(
        title = stringResource(smartListName(kind)),
        host = host,
        onBack = onBack,
        searching = searching,
        search = search,
        onSearch = { search = it },
        onSearching = { open -> searching = open; if (!open) search = "" },
        sort = { SortButton(order, songs.order.collectAsStateWithLifecycle().value, songs::setOrder) },
    ) { modifier ->
        val empty = when {
            search.isNotBlank() -> R.string.search_nothing_found
            kind == ListKind.FAVORITES -> R.string.smart_favorites_none
            kind == ListKind.RECENTLY_ADDED -> R.string.smart_added_none
            kind == ListKind.MISSING_INFO && chosen == 0 -> R.string.smart_missing_choose
            kind == ListKind.MISSING_INFO -> R.string.smart_missing_none
            else -> R.string.smart_played_none
        }
        if (!access.granted) {
            PermissionCard(access.deniedForGood, access.request, access.openSettings, modifier)
        } else {
            SongsScreen(
                access = access,
                viewModel = songs,
                onSongClick = { row, song, list -> host.play(row, song, list) },
                modifier = modifier,
                currentSongId = host.currentSongId,
                menu = host.menu,
                listTag = SmartListTags.PAGE_LIST,
                emptyText = stringResource(empty),
                headerItems = 1,
            ) {
                item(key = "smart list header") { Header(kind, loaded, chosen, host) }
            }
        }
    }
}

/** The number of songs, "Play" and "Shuffle", and for Songs missing information the chips of its reasons. */
@Composable
private fun Header(kind: ListKind, loaded: SongsState.Loaded?, chosen: Int, host: LibraryHost) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayAndShuffle(loaded, host, SmartListTags.PLAY, SmartListTags.SHUFFLE)
        if (kind == ListKind.MISSING_INFO) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (reason in MissingReasons.ORDER) {
                    FilterChip(
                        selected = chosen and reason != 0,
                        onClick = { MissingReasonChoice.toggle(reason) },
                        label = { Text(stringResource(reasonLabel(reason))) },
                        modifier = Modifier.testTag(SmartListTags.CHIP),
                    )
                }
            }
        }
    }
}
