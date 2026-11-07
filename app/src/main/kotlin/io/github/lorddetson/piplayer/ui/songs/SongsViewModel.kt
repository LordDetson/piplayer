package io.github.lorddetson.piplayer.ui.songs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.tagpass.PassProgress
import io.github.lorddetson.piplayer.library.tagpass.TagPassState
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A song list (docs/playback.md §2.1, §2.2, docs/library.md §1.4, §6): the Songs tab, or the songs of an album, artist
 * or folder page as [base] names them, in the order chosen for [list] and with the search typed. It shows "Loading" until
 * the library is ready, then reads the list when the screen first starts with the permission, after every sync that
 * changed the library, and whenever the order or the search changes. The first time, its first page is shown ahead of
 * the whole list. A return from the background reads nothing unless the library changed or the tag pass has ended and
 * left the list to sort anew, once, on the same song (§5.4).
 */
@OptIn(FlowPreview::class)
class SongsViewModel(
    application: Application,
    private val base: SongQuery = SongQuery.SONGS,
    val list: ListName = ListName.SONGS,
) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<SongsState>(SongsState.Loading)
    val state: StateFlow<SongsState> = mutableState.asStateFlow()

    /** How far the tag pass has come. */
    val passProgress: StateFlow<PassProgress> = TagPassState.progress

    /** The order chosen for this kind of list. */
    val order: StateFlow<ListOrder> get() = mutableOrder
    private val mutableOrder = MutableStateFlow(ListOrder(list.defaultField))
    private val search = MutableStateFlow("")

    /** The song at the top of the list, for the sort after the tag pass. */
    var firstVisibleSong: Long? = null

    /** The list last shown; a new one is read fresh, with its first page first. */
    private var shown: SongQuery? = null

    /** A sort whose list has not reached the screen, for instance because the screen stopped during the read. */
    private var sortToShow = false

    /** The list has been brought to the song it asked for; a later composition must not do that again. */
    fun onScrolled() {
        mutableState.update { state -> if (state is SongsState.Loaded) state.copy(scrollTo = null) else state }
    }

    private var watching: Job? = null
    private var started = false
    private var granted = false

    init {
        viewModelScope.launch { ListSorts(application).order(list).collect { mutableOrder.value = it } }
    }

    /** The screen started: with the permission, the list is read and the library watched. */
    fun onStart(granted: Boolean, deniedForGood: Boolean) {
        started = true
        permission(granted, deniedForGood)
        if (granted) watch()
    }

    /** The screen stopped: the library is no longer watched. */
    fun onStop() {
        started = false
        watching?.cancel()
        watching = null
    }

    /**
     * The screen checked the permission again (it resumed, or the system dialog answered): [granted], or not and
     * [deniedForGood] once the system no longer shows its dialog.
     */
    fun onPermission(granted: Boolean, deniedForGood: Boolean) {
        permission(granted, deniedForGood)
        if (granted && started) watch()
    }

    /** The user chose another order; it is kept for every list of this kind. */
    fun setOrder(order: ListOrder) {
        mutableOrder.value = order
        viewModelScope.launch { ListSorts(getApplication()).set(list, order) }
    }

    /** The text of the search field; empty shows the whole list. */
    fun setSearch(text: String) {
        search.value = text
    }

    private fun permission(granted: Boolean, deniedForGood: Boolean) {
        val newlyGranted = granted && !this.granted
        this.granted = granted
        if (!granted) {
            watching?.cancel()
            watching = null
            mutableState.value = SongsState.NeedsPermission(deniedForGood)
        } else if (newlyGranted) {
            // Access granted while the process runs: the library starts watching the index and syncs.
            Library.start(getApplication())
            if (mutableState.value is SongsState.NeedsPermission) mutableState.value = SongsState.Loading
        }
    }

    private fun queryOf(order: ListOrder, search: String) =
        base.copy(sort = SongSort.of(order.field) ?: base.sort, reversed = order.reversed, search = search.trim())

    private fun watch() {
        if (watching?.isActive == true) return
        val app = getApplication<Application>()
        watching = viewModelScope.launch {
            launch {
                // A batch of the tag pass: the screen takes the shared list, so that a tap plays what it shows.
                SongList.batches.drop(1).collect {
                    val query = shown ?: return@collect
                    // Not over a first page, which would jump back to an older order.
                    if (mutableState.value is SongsState.Loaded) {
                        SongList.current(query)?.let { songs ->
                            mutableState.update { state ->
                                if (state is SongsState.Loaded && state.query == query) state.copy(songs = songs) else state
                            }
                        }
                    }
                }
            }
            // A search is read once typing pauses, not for every letter (docs/library.md §6.3).
            val typed = search.debounce { if (it.isEmpty()) 0L else SEARCH_PAUSE_MS }
            val queries = combine(mutableOrder, typed, ::queryOf).distinctUntilChanged()
            combine(SongList.changes(app), queries, ::Pair).collectLatest { (key, query) ->
                val newList = query != shown
                val resort = SongList.resortDue(query)
                val kept = if (!newList && !resort) SongList.keptFor(key, query) else null
                if (kept != null) {
                    mutableState.value = SongsState.Loaded(kept, query, scrollTo = firstVisibleSong.takeIf { sortToShow })
                    sortToShow = false
                    return@collectLatest
                }
                // Before a list is first shown, its first page comes ahead of the whole list: 20 000 songs take a few
                // hundred milliseconds to read, the first page a few.
                if (newList) {
                    val page = withContext(Dispatchers.IO) { SongList.firstPage(app, FIRST_PAGE, query) }
                    if (page.isNotEmpty() || mutableState.value !is SongsState.Loaded) {
                        mutableState.value = SongsState.Loaded(page, query)
                    }
                }
                if (resort) sortToShow = true
                val songs = withContext(Dispatchers.IO) { SongList.load(app, fresh = resort || newList, query = query) }
                shown = query
                SongList.hold(this@SongsViewModel, query)
                // The song at the top now: the user may have scrolled during the read.
                mutableState.value = SongsState.Loaded(songs, query, scrollTo = firstVisibleSong.takeIf { sortToShow })
                sortToShow = false
            }
        }
    }

    override fun onCleared() {
        watching?.cancel()
        SongList.hold(this, null)
    }

    private companion object {
        /** More than a tall screen shows. */
        const val FIRST_PAGE = 50

        /** Long enough for the next letter of someone typing. */
        const val SEARCH_PAUSE_MS = 250L
    }
}
