package io.github.lorddetson.piplayer.ui.songs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.tagpass.PassProgress
import io.github.lorddetson.piplayer.library.tagpass.TagPassState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The song list (docs/playback.md §2.1, §2.2, docs/library.md §1.4): shows "Loading" until the library is ready, then
 * reads the list when the screen first starts with the permission, and after every sync that changed the library. The
 * first time, its first page is shown ahead of the whole list. A return from the background reads nothing unless the
 * library changed or the tag pass has ended and left the list to sort anew, once, on the same song (§5.4).
 */
class SongsViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<SongsState>(SongsState.Loading)
    val state: StateFlow<SongsState> = mutableState.asStateFlow()

    /** How far the tag pass has come. */
    val passProgress: StateFlow<PassProgress> = TagPassState.progress

    /** The song at the top of the list, for the sort after the tag pass. */
    var firstVisibleSong: Long? = null

    /** A new screen sorts its list anew. */
    private var firstLoadDone = false

    private var watching: Job? = null
    private var started = false
    private var granted = false

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

    private fun watch() {
        if (watching?.isActive == true) return
        val app = getApplication<Application>()
        watching = viewModelScope.launch {
            launch {
                // A batch of the tag pass: the screen takes the shared list, so that a tap plays what it shows.
                SongList.batches.drop(1).collect {
                    if (mutableState.value is SongsState.Loaded) SongList.current()?.let { mutableState.value = SongsState.Loaded(it) }
                }
            }
            SongList.changes(app).collectLatest { key ->
                val resort = SongList.resortDue()
                val kept = if (firstLoadDone && !resort) SongList.keptFor(key) else null
                if (kept != null) {
                    mutableState.value = SongsState.Loaded(kept)
                    return@collectLatest
                }
                // Before the list is first shown, its first page comes ahead of the whole list: 20 000 songs take a
                // few hundred milliseconds to read, the first page a few.
                if (mutableState.value !is SongsState.Loaded) {
                    val page = withContext(Dispatchers.IO) { SongList.firstPage(app, FIRST_PAGE) }
                    if (page.isNotEmpty()) mutableState.value = SongsState.Loaded(page)
                }
                val anchor = firstVisibleSong.takeIf { resort }
                val songs = withContext(Dispatchers.IO) { SongList.load(app, fresh = resort || !firstLoadDone) }
                firstLoadDone = true
                mutableState.value = SongsState.Loaded(songs, scrollTo = anchor)
            }
        }
    }

    override fun onCleared() {
        watching?.cancel()
    }

    private companion object {
        /** More than a tall screen shows. */
        const val FIRST_PAGE = 50
    }
}
