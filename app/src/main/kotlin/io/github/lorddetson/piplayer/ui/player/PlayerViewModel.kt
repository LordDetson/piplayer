package io.github.lorddetson.piplayer.ui.player

import android.app.Application
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.playback.PlaybackConnection
import io.github.lorddetson.piplayer.playback.PlaybackSessionCallback
import io.github.lorddetson.piplayer.playback.QueueRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The activity's one controller of the playback service (docs/playback.md §3.1), the current song's cover decoded for
 * the screens (§3.4), and the service's messages (§2.4).
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val mutablePlayer = MutableStateFlow<Player?>(null)

    /** The controller, null until it connects. */
    val player: StateFlow<Player?> = mutablePlayer.asStateFlow()

    private val mutableCover = MutableStateFlow<ImageBitmap?>(null)

    /** The current song's embedded cover, null while there is none or it is still being decoded. */
    val cover: StateFlow<ImageBitmap?> = mutableCover.asStateFlow()

    private val mutableMessages = Channel<String>(Channel.BUFFERED)

    /** Messages of the playback service, such as playback stopping on broken songs. */
    val messages: Flow<String> = mutableMessages.receiveAsFlow()

    /** Now Playing draws the cover as wide as the screen at most. */
    private val coverSizePx = application.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }

    private var coverBytes: ByteArray? = null
    private var decoding: Job? = null

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = showCover(mediaMetadata.artworkData)
    }

    private val connection = PlaybackConnection(application, object : MediaController.Listener {
        @OptIn(UnstableApi::class)
        override fun onError(controller: MediaController, sessionError: SessionError) {
            mutableMessages.trySend(sessionError.message)
        }
    })

    private var controller: MediaController? = null

    init {
        connection.whenConnected {
            controller = this
            addListener(listener)
            showCover(mediaMetadata.artworkData)
            mutablePlayer.value = this
            // Opening the screen shows the queue saved before the process died, paused (docs/playback.md §4.4).
            sendCustomCommand(PlaybackSessionCallback.RESTORE_COMMAND, Bundle.EMPTY)
        }
    }

    /** Plays the whole list from [song], shown at [row] (§2.3). */
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

    /**
     * Decodes the cover once per song: the controller lives in the service's process and shares the bytes of the
     * metadata, so the same picture comes as the same array. A new song shows no cover until its own is decoded.
     */
    private fun showCover(bytes: ByteArray?) {
        if (bytes === coverBytes) return
        coverBytes = bytes
        decoding?.cancel()
        mutableCover.value = null
        if (bytes == null) return
        decoding = viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { Covers.decode(bytes, coverSizePx) }
            mutableCover.value = bitmap?.asImageBitmap()
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        connection.release()
    }
}
