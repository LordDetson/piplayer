package io.github.lorddetson.piplayer.ui.songs

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.PlaybackConnection
import io.github.lorddetson.piplayer.playback.QueueRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The temporary song list (docs/playback.md §2.1 to §2.3): reads the media index whenever the screen starts with the
 * permission, or gets it, and a second after the index changes while the screen is started; turns a tap into a compact
 * queue request.
 */
class SongsViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<SongsState>(SongsState.Loading)
    val state: StateFlow<SongsState> = mutableState.asStateFlow()

    private val mutableMessages = Channel<String>(Channel.BUFFERED)

    /** Messages of the playback service, such as playback stopping on broken songs. */
    val messages: Flow<String> = mutableMessages.receiveAsFlow()

    private val connection = PlaybackConnection(application, object : MediaController.Listener {
        @OptIn(UnstableApi::class)
        override fun onError(controller: MediaController, sessionError: SessionError) {
            mutableMessages.trySend(sessionError.message)
        }
    })

    private var loading: Job? = null
    private var started = false
    private var granted = false
    private var observing = false

    /** Reloads a second after the media index reports a change, once for a burst of changes. */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            load(delayMs = CHANGE_DELAY_MS)
        }
    }

    /** The screen started: with the permission, the list is read again and the media index watched. */
    fun onStart(granted: Boolean, deniedForGood: Boolean) {
        started = true
        this.granted = granted
        if (granted) {
            observe(true)
            load()
        } else {
            mutableState.value = SongsState.NeedsPermission(deniedForGood)
        }
    }

    /** The screen stopped: the media index is no longer watched. */
    fun onStop() {
        started = false
        observe(false)
    }

    /**
     * The screen checked the permission again (it resumed, or the system dialog answered): [granted], or not and
     * [deniedForGood] once the system no longer shows its dialog. Reads the list only when access was just granted.
     */
    fun onPermission(granted: Boolean, deniedForGood: Boolean) {
        val newlyGranted = granted && !this.granted
        this.granted = granted
        if (!granted) {
            mutableState.value = SongsState.NeedsPermission(deniedForGood)
        } else if (newlyGranted && started) {
            observe(true)
            load()
        }
    }

    private fun observe(on: Boolean) {
        if (on == observing) return
        val resolver = getApplication<Application>().contentResolver
        if (on) {
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        } else {
            resolver.unregisterContentObserver(observer)
            loading?.cancel()
        }
        observing = on
    }

    /** Plays the whole list from [song], shown at [row]. */
    fun play(row: Int, song: Song) {
        val item = MediaItem.Builder()
            .setMediaId(QueueRequest(song.id, row).mediaId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build())
            .build()
        connection.whenConnected {
            setMediaItem(item)
            prepare()
            play()
        }
    }

    private fun load(delayMs: Long = 0) {
        loading?.cancel()
        loading = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val songs = withContext(Dispatchers.IO) { SongList.load(getApplication<Application>()) }
            mutableState.value = SongsState.Loaded(songs)
        }
    }

    override fun onCleared() {
        observe(false)
        connection.release()
    }

    private companion object {
        const val CHANGE_DELAY_MS = 1_000L
    }
}
