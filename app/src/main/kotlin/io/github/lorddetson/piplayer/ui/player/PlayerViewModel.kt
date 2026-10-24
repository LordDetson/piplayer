package io.github.lorddetson.piplayer.ui.player

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.playback.PlaybackConnection
import io.github.lorddetson.piplayer.playback.PlaybackExtras
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

    private val mutableExtras = MutableStateFlow(PlaybackExtras.NONE)

    /** The sleep timer and "stop after current track" as the service reports them (§6.5). */
    val extras: StateFlow<PlaybackExtras> = mutableExtras.asStateFlow()

    private val connection = PlaybackConnection(application, object : MediaController.Listener {
        @OptIn(UnstableApi::class)
        override fun onError(controller: MediaController, sessionError: SessionError) {
            mutableMessages.trySend(sessionError.message)
        }

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            mutableExtras.value = PlaybackExtras.from(extras)
        }
    })

    private var controller: MediaController? = null

    init {
        connection.whenConnected {
            controller = this
            addListener(listener)
            showCover(mediaMetadata.artworkData)
            mutableExtras.value = PlaybackExtras.from(sessionExtras)
            mutablePlayer.value = this
            // Opening the screen shows the queue saved before the process died, paused (docs/playback.md §4.4).
            sendCustomCommand(PlaybackSessionCallback.RESTORE_COMMAND, Bundle.EMPTY)
        }
    }

    /** A sleep timer of [durationMs] (§6.3). */
    fun setSleepTimer(durationMs: Long, finishTrack: Boolean) = connection.whenConnected {
        val args = Bundle().apply {
            putLong(PlaybackSessionCallback.EXTRA_DURATION_MS, durationMs)
            putBoolean(PlaybackSessionCallback.EXTRA_FINISH_TRACK, finishTrack)
        }
        sendCustomCommand(PlaybackSessionCallback.SLEEP_TIMER_COMMAND, args)
    }

    fun cancelSleepTimer() = setSleepTimer(0, finishTrack = false)

    /** "Stop after current track" (§6.4). */
    fun setStopAfterCurrent(enabled: Boolean) = connection.whenConnected {
        val args = Bundle().apply { putBoolean(PlaybackSessionCallback.EXTRA_ENABLED, enabled) }
        sendCustomCommand(PlaybackSessionCallback.STOP_AFTER_CURRENT_COMMAND, args)
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
     * Inserts files opened from another app right after the current song; the first becomes current and plays
     * (docs/playback.md §5.1). The service reads their tags and reports how many files could not be opened.
     */
    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val arguments = Bundle().apply {
            putStringArrayList(PlaybackSessionCallback.EXTRA_URIS, ArrayList(uris.map(Uri::toString)))
        }
        connection.whenConnected {
            val result = sendCustomCommand(PlaybackSessionCallback.OPEN_COMMAND, arguments)
            result.addListener(
                {
                    val sessionResult = runCatching { result.get() }.getOrNull() ?: return@addListener
                    val app = getApplication<Application>()
                    if (sessionResult.resultCode != SessionResult.RESULT_SUCCESS) {
                        mutableMessages.trySend(app.getString(R.string.open_files_failed))
                    } else {
                        val skipped = sessionResult.extras.getInt(PlaybackSessionCallback.EXTRA_SKIPPED, 0)
                        if (skipped > 0) {
                            mutableMessages.trySend(
                                app.resources.getQuantityString(R.plurals.open_files_skipped, skipped, skipped),
                            )
                        }
                    }
                },
                ContextCompat.getMainExecutor(getApplication()),
            )
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
