package io.github.lorddetson.piplayer.debug

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.lorddetson.piplayer.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug builds (M1 step 1): plays a picked file through the playback service with a controller, the way the app will
 * (docs/playback.md §1.6). Goes away when the song list arrives.
 */
class DebugPlaybackViewModel(application: Application) : AndroidViewModel(application) {

    enum class Status { IDLE, PLAYING, PAUSED, FAILED }

    data class State(val status: Status = Status.IDLE, val title: String? = null, val error: String? = null)

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update(player)
    }

    private val controllerFuture = MediaController.Builder(
        application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java)),
    ).buildAsync()

    init {
        whenConnected {
            addListener(listener)
            update(this)
        }
    }

    /** The file picked last, whose read grant is kept until another one is picked. */
    private var picked: Uri? = null

    fun play(uri: Uri) {
        keepReadAccess(uri)
        val title = displayName(uri)
        whenConnected {
            setMediaItem(
                MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build(),
            )
            prepare()
            play()
        }
    }

    fun togglePause() = whenConnected {
        when {
            isPlaying -> pause()
            playbackState == Player.STATE_IDLE -> {
                prepare()
                play()
            }
            playbackState == Player.STATE_ENDED -> {
                seekToDefaultPosition()
                play()
            }
            else -> play()
        }
    }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture)
    }

    /** Runs [block] on the main thread once the controller is connected; drops it if the connection failed. */
    private fun whenConnected(block: MediaController.() -> Unit) {
        controllerFuture.addListener(
            { runCatching { controllerFuture.get() }.getOrNull()?.block() },
            ContextCompat.getMainExecutor(getApplication()),
        )
    }

    private fun update(player: Player) {
        val error = player.playerError
        mutableState.value = State(
            status = when {
                error != null -> Status.FAILED
                player.isPlaying -> Status.PLAYING
                player.mediaItemCount > 0 -> Status.PAUSED
                else -> Status.IDLE
            },
            title = player.mediaMetadata.title?.toString(),
            error = error?.let { "${it.errorCodeName}: ${it.message}" },
        )
    }

    /**
     * The picker's grant ends with the activity that received it, and playback outlives the activity: a seek or a
     * resume after the app was swiped away reopens the file. A persisted grant lasts; the previous one is given back.
     */
    private fun keepReadAccess(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        picked?.takeIf { it != uri }?.let { previous ->
            runCatching { resolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        picked = uri
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
