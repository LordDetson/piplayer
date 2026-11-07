package io.github.lorddetson.piplayer.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.AlbumGroup
import io.github.lorddetson.piplayer.library.lists.AlbumSort
import io.github.lorddetson.piplayer.library.lists.ArtistGroup
import io.github.lorddetson.piplayer.library.lists.ArtistSort
import io.github.lorddetson.piplayer.library.lists.FolderEntry
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.ListOrder
import io.github.lorddetson.piplayer.settings.ListSorts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a list of albums, artists or folders, or a page's header, shows; [value] null means not found. */
sealed interface GroupsState<out T> {
    data object Loading : GroupsState<Nothing>

    data class Loaded<T>(val value: T) : GroupsState<T>
}

/**
 * Reads a list of groups of the library (docs/library.md §6.5): when the screen starts, after every sync that changed
 * the library or a change of the hidden files, when the order or the search changes, and once more at the first start
 * after a pass of the tag pass that changed rows; never during a pass for its batches, so that nothing is grouped again
 * under the user's finger.
 */
@OptIn(FlowPreview::class)
abstract class GroupsViewModel<T>(application: Application, val list: ListName?) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<GroupsState<T>>(GroupsState.Loading)
    val state: StateFlow<GroupsState<T>> = mutableState.asStateFlow()

    private val mutableOrder = MutableStateFlow(ListOrder(list?.defaultField.orEmpty()))
    val order: StateFlow<ListOrder> = mutableOrder.asStateFlow()
    private val search = MutableStateFlow("")
    private val again = MutableStateFlow(0)
    private var readAfterPasses = -1L
    private var watching: Job? = null

    /** What the last read was made of; a start that changes none of it reads nothing. */
    private var lastRead: List<Any?>? = null

    init {
        list?.let { name -> viewModelScope.launch { ListSorts(application).order(name).collect { mutableOrder.value = it } } }
    }

    protected abstract fun read(lists: LibraryLists, order: ListOrder, search: String): T

    fun setOrder(order: ListOrder) {
        mutableOrder.value = order
        val name = list ?: return
        viewModelScope.launch { ListSorts(getApplication()).set(name, order) }
    }

    fun setSearch(text: String) {
        search.value = text.trim()
    }

    /** The screen started with [granted] access; without it nothing is read. */
    fun onStart(granted: Boolean) {
        if (!granted) return onStop()
        if (readAfterPasses >= 0 && readAfterPasses != SongList.changingPasses && !SongList.passRunning()) again.value++
        if (watching?.isActive == true) return
        val app = getApplication<Application>()
        watching = viewModelScope.launch {
            val typed = search.debounce { if (it.isEmpty()) 0L else SEARCH_PAUSE_MS }
            combine(SongList.changes(app), mutableOrder, typed, again) { key, order, search, again -> listOf(key, order, search, again) }
                .collectLatest { inputs ->
                    if (inputs == lastRead) return@collectLatest
                    val order = inputs[1] as ListOrder
                    val search = inputs[2] as String
                    val passes = SongList.changingPasses
                    val value = withContext(Dispatchers.IO) {
                        val hidden = LibrarySettings(app).readHiddenFiles()
                        Library.read(app) { database -> read(LibraryLists(database, hidden), order, search) }
                    }
                    readAfterPasses = passes
                    lastRead = inputs
                    mutableState.value = GroupsState.Loaded(value)
                }
        }
    }

    fun onStop() {
        watching?.cancel()
        watching = null
    }

    private companion object {
        /** Long enough for the next letter of someone typing. */
        const val SEARCH_PAUSE_MS = 250L
    }
}

class AlbumsViewModel(application: Application) : GroupsViewModel<List<AlbumGroup>>(application, ListName.ALBUMS) {
    override fun read(lists: LibraryLists, order: ListOrder, search: String) =
        lists.albums(AlbumSort.valueOf(order.field.uppercase()), order.reversed, search)
}

class ArtistsViewModel(application: Application) : GroupsViewModel<List<ArtistGroup>>(application, ListName.ARTISTS) {
    override fun read(lists: LibraryLists, order: ListOrder, search: String) =
        lists.artists(if (order.field == "songs") ArtistSort.SONGS else ArtistSort.NAME, order.reversed, search)
}

/** The header of an album page: the album, or null once it is gone. */
class AlbumPageViewModel(application: Application, private val key: String) : GroupsViewModel<AlbumGroup?>(application, null) {
    override fun read(lists: LibraryLists, order: ListOrder, search: String) = lists.album(key)
}

/** The artist of an artist page with their albums, or null once they are gone. */
data class ArtistPage(val artist: ArtistGroup, val albums: List<AlbumGroup>)

class ArtistPageViewModel(application: Application, private val key: String) : GroupsViewModel<ArtistPage?>(application, null) {
    override fun read(lists: LibraryLists, order: ListOrder, search: String): ArtistPage? {
        val artist = lists.artist(key) ?: return null
        val albums = lists.artistAlbums(key)
        if (search.isEmpty()) return ArtistPage(artist, albums)
        val found = lists.albums(search = search).mapTo(HashSet()) { it.key }
        return ArtistPage(artist, albums.filter { it.key in found })
    }
}

/**
 * A level of folders: the subfolders of [folder] (or the volumes at the top, when there are more than one), or with a
 * search every matching folder below it. [folder] is null at a top of volumes and for a folder that is gone ([exists]).
 */
data class FolderPage(val folder: FolderKey?, val exists: Boolean, val entries: List<FolderEntry>, val searching: Boolean)

/** The Folders tab when [folder] is null, otherwise a folder page (docs/library.md §6.1). */
class FolderPageViewModel(application: Application, private val folder: FolderKey?) :
    GroupsViewModel<FolderPage>(application, ListName.FOLDERS) {

    override fun read(lists: LibraryLists, order: ListOrder, search: String): FolderPage {
        val levels = lists.folders()
        val at = folder ?: levels.top().folder?.let { (volume, path) -> FolderKey(volume, path) }
        if (search.isNotEmpty()) {
            val found = levels.search(search).filter { entry ->
                at == null || (entry.volume == at.volume && entry.relativePath.startsWith(at.relativePath))
            }
            return FolderPage(at, exists = at == null || levels.contains(at.volume, at.relativePath), found, searching = true)
        }
        if (at == null) return FolderPage(null, exists = true, levels.top().entries.let { if (order.reversed) it.reversed() else it }, false)
        val exists = levels.contains(at.volume, at.relativePath) || (folder == null)
        return FolderPage(at, exists, levels.children(at.volume, at.relativePath, order.reversed), searching = false)
    }
}
