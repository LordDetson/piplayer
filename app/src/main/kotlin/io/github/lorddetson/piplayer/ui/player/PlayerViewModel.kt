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
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.Covers as AppCovers
import io.github.lorddetson.piplayer.playback.QueueIds
import kotlinx.coroutines.flow.drop
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.playback.PlaybackConnection
import io.github.lorddetson.piplayer.playback.PlaybackExtras
import io.github.lorddetson.piplayer.playback.PlaybackSessionCallback
import io.github.lorddetson.piplayer.playback.QueueRequest
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.ListSorts
import kotlinx.coroutines.flow.first
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

    /**
     * The current song's cover by the order of docs/library.md §13.1, or for a file opened from another app the picture
     * the player read from it; null while there is none or it is still being loaded.
     */
    val cover: StateFlow<ImageBitmap?> = mutableCover.asStateFlow()

    private val mutableCoverName = MutableStateFlow<String?>(null)

    /** The current library song's album, which colors the placeholder (docs/library.md §13.4); null otherwise. */
    val coverName: StateFlow<String?> = mutableCoverName.asStateFlow()

    private val mutableMessages = Channel<PlayerMessage>(Channel.BUFFERED)

    /** Messages of the playback service, such as playback stopping on broken songs, and of the queue's edits. */
    val messages: Flow<PlayerMessage> = mutableMessages.receiveAsFlow()

    private fun message(text: String?) {
        if (text != null) mutableMessages.trySend(PlayerMessage(text))
    }

    /** Now Playing draws the cover as wide as the screen at most. */
    private val coverSizePx = application.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }

    private var coverBytes: ByteArray? = null
    private var coverId: String? = null
    private var decoding: Job? = null
    private val covers = AppCovers.loader(application)

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = showCover()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = showCover()
    }

    private val mutableExtras = MutableStateFlow(PlaybackExtras.NONE)

    /** The sleep timer and "stop after current track" as the service reports them (§6.5). */
    val extras: StateFlow<PlaybackExtras> = mutableExtras.asStateFlow()

    private val connection = PlaybackConnection(application, object : MediaController.Listener {
        @OptIn(UnstableApi::class)
        override fun onError(controller: MediaController, sessionError: SessionError) {
            message(sessionError.message)
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
            showCover()
            mutableExtras.value = PlaybackExtras.from(sessionExtras)
            mutablePlayer.value = this
            // Opening the screen shows the queue saved before the process died, paused (docs/playback.md §4.4).
            sendCustomCommand(PlaybackSessionCallback.RESTORE_COMMAND, Bundle.EMPTY)
        }
    }

    private val timerChoices = TimerChoiceStore(application)
    private val mutableTimerChoice = MutableStateFlow(timerChoices.read())

    /** The sleep timer sheet's last choice, kept in the app's settings (§6.2). */
    val timerChoice: StateFlow<TimerChoice> = mutableTimerChoice.asStateFlow()

    fun saveTimerChoice(choice: TimerChoice) {
        mutableTimerChoice.value = choice
        timerChoices.save(choice)
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

    /**
     * Plays the whole [list] from [song], shown at [row] (§2.3, docs/library.md §6.4); without a song, from the start, as
     * "Play all" of a folder does.
     */
    fun play(row: Int, song: Song?, list: SongQuery = SongQuery.SONGS) {
        val item = MediaItem.Builder()
            .setMediaId(QueueRequest(song?.id ?: NO_SONG, row, list).mediaId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(song?.title).setArtist(song?.artist).build())
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
                        message(app.getString(R.string.open_files_failed))
                    } else {
                        val skipped = sessionResult.extras.getInt(PlaybackSessionCallback.EXTRA_SKIPPED, 0)
                        if (skipped > 0) message(app.resources.getQuantityString(R.plurals.open_files_skipped, skipped, skipped))
                    }
                },
                ContextCompat.getMainExecutor(getApplication()),
            )
        }
    }

    /**
     * Adds the songs of [target] right after the current song ([next]) or after the up-next run, and says so
     * (docs/playback.md §9.1). An album, an artist or a folder goes in the sort kept for its kind of list.
     */
    fun addToQueue(target: QueueTarget, next: Boolean) {
        viewModelScope.launch {
            val args = Bundle().apply {
                putBoolean(PlaybackSessionCallback.EXTRA_NEXT, next)
                when (target) {
                    is QueueTarget.Songs -> putLongArray(PlaybackSessionCallback.EXTRA_SONG_IDS, target.ids.toLongArray())
                    else -> putString(PlaybackSessionCallback.EXTRA_REQUEST, QueueRequest(NO_SONG, 0, queryOf(target)).mediaId)
                }
            }
            connection.whenConnected {
                val result = sendCustomCommand(PlaybackSessionCallback.ADD_TO_QUEUE_COMMAND, args)
                result.addListener(
                    {
                        val sessionResult = runCatching { result.get() }.getOrNull() ?: return@addListener
                        if (sessionResult.resultCode != SessionResult.RESULT_SUCCESS) return@addListener
                        val count = sessionResult.extras.getInt(PlaybackSessionCallback.EXTRA_ADDED)
                        val title = sessionResult.extras.getString(PlaybackSessionCallback.EXTRA_TITLE).orEmpty()
                        val resources = getApplication<Application>().resources
                        message(
                            when {
                                count == 1 && next -> resources.getString(R.string.queue_plays_next, title)
                                count == 1 -> resources.getString(R.string.queue_added, title)
                                next -> resources.getQuantityString(R.plurals.queue_plays_next_count, count, count)
                                else -> resources.getQuantityString(R.plurals.queue_added_count, count, count)
                            },
                        )
                    },
                    ContextCompat.getMainExecutor(getApplication()),
                )
            }
        }
    }

    /**
     * The list of [target] without a search: an album by disc and track, an artist and a folder in the order kept for
     * their kind (docs/playback.md §9.1, docs/library.md §6.3).
     */
    private suspend fun queryOf(target: QueueTarget): SongQuery {
        val (query, list) = when (target) {
            is QueueTarget.Album -> return SongQuery.album(target.key)
            is QueueTarget.Artist -> SongQuery.artist(target.key) to ListName.ARTIST_PAGE
            is QueueTarget.Folder -> SongQuery.tree(target.volume, target.relativePath) to ListName.FOLDERS
            is QueueTarget.Songs -> return SongQuery.SONGS
        }
        val order = ListSorts(getApplication()).order(list).first()
        return query.copy(sort = SongSort.of(order.field) ?: query.sort, reversed = order.reversed)
    }

    /** Removes the queue entry at [row] that shows [mediaId], with "Undo" in the message (docs/playback.md §9.2). */
    fun removeFromQueue(row: Int, mediaId: String, title: String) {
        val args = Bundle().apply {
            putInt(PlaybackSessionCallback.EXTRA_ROW, row)
            putString(PlaybackSessionCallback.EXTRA_MEDIA_ID, mediaId)
        }
        connection.whenConnected {
            val result = sendCustomCommand(PlaybackSessionCallback.REMOVE_FROM_QUEUE_COMMAND, args)
            result.addListener(
                {
                    val sessionResult = runCatching { result.get() }.getOrNull() ?: return@addListener
                    if (sessionResult.resultCode != SessionResult.RESULT_SUCCESS) return@addListener
                    val app = getApplication<Application>()
                    // The undo names this removal: an older message cannot put back a later one (§9.2).
                    val token = sessionResult.extras.getLong(PlaybackSessionCallback.EXTRA_TOKEN, -1)
                    mutableMessages.trySend(
                        PlayerMessage(app.getString(R.string.queue_removed, title), app.getString(R.string.queue_undo)) {
                            undoRemoval(token)
                        },
                    )
                },
                ContextCompat.getMainExecutor(getApplication()),
            )
        }
    }

    /** Puts back the entry of the removal [token] names, if the queue has not changed since (§9.2). */
    fun undoRemoval(token: Long) = connection.whenConnected {
        sendCustomCommand(PlaybackSessionCallback.UNDO_REMOVE_COMMAND, Bundle().apply { putLong(PlaybackSessionCallback.EXTRA_TOKEN, token) })
    }

    /** Moves the entry [mediaId] from place [from] to [to] of the order the queue screen shows (§9.2). */
    fun moveInQueue(from: Int, to: Int, mediaId: String) {
        val args = Bundle().apply {
            putInt(PlaybackSessionCallback.EXTRA_FROM, from)
            putInt(PlaybackSessionCallback.EXTRA_TO, to)
            putString(PlaybackSessionCallback.EXTRA_MEDIA_ID, mediaId)
        }
        connection.whenConnected { sendCustomCommand(PlaybackSessionCallback.MOVE_IN_QUEUE_COMMAND, args) }
    }

    init {
        // A change of own covers loads the current song's cover again, keeping the old one on screen meanwhile.
        viewModelScope.launch { AppCovers.own(application).version.drop(1).collect { showCover(reload = true) } }
    }

    /**
     * Loads the current song's cover once per song (docs/library.md §13.5): a library song's from the cover loader, a file
     * opened from another app's from the session's metadata. The controller lives in the service's process and shares
     * the bytes of the metadata, so the same picture comes as the same array. A new song shows no cover until its own is
     * loaded. A library song falls back to the stream's picture when the loader finds nothing, such as for a file the
     * library does not hold yet; that picture may arrive after the loader has finished.
     */
    private fun showCover(reload: Boolean = false) {
        val controller = controller ?: return
        val item = controller.currentMediaItem
        val id = item?.mediaId
        val bytes = controller.mediaMetadata.artworkData
        val fileId = id?.let(QueueIds::fileId)
        mutableCoverName.value = if (fileId != null) item.mediaMetadata.albumTitle?.toString() else null
        val newSong = id != coverId
        if (!newSong && !reload) {
            if (bytes === coverBytes) return
            coverBytes = bytes
            // Only a picture of the stream that came late can help, while nothing is shown and nothing is loading.
            if (bytes == null || mutableCover.value != null || decoding?.isActive == true) return
        }
        coverId = id
        coverBytes = bytes
        decoding?.cancel()
        if (newSong) mutableCover.value = null
        if (id == null) return
        decoding = viewModelScope.launch {
            val loaded = fileId?.let { covers.load(CoverRequest.Track(it), coverSizePx) }
            val image = loaded ?: coverBytes?.let { stream -> withContext(Dispatchers.Default) { Covers.decode(stream, coverSizePx) } }
            mutableCover.value = image?.asImageBitmap()
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        connection.release()
    }

    private companion object {
        /** No song of the library has it, so a request with it starts at its row. */
        const val NO_SONG = -1L
    }
}

/** A message for the snackbar, with an action such as "Undo" when it has one. */
class PlayerMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** What "Play next" and "Add to queue" add (docs/playback.md §9.1). */
sealed interface QueueTarget {
    data class Songs(val ids: List<Long>) : QueueTarget

    data class Album(val key: String) : QueueTarget

    data class Artist(val key: String) : QueueTarget

    /** A folder with all its subfolders. */
    data class Folder(val volume: String, val relativePath: String) : QueueTarget
}
