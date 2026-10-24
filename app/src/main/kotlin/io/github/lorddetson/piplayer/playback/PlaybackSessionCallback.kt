package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Who may connect to the session and what they may play or browse (docs/playback.md §1.4, §2.3), how the saved queue
 * is restored (docs/playback.md §4.3, §4.4), and how opened files join it (docs/playback.md §5).
 */
class PlaybackSessionCallback(
    private val context: Context,
    private val store: PlaybackStateStore,
    private val controls: PlaybackControls = PlaybackControls.NONE,
    private val onQueueRequest: (QueueState?) -> Unit,
) : MediaLibrarySession.Callback {

    private val packageName = context.packageName

    private val openFileReader = OpenFileReader(context)

    private val openGrants = OpenFileGrants(context.contentResolver)

    /** Expands queue requests and restores off the main thread, one at a time. */
    private val expansion = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    /**
     * Reads opened files on its own thread: a file opened from another app starts at once even while a slow library
     * restore is still reading the song list (docs/playback.md A5.1).
     */
    private val openExpansion = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    private val mainHandler = Handler(Looper.getMainLooper())

    /** A restore or a queue change that is superseded stops before it touches the player. */
    private val restoreGeneration = AtomicInteger()

    /** The queue the player has, as far as saving and inserting opened files are concerned. */
    @Volatile
    private var currentOrigin: QueueState? = null

    @Volatile
    private var released = false

    /**
     * Every controller may browse and control playback. Changing how the player is set up stays with PiPlayer itself
     * and the controllers Android trusts (the system, media control permissions, enabled notification listeners); a
     * controller that names PiPlayer's package from another app is refused. The queue is the user's music library, so
     * only controllers that may read it get the timeline (§1.4); the media notification's controller does not either,
     * so that the platform session publishes no queue (§2.3). The restore, open, sleep timer and "stop after current"
     * commands are PiPlayer's own screen's, and only it gets their state (§6.5).
     */
    @OptIn(UnstableApi::class)
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): ConnectionResult {
        val fromPiPlayer = controller.uid == Process.myUid()
        if (controller.packageName == packageName && !fromPiPlayer) return ConnectionResult.reject()
        val all = ConnectionResult.DEFAULT_PLAYER_COMMANDS
        val playerCommands = when {
            session.isMediaNotificationController(controller) -> all.buildUpon().remove(Player.COMMAND_GET_TIMELINE).build()
            fromPiPlayer || controller.isTrusted -> all
            else -> all.buildUpon()
                .removeAll(*SETUP_COMMANDS)
                .apply { if (!holdsAudioPermission(controller)) remove(Player.COMMAND_GET_TIMELINE) }
                .build()
        }
        val ownScreen = fromPiPlayer && !session.isMediaNotificationController(controller)
        val sessionCommands = if (ownScreen) {
            ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(RESTORE_COMMAND)
                .add(OPEN_COMMAND)
                .add(SLEEP_TIMER_COMMAND)
                .add(STOP_AFTER_CURRENT_COMMAND)
                .build()
        } else {
            ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
        }
        return ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .apply { if (ownScreen) setSessionExtras(controls.extras()) }
            .build()
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(ROOT, params))

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        if (mediaId == ROOT_ID) LibraryResult.ofItem(ROOT, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(
        if (parentId == ROOT_ID) {
            LibraryResult.ofItemList(ImmutableList.of(), params)
        } else {
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        },
    )

    /**
     * A single compact request (§2.3) becomes the whole song list, starting at the tapped song; it is built off the main
     * thread. Only controllers that may read the library may send it. Any other set request follows [onAddMediaItems].
     * Either way the saved queue follows what the player really got.
     */
    @OptIn(UnstableApi::class)
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        // The user changed the queue: a restore or an open still on its way must not overwrite it.
        val token = restoreGeneration.incrementAndGet()
        val request = mediaItems.singleOrNull()?.let { QueueRequest.parse(it.mediaId) }
        if (request != null && !mayReadLibrary(mediaSession, controller)) {
            return Futures.immediateFailedFuture(UnsupportedOperationException("This app may not read the song list"))
        }
        if (request == null) {
            return Futures.transform(
                onAddMediaItems(mediaSession, controller, mediaItems),
                { items ->
                    if (token == restoreGeneration.get()) {
                        currentOrigin = null
                        openGrants.keepOnly(emptyList())
                        onQueueRequest(null)
                    }
                    MediaItemsWithStartPosition(items, startIndex, startPositionMs)
                },
                MoreExecutors.directExecutor(),
            )
        }
        return expansion.submit<MediaItemsWithStartPosition> {
            val songs = songsFor(request)
            val start = QueueRequest.startIndex(songs, request)
            if (start < 0) throw UnsupportedOperationException("The song list is empty")
            if (token != restoreGeneration.get()) throw UnsupportedOperationException("The queue request was superseded")
            val state = QueueState(request, emptyList())
            currentOrigin = state
            openGrants.keepOnly(emptyList())
            onQueueRequest(state)
            MediaItemsWithStartPosition(songs.map(::mediaItem), start, startPositionMs)
        }
    }

    /**
     * Only PiPlayer itself may name files: an item from another app would make PiPlayer open a file with PiPlayer's
     * permissions. Other apps will name library items by media ID once the library exists (M2a). The media
     * notification's controller runs in PiPlayer's process but never adds items, and Media3 hands it to this callback in
     * place of a controller that says it is the system UI, so it counts as another app.
     */
    @OptIn(UnstableApi::class)
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> =
        if (controller.uid == Process.myUid() &&
            !mediaSession.isMediaNotificationController(controller) &&
            mediaItems.all { it.localConfiguration != null }
        ) {
            Futures.immediateFuture(mediaItems)
        } else {
            Futures.immediateFailedFuture(UnsupportedOperationException("Only PiPlayer's own items can be played yet"))
        }

    /**
     * PiPlayer's screen asks the service to show the saved queue paused after the app was opened (docs/playback.md
     * §4.4), to insert files opened from another app (docs/playback.md §5.1), or to set the sleep timer or "stop after
     * current track" (§6.5). Both are session commands rather than
     * automatic actions, so that a play request or a test's own queue is never raced by them; other apps and the media
     * notification's controller cannot send them.
     */
    @OptIn(UnstableApi::class)
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
        progressReporter: MediaSession.ProgressReporter?,
    ): ListenableFuture<SessionResult> {
        val mayControl = controller.uid == Process.myUid() && !session.isMediaNotificationController(controller)
        return when {
            customCommand.customAction == RESTORE_ACTION && mayControl -> restorePaused(session)
            customCommand.customAction == OPEN_ACTION && mayControl -> openFiles(session, args)
            customCommand.customAction == SLEEP_TIMER_ACTION && mayControl -> {
                controls.setSleepTimer(args.getLong(EXTRA_DURATION_MS), args.getBoolean(EXTRA_FINISH_TRACK))
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            customCommand.customAction == STOP_AFTER_CURRENT_ACTION && mayControl -> {
                controls.setStopAfterCurrent(args.getBoolean(EXTRA_ENABLED))
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            else -> super.onCustomCommand(session, controller, customCommand, args, progressReporter)
        }
    }

    /**
     * The system asks for the current item when it shows its recent media controls, and for the queue when the user
     * presses play on a stopped service (docs/playback.md §4.4). The first gets lightweight metadata, the second the
     * whole queue at its saved position.
     */
    @OptIn(UnstableApi::class)
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        if (isForPlayback) resumptionItems(mediaSession) else recentItem()

    /** What the system UI shows before playback resumes: one item with the saved song's metadata, not the queue. */
    @OptIn(UnstableApi::class)
    private fun recentItem(): ListenableFuture<MediaItemsWithStartPosition> {
        val result = SettableFuture.create<MediaItemsWithStartPosition>()
        expansion.execute {
            val snapshot = runCatching { store.readBlocking() }.getOrNull()
            mainHandler.post {
                if (released) {
                    result.setException(IllegalStateException("The playback service is gone"))
                } else {
                    val item = snapshot?.let(::metadataItem)
                    result.set(MediaItemsWithStartPosition(item?.let(::listOf).orEmpty(), 0, snapshot?.positionMs ?: 0))
                }
            }
        }
        return result
    }

    /** Rebuilds the saved queue and starts at the saved item and position; Media3 then plays it. */
    @OptIn(UnstableApi::class)
    private fun resumptionItems(mediaSession: MediaSession): ListenableFuture<MediaItemsWithStartPosition> {
        val token = restoreGeneration.incrementAndGet()
        val result = SettableFuture.create<MediaItemsWithStartPosition>()
        expansion.execute {
            try {
                val snapshot = store.readBlocking()
                    ?: throw UnsupportedOperationException("There is no saved queue")
                val (openItems, skipped) = readableOpenItems(snapshot)
                val restored = restoredQueue(snapshot, openItems)
                if (restored == null) {
                    mainHandler.post {
                        if (!released && token == restoreGeneration.get()) sendOpenSkipped(mediaSession, skipped)
                    }
                    throw UnsupportedOperationException("The saved queue is empty")
                }
                mainHandler.post {
                    val player = mediaSession.player
                    when {
                        released || token != restoreGeneration.get() ->
                            result.setException(IllegalStateException("The restore was superseded"))
                        player.mediaItemCount > 0 ->
                            result.setException(IllegalStateException("A queue is already loaded"))
                        else -> {
                            sendOpenSkipped(mediaSession, skipped)
                            player.shuffleModeEnabled = snapshot.shuffle
                            player.repeatMode = snapshot.repeatMode
                            commit(restored.state)
                            result.set(MediaItemsWithStartPosition(restored.items, restored.start.index, restored.start.positionMs))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resume playback", e)
                result.setException(e)
            }
        }
        return result
    }

    /** The app was opened: show the saved queue paused, unless a play request or the user got there first. */
    private fun restorePaused(mediaSession: MediaSession): ListenableFuture<SessionResult> {
        // A restore that an open put off can come after the service has gone.
        if (released) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val player = mediaSession.player
        if (player.mediaItemCount > 0) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        val token = restoreGeneration.incrementAndGet()
        val result = SettableFuture.create<SessionResult>()
        expansion.execute {
            try {
                val snapshot = store.readBlocking()
                if (snapshot == null) {
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    return@execute
                }
                val (openItems, skipped) = readableOpenItems(snapshot)
                val restored = restoredQueue(snapshot, openItems)
                if (restored == null) {
                    mainHandler.post {
                        if (!released && token == restoreGeneration.get()) sendOpenSkipped(mediaSession, skipped)
                    }
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    return@execute
                }
                mainHandler.post {
                    if (released || token != restoreGeneration.get() || player.mediaItemCount > 0) {
                        result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        sendOpenSkipped(mediaSession, skipped)
                        player.pause()
                        player.setMediaItems(restored.items, restored.start.index, restored.start.positionMs)
                        player.shuffleModeEnabled = snapshot.shuffle
                        player.repeatMode = snapshot.repeatMode
                        commit(restored.state)
                        player.prepare()
                        result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore the saved queue", e)
                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
        return result
    }

    /**
     * Reads the files of an open-with intent and puts them right after the current item; the first one becomes current
     * and plays (docs/playback.md §5.1). An empty player starts a queue of just these files.
     */
    private fun openFiles(session: MediaSession, args: Bundle): ListenableFuture<SessionResult> {
        val uris = args.getStringArrayList(EXTRA_URIS)
            ?.mapNotNull { value -> runCatching { Uri.parse(value) }.getOrNull() }
            .orEmpty()
            .distinct()
        if (uris.isEmpty()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val token = restoreGeneration.incrementAndGet()
        val result = SettableFuture.create<SessionResult>()
        openExpansion.execute {
            try {
                val opened = uris.mapNotNull { uri -> runCatching { openFileReader.read(uri) }.getOrNull() }
                val skipped = uris.size - opened.size
                if (opened.isEmpty()) {
                    result.set(
                        SessionResult(
                            SessionError.ERROR_IO,
                            Bundle().apply { putInt(EXTRA_SKIPPED, skipped) },
                        ),
                    )
                    // The open superseded a restore before it knew that it had nothing to play. Put the saved queue
                    // back if the player is still empty, so a failed open does not leave the app blank.
                    mainHandler.post {
                        if (!released && token == restoreGeneration.get() && session.player.mediaItemCount == 0) {
                            restorePaused(session)
                        }
                    }
                    return@execute
                }
                mainHandler.post {
                    val player = session.player
                    if (released || token != restoreGeneration.get()) {
                        openGrants.keepOnly(currentOrigin?.openItems?.map { it.uri }.orEmpty())
                        result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                        return@post
                    }
                    val state = currentOrigin
                    val currentIndex = player.currentMediaItemIndex
                    val newState = if (state == null || player.mediaItemCount == 0 || currentIndex < 0) {
                        QueueState(null, opened.mapIndexed { row, file -> SavedOpenItem(row, file) })
                            .also {
                                player.setMediaItems(opened.map(::mediaItem), 0, 0)
                                player.prepare()
                                player.play()
                            }
                    } else {
                        state.withInserted(currentIndex, opened).also {
                            player.addMediaItems(currentIndex + 1, opened.map(::mediaItem))
                            player.seekTo(currentIndex + 1, 0)
                            player.prepare()
                            player.play()
                        }
                    }
                    currentOrigin = newState
                    openGrants.take(newState.openItems.map { it.uri })
                    openGrants.keepOnly(newState.openItems.map { it.uri })
                    onQueueRequest(newState)
                    result.set(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            Bundle().apply { putInt(EXTRA_SKIPPED, skipped) },
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open files", e)
                openGrants.keepOnly(currentOrigin?.openItems?.map { it.uri }.orEmpty())
                result.set(SessionResult(SessionError.ERROR_UNKNOWN))
            }
        }
        return result
    }

    /**
     * The opened files of [snapshot] that this process can still open. A temporary grant from the file manager ends
     * with the process; such a file is left out of the restored queue and the caller notes how many were left out
     * (docs/playback.md A5.3).
     */
    private fun readableOpenItems(snapshot: PlaybackSnapshot): Pair<List<SavedOpenItem>, Int> {
        val readable = snapshot.openItems.filter { item ->
            runCatching { openFileReader.canRead(Uri.parse(item.uri)) }.getOrDefault(false)
        }
        return readable to (snapshot.openItems.size - readable.size)
    }

    /** Tells the controllers that restored opened files are gone; call it on the main thread. */
    @OptIn(UnstableApi::class)
    private fun sendOpenSkipped(session: MediaSession, skipped: Int) {
        if (skipped <= 0) return
        val message = context.resources.getQuantityString(R.plurals.open_files_skipped, skipped, skipped)
        session.sendError(SessionError(SessionError.ERROR_IO, message))
    }

    /** The saved queue with the readable opened files put back and the item and command state that belongs to it. */
    private fun restoredQueue(snapshot: PlaybackSnapshot, openItems: List<SavedOpenItem>): RestoredQueue? {
        val library = snapshot.queue?.let { request ->
            runCatching { songsFor(request) }.getOrElse { e ->
                Log.w(TAG, "Could not read the library part of the saved queue", e)
                emptyList()
            }
        }.orEmpty()
        val order = QueueRestore.order(library, snapshot, openItems)
        if (order.isEmpty()) return null
        val start = QueueRestore.choose(order, snapshot) ?: return null
        val items = order.map { slot ->
            when (slot) {
                is QueueSlot.Library -> mediaItem(slot.song)
                is QueueSlot.Open -> mediaItem(slot.item.file)
            }
        }
        val openItems = QueueRestore.openItems(order)
        val libraryRequest = if (order.any { it is QueueSlot.Library }) snapshot.queue else null
        return RestoredQueue(items, start, QueueState(libraryRequest, openItems))
    }

    /** Makes a restored or new queue the one the saver writes and the one opened files are inserted into. */
    private fun commit(state: QueueState) {
        currentOrigin = state
        openGrants.take(state.openItems.map { it.uri })
        openGrants.keepOnly(state.openItems.map { it.uri })
        onQueueRequest(state)
    }

    private data class RestoredQueue(val items: List<MediaItem>, val start: QueueStart, val state: QueueState)

    /**
     * Whether [controller] may read the user's music library through the session: PiPlayer itself, the controllers
     * Android trusts, and apps that may read music anyway. Never the media notification's controller, which Media3
     * hands to the callback in place of a controller that says it is the system UI.
     */
    @OptIn(UnstableApi::class)
    private fun mayReadLibrary(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean =
        !session.isMediaNotificationController(controller) &&
            (controller.uid == Process.myUid() || controller.isTrusted || holdsAudioPermission(controller))

    /** By the caller's uid, which cannot be claimed like a package name. */
    private fun holdsAudioPermission(controller: MediaSession.ControllerInfo): Boolean =
        context.checkPermission(AudioPermission.name, -1, controller.uid) == PackageManager.PERMISSION_GRANTED

    /** The list a saved or requested queue names; the list on screen first, as a tap uses it (§2.3). */
    private fun songsFor(request: QueueRequest): List<Song> =
        SongList.current()?.takeIf { list -> list.any { it.id == request.startSongId } }
            ?: SongList.load(context)

    /** Releases the expansion thread; the service calls it when it is destroyed. */
    fun release() {
        released = true
        expansion.shutdown()
        openExpansion.shutdown()
    }

    /**
     * A queue item: what the player opens and what the notification shows before the file is read. No artwork and no
     * request metadata, which the session would publish to other apps.
     */
    private fun mediaItem(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .setDurationMs(song.durationMs)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    /** A file opened from another app; its URI is also its media ID, so the current item can be found after a restore. */
    private fun mediaItem(file: OpenedFile): MediaItem = MediaItem.Builder()
        .setMediaId(file.uri)
        .setUri(file.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(file.title)
                .setArtist(file.artist)
                .setAlbumTitle(file.album)
                .setDurationMs(file.durationMs)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    /** The saved song as the system UI shows it, before the queue is rebuilt. */
    private fun metadataItem(snapshot: PlaybackSnapshot): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(snapshot.uri ?: snapshot.songId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(snapshot.title)
                    .setArtist(snapshot.artist)
                    .setAlbumTitle(snapshot.album)
                    .setDurationMs(snapshot.durationMs)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
        snapshot.uri?.let { uri -> runCatching { Uri.parse(uri) }.getOrNull()?.let(builder::setUri) }
        return builder.build()
    }

    companion object {
        /** The library root; its folders arrive with the library in M2a. */
        const val ROOT_ID = "root"

        /** The custom session command with which PiPlayer's own screen shows the saved queue (§4.4). */
        const val RESTORE_ACTION = "io.github.lorddetson.piplayer.playback.RESTORE"

        val RESTORE_COMMAND = SessionCommand(RESTORE_ACTION, Bundle.EMPTY)

        /** The custom session command with which PiPlayer's own screen inserts files opened from another app (§5.1). */
        const val OPEN_ACTION = "io.github.lorddetson.piplayer.playback.OPEN"

        val OPEN_COMMAND = SessionCommand(OPEN_ACTION, Bundle.EMPTY)

        /** The custom session command with which PiPlayer's own screen sets or cancels the sleep timer (§6.5). */
        const val SLEEP_TIMER_ACTION = "io.github.lorddetson.piplayer.playback.SLEEP_TIMER"

        val SLEEP_TIMER_COMMAND = SessionCommand(SLEEP_TIMER_ACTION, Bundle.EMPTY)

        /** The timer's length in milliseconds; 0 or less cancels it. */
        const val EXTRA_DURATION_MS = "io.github.lorddetson.piplayer.playback.DURATION_MS"

        const val EXTRA_FINISH_TRACK = "io.github.lorddetson.piplayer.playback.FINISH_TRACK"

        /** The custom session command with which PiPlayer's own screen sets "stop after current track" (§6.5). */
        const val STOP_AFTER_CURRENT_ACTION = "io.github.lorddetson.piplayer.playback.STOP_AFTER_CURRENT"

        val STOP_AFTER_CURRENT_COMMAND = SessionCommand(STOP_AFTER_CURRENT_ACTION, Bundle.EMPTY)

        const val EXTRA_ENABLED = "io.github.lorddetson.piplayer.playback.ENABLED"

        /** The URIs of an [OPEN_COMMAND], as strings. */
        const val EXTRA_URIS = "io.github.lorddetson.piplayer.playback.URIS"

        /** How many files of an opened set could not be read; in the result of [OPEN_COMMAND]. */
        const val EXTRA_SKIPPED = "io.github.lorddetson.piplayer.playback.SKIPPED"

        /** Commands that change how the player is set up rather than what it plays. */
        private val SETUP_COMMANDS = intArrayOf(
            Player.COMMAND_SET_AUDIO_ATTRIBUTES,
            Player.COMMAND_SET_VOLUME,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            Player.COMMAND_SET_VIDEO_SURFACE,
        )

        private const val TAG = "PlaybackSession"

        private val ROOT = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()
    }
}
