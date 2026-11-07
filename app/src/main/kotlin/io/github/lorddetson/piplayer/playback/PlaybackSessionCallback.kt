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
import androidx.media3.common.C
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
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.library.identity.TrackRef
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.settings.LibrarySettings
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Who may connect to the session and what they may play or browse (docs/playback.md §1.4, §2.3), how the saved queue
 * is restored (docs/playback.md §4.3, §4.4), and how opened files join it (docs/playback.md §5).
 */
// The queue's edits call the session's own player, which is a Media3 ForwardingPlayer over ExoPlayer (§9).
@OptIn(UnstableApi::class)
class PlaybackSessionCallback(
    private val context: Context,
    private val store: PlaybackStateStore,
    private val controls: PlaybackControls = PlaybackControls.NONE,
    /** Finds the saved current song again (docs/playback.md §4.8); null where there is no library. */
    private val trackRefs: () -> LibraryTrackRefs? = {
        try {
            Library.trackRefs(context)
        } catch (e: Exception) {
            Log.w(TAG, "No library to find saved songs in", e)
            null
        }
    },
    /** The library tree that controllers browse (docs/playback.md §10). */
    private val tree: LibraryTree = LibraryTree(context),
    private val onQueueRequest: (QueueState?) -> Unit,
) : MediaLibrarySession.Callback, SessionPlayer.Edits {

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

    /** Reads the library tree for browsing controllers, off the main thread and apart from the queue (§10.5). */
    private val browse = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The songs a controller's add resolved, by the media IDs given to Media3, until it puts them into the player (§10.3);
     * only touched on the main thread.
     */
    private val pendingAdds = HashMap<String, PendingAdd>()

    private class PendingAdd(val track: TrackRef, val libraryId: String?)

    /** The next number an added entry of a controller's add may take; only touched on the main thread. */
    private var nextAddNumber = 1L

    /** A restore or a queue change that is superseded stops before it touches the player. */
    private val restoreGeneration = AtomicInteger()

    /** The queue the player has, as far as saving and inserting opened files are concerned. */
    @Volatile
    private var currentOrigin: QueueState? = null

    @Volatile
    private var released = false

    /** The player the session wraps, once the service has it; the edits of the queue go through it (§9). */
    private var player: SessionPlayer? = null

    /** Counts the changes of the queue's state, so that an undo knows whether the queue changed since (§9.2). */
    private var version = 0L

    /** The entry removed last and what undoing it needs; only touched on the main thread. */
    private var lastRemoval: Removal? = null

    private class Removal(val before: QueueState?, val row: Int, val position: Int?, val item: MediaItem, val version: Long)

    /** The URI of an opened file that the last removal took out: its grant stays while an undo may want it back. */
    private val undoUri: String?
        get() = lastRemoval?.item?.mediaId?.takeIf { QueueIds.fileId(it) == null }

    private val transitions = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onTransition()

        // A new play order makes the saved play position of a removal meaningless (§9.2).
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            version++
        }
    }

    /** Edits of the queue go through [player], which tells this callback about them (§9). Call it on the main thread. */
    fun attach(player: SessionPlayer) {
        this.player = player
        player.edits = this
        player.addListener(transitions)
    }

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
                .add(ADD_TO_QUEUE_COMMAND)
                .add(REMOVE_FROM_QUEUE_COMMAND)
                .add(UNDO_REMOVE_COMMAND)
                .add(MOVE_IN_QUEUE_COMMAND)
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
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

    /** Every ID of the tree resolves, for controllers that may read the library (§10.1, §10.4). */
    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (mediaId == ROOT_ID) return Futures.immediateFuture(LibraryResult.ofItem(tree.root(), null))
        if (!mayReadLibrary(session, browser)) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        return browsed { tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) }
    }

    /** Controllers that may not read the library see an empty root and nothing else (§10.4). */
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!mayReadLibrary(session, browser)) {
            return Futures.immediateFuture(
                if (parentId == ROOT_ID) LibraryResult.ofItemList(ImmutableList.of(), params) else LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED),
            )
        }
        return browsed {
            tree.children(parentId, page, pageSize)?.let { LibraryResult.ofItemList(it, params) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
    }

    /** The app's search (§10.3); the browser then asks for the results. */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        if (!mayReadLibrary(session, browser)) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        return browsed {
            val count = tree.search(query).size
            mainHandler.post { if (!released) session.notifySearchResultChanged(browser, query, count, params) }
            LibraryResult.ofVoid(params)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!mayReadLibrary(session, browser)) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        return browsed {
            val found = tree.search(query)
            LibraryResult.ofItemList(found.slice(TreeBuckets.page(found.size, page, pageSize)), params)
        }
    }

    /** Runs [read] on the browsing thread; a failure to read the library is an error of the result, not of the future. */
    private fun <T : Any> browsed(read: () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> =
        try {
            browse.submit<LibraryResult<T>> {
                try {
                    read()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read the library tree", e)
                    LibraryResult.ofError(SessionError.ERROR_IO)
                }
            }
        } catch (_: RejectedExecutionException) {
            // The service is gone.
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_INVALID_STATE))
        }

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
        val single = mediaItems.singleOrNull()
        val request = single?.let { QueueRequest.parse(it.mediaId) }
        // A playable node of the library tree, or a voice request: a search query without a media ID (§10.3).
        val node = single?.mediaId?.takeIf { request == null && TreeNode.parse(it) != null }
        val voice = single?.takeIf { request == null && it.mediaId.isEmpty() }?.requestMetadata?.searchQuery
        val names = request != null || node != null || voice != null
        if (names && !mayReadLibrary(mediaSession, controller)) {
            return Futures.immediateFailedFuture(UnsupportedOperationException("This app may not read the song list"))
        }
        if (!names) {
            if (mediaItems.isNotEmpty() && mediaItems.all(::isLibraryItem) && mayReadLibrary(mediaSession, controller)) {
                return setLibrarySongs(token, mediaItems, startIndex, startPositionMs)
            }
            return Futures.transform(
                onAddMediaItems(mediaSession, controller, mediaItems),
                { items ->
                    if (token == restoreGeneration.get()) {
                        currentOrigin = null
                        openGrants.keepOnly(emptyList())
                        report(null)
                    }
                    MediaItemsWithStartPosition(items, startIndex, startPositionMs)
                },
                MoreExecutors.directExecutor(),
            )
        }
        return expansion.submit<MediaItemsWithStartPosition> {
            val request = request
                ?: node?.let { tree.playRequest(it) ?: throw UnsupportedOperationException("This node does not play") }
                ?: tree.voiceRequest(voice.orEmpty())
                ?: throw UnsupportedOperationException("Nothing in the library matches the search")
            val libraryId = libraryIdNow()
            val songs = songsFor(request)
            val start = QueueRequest.startIndex(songs, request)
            if (start < 0) throw UnsupportedOperationException("The song list is empty")
            if (token != restoreGeneration.get()) throw UnsupportedOperationException("The queue request was superseded")
            val state = QueueState(request, emptyList(), libraryId)
            // On the main thread, where the edits run, after any edit already on its way and before Media3 sets the
            // items the future gives it (§9): an edit never mixes this queue's state with the old player.
            mainHandler.post {
                if (token == restoreGeneration.get()) {
                    currentOrigin = state
                    openGrants.keepOnly(emptyList())
                    lastRemoval = null
                    report(state)
                }
            }
            MediaItemsWithStartPosition(songs.map(::mediaItem), start, startPositionMs)
        }
    }

    /**
     * Only PiPlayer itself may name files: an item from another app would make PiPlayer open a file with PiPlayer's
     * permissions. Controllers that may read the library name its items by media ID (§10.3). The media
     * notification's controller runs in PiPlayer's process but never adds items, and Media3 hands it to this callback in
     * place of a controller that says it is the system UI, so it counts as another app.
     */
    @OptIn(UnstableApi::class)
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = when {
        controller.uid == Process.myUid() && !mediaSession.isMediaNotificationController(controller) &&
            mediaItems.all { it.localConfiguration != null } -> Futures.immediateFuture(mediaItems)
        mediaItems.isNotEmpty() && mediaItems.all(::isLibraryItem) && mayReadLibrary(mediaSession, controller) -> addLibrarySongs(mediaItems)
        else -> Futures.immediateFailedFuture(UnsupportedOperationException("Only PiPlayer's own items and library items can be played"))
    }

    /**
     * Whether [item] names something of the library rather than a file: a queue request, a node of the tree, a song of
     * the queue (a file ID or an added entry's ID), or a search query without a media ID (§10.3).
     */
    private fun isLibraryItem(item: MediaItem): Boolean {
        if (item.localConfiguration != null) return false
        val id = item.mediaId
        if (id.isEmpty()) return item.requestMetadata.searchQuery != null
        return QueueRequest.parse(id) != null || QueueIds.fileId(id) != null || TreeNode.parse(id) != null
    }

    /** The songs that library items name, with their track references and the library ID they were read under. */
    private class ResolvedSongs(val songs: List<Song>, val tracks: List<TrackRef>, val libraryId: String?)

    /** Reads the songs of [mediaItems] (§10.3); blocks, so call it on the expansion thread. */
    private fun resolveSongs(mediaItems: List<MediaItem>): ResolvedSongs {
        val songs = mediaItems.flatMap { item -> tree.songsToAdd(item).orEmpty() }
        if (songs.isEmpty()) throw UnsupportedOperationException("The items name no song of the library")
        val refs = trackRefs()
        val libraryId = libraryIdNow(refs)
        val tracks = songs.map { song -> refs.lookup { it.refOf(song.id) } ?: TrackRef(song.id, null, null, null, null) }
        return ResolvedSongs(songs, tracks, libraryId)
    }

    /** A controller's add of library items: Media3 puts them in, and [inserting] journals them (§10.3). */
    private fun addLibrarySongs(mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        val result = SettableFuture.create<List<MediaItem>>()
        val resolving = expansion.submit<Unit> {
            val resolved = resolveSongs(mediaItems)
            mainHandler.post {
                if (released) {
                    result.setException(IllegalStateException("The playback service is gone"))
                    return@post
                }
                // Numbers no entry of the queue has, also when several adds are on their way.
                val first = maxOf(nextAddNumber, currentOrigin?.edits?.nextNumber ?: 1L)
                nextAddNumber = first + resolved.songs.size
                val items = resolved.songs.mapIndexed { index, song -> mediaItem(song, first + index) }
                items.forEachIndexed { index, item -> pendingAdds[item.mediaId] = PendingAdd(resolved.tracks[index], resolved.libraryId) }
                result.set(items)
            }
        }
        Futures.addCallback(resolving, failureTo(result), MoreExecutors.directExecutor())
        return result
    }

    /** A controller's set of library items other than one list: a queue of added entries (§10.3). */
    private fun setLibrarySongs(token: Int, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> =
        expansion.submit<MediaItemsWithStartPosition> {
            val resolved = resolveSongs(mediaItems)
            if (token != restoreGeneration.get()) throw UnsupportedOperationException("The set was superseded")
            // The item to start at stands for its first song.
            val start = if (startIndex in mediaItems.indices) {
                mediaItems.take(startIndex).sumOf { item -> tree.songsToAdd(item).orEmpty().size }.coerceAtMost(resolved.songs.lastIndex)
            } else {
                C.INDEX_UNSET
            }
            val state = QueueEditing.added(resolved.libraryId, resolved.tracks)
            mainHandler.post {
                if (token == restoreGeneration.get()) {
                    currentOrigin = state
                    openGrants.keepOnly(emptyList())
                    lastRemoval = null
                    report(state)
                }
            }
            val items = resolved.songs.mapIndexed { index, song -> mediaItem(song, index + 1L) }
            MediaItemsWithStartPosition(items, start, if (start == C.INDEX_UNSET) C.TIME_UNSET else startPositionMs)
        }

    private fun <T> failureTo(result: SettableFuture<T>) = object : FutureCallback<Any?> {
        override fun onSuccess(value: Any?) = Unit

        override fun onFailure(t: Throwable) {
            result.setException(t)
        }
    }

    /** Journals the songs of a controller's add as Media3 puts them in at [index] (§10.3). */
    override fun inserting(index: Int, items: List<MediaItem>) {
        if (items.isEmpty() || items.any { it.mediaId !in pendingAdds }) return
        val adds = items.map { pendingAdds.remove(it.mediaId)!! }
        val player = player ?: return
        val keys = items.map { EntryKey.Added(QueueIds.addedNumber(it.mediaId)!!) }
        val tracks = adds.map { it.track }
        if (player.mediaItemCount == 0) {
            // An empty player: the songs are the queue, as an add of PiPlayer's screen makes it (§9.1).
            restoreGeneration.incrementAndGet()
            val added = keys.zip(tracks) { key, track -> AddedEntry(key.number, track) }
            lastRemoval = null
            commit(QueueState(null, emptyList(), adds.first().libraryId, QueueEdits(added = added, nextNumber = keys.maxOf { it.number } + 1)))
            return
        }
        val state = currentOrigin ?: return
        val view = viewOf(player, state) ?: return
        edited(QueueEditing.insertAt(state, view, index.coerceIn(0, view.keys.size), keys, tracks))
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
            customCommand.customAction == ADD_TO_QUEUE_ACTION && mayControl -> addToQueue(args)
            customCommand.customAction == REMOVE_FROM_QUEUE_ACTION && mayControl -> Futures.immediateFuture(removeFromQueue(args))
            customCommand.customAction == UNDO_REMOVE_ACTION && mayControl -> Futures.immediateFuture(undoRemoval(args.getLong(EXTRA_TOKEN, -1)))
            customCommand.customAction == MOVE_IN_QUEUE_ACTION && mayControl -> Futures.immediateFuture(moveInQueue(args))
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
                            restored.order?.let { (player as? SessionPlayer)?.awaitOrder(it, restored.items.map { item -> item.mediaId }) }
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
                        // Shuffle first, while the player is still empty: the queue then takes the restored order,
                        // or starts a new one at the restored song, instead of shuffle starting one afterwards.
                        restored.order?.let { (player as? SessionPlayer)?.awaitOrder(it, restored.items.map { item -> item.mediaId }) }
                        player.shuffleModeEnabled = snapshot.shuffle
                        player.repeatMode = snapshot.repeatMode
                        player.setMediaItems(restored.items, restored.start.index, restored.start.positionMs)
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
                            val items = opened.map(::mediaItem)
                            val sessionPlayer = player as? SessionPlayer
                            if (sessionPlayer != null) {
                                // Right after the current song in the play order too, so that nothing is skipped (§5.1, §9.3).
                                sessionPlayer.insert(currentIndex + 1, items, sessionPlayer.playOrder?.let { it.positionOf(currentIndex) + 1 })
                            } else {
                                player.addMediaItems(currentIndex + 1, items)
                            }
                            player.seekTo(currentIndex + 1, 0)
                            player.prepare()
                            player.play()
                        }
                    }
                    currentOrigin = newState
                    openGrants.take(newState.openItems.map { it.uri })
                    openGrants.keepOnly(newState.openItems.map { it.uri })
                    report(newState)
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
        val refs = trackRefs()
        val libraryId = libraryIdNow(refs)
        // Schema 2 named songs by their media index IDs (docs/playback.md §4.8).
        val request = snapshot.queue?.let { request ->
            if (!snapshot.mediaIndexIds) {
                request
            } else {
                request.copy(startSongId = refs.lookup { it.fileOfMediaStoreId(request.startSongId) } ?: -1)
            }
        }?.let { followed(it, currentFileId(snapshot, refs)) }
        val library = request?.let {
            runCatching { songsFor(it) }.getOrElse { e ->
                Log.w(TAG, "Could not read the library part of the saved queue", e)
                emptyList()
            }
        }.orEmpty()
        val file = queueFileOf(snapshot)
        val savedEdits = file?.let { QueueEdits(it.added, it.journal, it.upNext.filterIsInstance<EntryKey.Added>(), it.nextNumber) }
            ?: QueueEdits()
        // File IDs of another library name other files (docs/playback.md §9.4).
        val songKeys = file?.libraryId == null || file.libraryId == libraryId
        val entries = QueueRebuild.entries(library, savedEdits, addedSongs(savedEdits, file?.libraryId, refs), songKeys)
        var order = QueueRestore.withOpened(entries, snapshot, openItems)
        if (order.isEmpty()) return null
        val currentFileId = currentFileId(snapshot, refs)
        val start = QueueRestore.choose(order, snapshot, currentFileId) ?: return null
        val shuffled = savedOrder(snapshot, file, order, start.index, currentFileId, libraryId, savedEdits.upNext.toSet())
        var edits = savedEdits
        var libraryRequest = if (order.any { it is QueueSlot.Library } || !savedEdits.isEmpty) request else null
        if (QueueRebuild.mustFold(savedEdits, songKeys)) {
            // The journal is folded here, where every entry gets its media ID anew (§9.4); the order keeps its indices.
            val folded = QueueRebuild.fold(order, savedEdits) { id -> refs.lookup { it.refOf(id) } }
            order = folded.first
            edits = folded.second
            libraryRequest = null
        }
        val items = order.map { slot ->
            when (slot) {
                is QueueSlot.Library -> mediaItem(slot.song)
                is QueueSlot.Open -> mediaItem(slot.item.file)
                is QueueSlot.Added -> mediaItem(slot.song, slot.number)
            }
        }
        val openItems = QueueRestore.openItems(order)
        if (libraryRequest == null && openItems.isEmpty() && edits.added.isEmpty()) return null
        return RestoredQueue(items, start, QueueState(libraryRequest, openItems, libraryId, edits), shuffled)
    }

    /** The queue file [snapshot] names, or null when there is none or it is of another revision. */
    private fun queueFileOf(snapshot: PlaybackSnapshot): QueueFile? {
        if (snapshot.queueRevision <= 0) return null
        return store.readQueueBlocking()?.takeIf { it.revision == snapshot.queueRevision }
    }

    /** The songs of the added entries of [edits] by their numbers, found by their track references (§9.4). */
    private fun addedSongs(edits: QueueEdits, savedLibraryId: String?, refs: LibraryTrackRefs?): Map<Long, Song> {
        if (edits.added.isEmpty()) return emptyMap()
        // A test's own songs are no files of the library: their IDs stand as they are.
        val asSaved = refs == null || SongList.sourceOverride != null
        val files = edits.added.associate { entry ->
            entry.number to if (asSaved) entry.track.fileId else refs.lookup { it.resolve(entry.track, savedLibraryId) }
        }
        val songs = runCatching { SongList.songsById(context, files.values.filterNotNull()) }.getOrElse { e ->
            Log.w(TAG, "Could not read the added songs of the saved queue", e)
            emptyMap()
        }
        return files.mapNotNull { (number, file) -> file?.let(songs::get)?.let { number to it } }.toMap()
    }

    /** The file ID of the saved current song as the library has it now, or null when it is gone or an opened file. */
    private fun currentFileId(snapshot: PlaybackSnapshot, refs: LibraryTrackRefs?): Long? {
        if (snapshot.songId < 0) return null
        val current = snapshot.current
        return when {
            snapshot.mediaIndexIds -> refs.lookup { it.fileOfMediaStoreId(snapshot.songId) }
            current != null && refs != null -> refs.lookup { it.resolve(current, snapshot.libraryId) }
            // Without a library to ask, the ID is taken as it is.
            else -> snapshot.songId
        }
    }

    /** A lookup in the library that fails gives null: the restore goes on without it, as with a gone song. */
    private fun <T> LibraryTrackRefs?.lookup(block: (LibraryTrackRefs) -> T?): T? {
        val refs = this ?: return null
        return try {
            block(refs)
        } catch (e: Exception) {
            Log.w(TAG, "Could not look up a saved song", e)
            null
        }
    }

    private fun libraryIdNow(refs: LibraryTrackRefs? = trackRefs()): String? = refs.lookup { it.libraryId() }

    /** The saved shuffle order over the restored queue, or null for a new one (docs/playback.md §4.8). */
    private fun savedOrder(
        snapshot: PlaybackSnapshot,
        file: QueueFile?,
        order: List<QueueSlot>,
        current: Int,
        currentFileId: Long?,
        libraryId: String?,
        upNext: Set<EntryKey>,
    ): IntArray? {
        if (!snapshot.shuffle || file == null) return null
        val saved = file.order ?: return null
        if (order.any { it is QueueSlot.Library } && file.libraryId != libraryId) return null
        val keys = order.mapIndexed { index, slot ->
            when (slot) {
                // The current song moved and has another file ID now; in the saved order it has its old one.
                is QueueSlot.Library ->
                    if (index == current && slot.song.id == currentFileId) EntryKey.Song(snapshot.songId) else EntryKey.Song(slot.song.id)
                is QueueSlot.Added -> EntryKey.Added(slot.number)
                is QueueSlot.Open -> EntryKey.Opened(snapshot.openItems.indexOf(slot.item))
            }
        }
        return QueueOrder.restore(saved, keys, current, Random(), upNext)
    }

    /** Tells the saver the queue's state, and keeps the list it plays while it plays (docs/library.md §6.4). */
    private fun report(state: QueueState?) {
        SongList.hold(this, state?.library?.list)
        version++
        onQueueRequest(state)
    }

    /**
     * A saved album or artist whose key names nothing any more, because the tag pass has read its files since (a release
     * ID, another album artist), follows the saved song to its album or artist now (docs/library.md §6.5).
     */
    private fun followed(request: QueueRequest, songId: Long?): QueueRequest {
        val kind = request.list.kind
        if (songId == null || (kind != ListKind.ALBUM && kind != ListKind.ARTIST) || SongList.sourceOverride != null ||
            !Library.mayRead(context)
        ) {
            return request
        }
        return runCatching {
            Library.awaitReady(context, READY_TIMEOUT_MS)
            val hidden = LibrarySettings(context).readHiddenFiles()
            Library.read(context) { database ->
                val lists = LibraryLists(database, hidden)
                val exists = if (kind == ListKind.ALBUM) lists.album(request.list.key) != null else lists.artist(request.list.key) != null
                QueueRequest.rekeyed(request, exists, if (exists) null else lists.links(songId))
            }
        }.getOrElse { e ->
            Log.w(TAG, "Could not follow the saved list", e)
            request
        }
    }

    /** Makes a restored or new queue the one the saver writes and the one opened files are inserted into. */
    private fun commit(state: QueueState) {
        currentOrigin = state
        openGrants.take(state.openItems.map { it.uri })
        openGrants.keepOnly(state.openItems.map { it.uri })
        report(state)
    }

    private data class RestoredQueue(
        val items: List<MediaItem>,
        val start: QueueStart,
        val state: QueueState,
        /** The restored shuffle order, or null for a new one. */
        val order: IntArray?,
    )

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

    /** The list a saved or requested queue names; the list on screen first, as a tap uses it (§2.3, library.md §6.4). */
    private fun songsFor(request: QueueRequest): List<Song> =
        SongList.current(request.list)?.takeIf { list -> list.any { it.id == request.startSongId } }
            ?: SongList.load(context, query = request.list)

    /** Releases the expansion thread; the service calls it when it is destroyed. */
    fun release() {
        released = true
        expansion.shutdown()
        browse.shutdown()
        openExpansion.shutdown()
    }

    /**
     * A queue item: what the player opens and what the notification shows before the file is read. No artwork and no
     * request metadata, which the session would publish to other apps.
     */
    private fun mediaItem(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(SessionItemRefresher.songMetadata(song))
        .build()

    /** An entry an edit added: its own media ID, so that a song added twice is two entries (§9.4). */
    private fun mediaItem(song: Song, number: Long): MediaItem = mediaItem(song).buildUpon()
        .setMediaId(QueueIds.added(number, song.id))
        .build()

    /** The queue of [player] as [state] names its entries, or null while the two disagree. */
    private fun viewOf(player: SessionPlayer, state: QueueState): QueueView? {
        val ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        val keys = QueueIds.keys(ids, state.openItems) ?: return null
        val current = player.currentMediaItemIndex
        if (current !in keys.indices) return null
        return QueueView(keys, current, player.playOrder?.indices())
    }

    /** Makes [state] the queue's after an edit (§9). */
    private fun edited(state: QueueState) {
        currentOrigin = state
        // A file that left the queue gives back its grant, unless an undo may still want it (§5.3).
        openGrants.keepOnly(state.openItems.map { it.uri } + listOfNotNull(undoUri))
        report(state)
    }

    /** The queue became empty: it and the saved one are forgotten (§9.4). */
    private fun emptied() {
        currentOrigin = null
        report(null)
        store.enqueueClear()
    }

    override fun upNextRows(): IntArray {
        val player = player ?: return IntArray(0)
        val state = currentOrigin ?: return IntArray(0)
        if (state.edits.upNext.isEmpty()) return IntArray(0)
        return viewOf(player, state)?.let { QueueEditing.upNextRows(state, it) } ?: IntArray(0)
    }

    override fun joinAfter(): Int {
        val player = player ?: return 0
        val order = player.playOrder
        val current = player.currentMediaItemIndex
        val position = if (order != null && current in 0 until order.length) order.positionOf(current) else current.coerceAtLeast(0)
        val state = currentOrigin ?: return position
        if (state.edits.upNext.isEmpty()) return position
        return viewOf(player, state)?.let { QueueEditing.joinAfter(state, it) } ?: position
    }

    override fun removing(from: Int, to: Int) {
        val player = player ?: return
        if (from == 0 && to >= player.mediaItemCount) return emptied()
        val state = currentOrigin ?: return
        val view = viewOf(player, state) ?: return
        QueueEditing.removeRange(state, QueueView(view.keys, view.current), from, to)?.let(::edited) ?: emptied()
    }

    override fun moving(from: Int, to: Int, newIndex: Int) {
        val player = player ?: return
        val state = currentOrigin ?: return
        val view = viewOf(player, state) ?: return
        edited(QueueEditing.moveRange(state, QueueView(view.keys, view.current), from, to, newIndex))
    }

    /** A song played or passed leaves the up-next run (§9.1). */
    private fun onTransition() {
        val player = player ?: return
        val state = currentOrigin ?: return
        if (state.edits.upNext.isEmpty()) return
        val view = viewOf(player, state) ?: return
        val played = QueueEditing.played(state, view)
        if (played !== state) edited(played)
    }

    /** Adds songs right after the current one or after the up-next run; to an empty player, as its queue (§9.1). */
    private fun addToQueue(args: Bundle): ListenableFuture<SessionResult> {
        val ids = args.getLongArray(EXTRA_SONG_IDS)
        val request = args.getString(EXTRA_REQUEST)?.let(QueueRequest::parse)
        if (ids == null && request == null) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val next = args.getBoolean(EXTRA_NEXT)
        val result = SettableFuture.create<SessionResult>()
        expansion.execute {
            try {
                val songs = if (ids != null) {
                    val found = SongList.songsById(context, ids.toList())
                    ids.toList().mapNotNull(found::get)
                } else {
                    SongList.load(context, query = requireNotNull(request).list)
                }
                if (songs.isEmpty()) {
                    result.set(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return@execute
                }
                val refs = trackRefs()
                val libraryId = libraryIdNow(refs)
                val tracks = songs.map { song -> refs.lookup { it.refOf(song.id) } ?: TrackRef(song.id, null, null, null, null) }
                mainHandler.post { result.set(insertSongs(songs, tracks, libraryId, next)) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not add songs to the queue", e)
                result.set(SessionResult(SessionError.ERROR_UNKNOWN))
            }
        }
        return result
    }

    private fun insertSongs(songs: List<Song>, tracks: List<TrackRef>, libraryId: String?, next: Boolean): SessionResult {
        val player = player
        if (released || player == null) return SessionResult(SessionError.ERROR_INVALID_STATE)
        val extras = Bundle().apply {
            putInt(EXTRA_ADDED, songs.size)
            putString(EXTRA_TITLE, songs.first().title)
        }
        if (player.mediaItemCount == 0) {
            // An empty player takes the songs as its queue, paused; a restore on its way gives way to it.
            restoreGeneration.incrementAndGet()
            val state = QueueEditing.added(libraryId, tracks)
            player.pause()
            player.setMediaItems(songs.mapIndexed { index, song -> mediaItem(song, index + 1L) }, 0, 0)
            player.prepare()
            lastRemoval = null
            commit(state)
            return SessionResult(SessionResult.RESULT_SUCCESS, extras)
        }
        val state = currentOrigin ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        val view = viewOf(player, state) ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        val insertion = QueueEditing.insert(state, view, tracks, next)
        player.insert(insertion.row, songs.zip(insertion.keys) { song, key -> mediaItem(song, key.number) }, insertion.position)
        edited(insertion.state)
        return SessionResult(SessionResult.RESULT_SUCCESS, extras)
    }

    /** Removes the entry at the row the screen showed, if it is still the one it showed (§9.2). */
    private fun removeFromQueue(args: Bundle): SessionResult {
        val player = player ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        val row = args.getInt(EXTRA_ROW, -1)
        if (row !in 0 until player.mediaItemCount || player.getMediaItemAt(row).mediaId != args.getString(EXTRA_MEDIA_ID)) {
            return SessionResult(SessionError.ERROR_BAD_VALUE)
        }
        val before = currentOrigin
        val position = player.playOrder?.positionOf(row)
        val item = player.getMediaItemAt(row)
        // Known before the removal, so that the removed file keeps its grant for an undo.
        lastRemoval = Removal(before, row, position, item, -1)
        player.removeMediaItem(row)
        val removal = Removal(before, row, position, item, version)
        lastRemoval = removal
        return SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putLong(EXTRA_TOKEN, removal.version) })
    }

    /** Puts the entry removed last back where it was, in the list and the play order, if nothing changed since (§9.2). */
    private fun undoRemoval(token: Long): SessionResult {
        val player = player ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        // Only the removal the message names, and only while the queue is as that removal left it.
        val removal = lastRemoval?.takeIf { it.version == version && it.version == token }
            ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        lastRemoval = null
        val wasEmpty = player.mediaItemCount == 0
        player.insert(removal.row.coerceAtMost(player.mediaItemCount), listOf(removal.item), removal.position.takeIf { player.playOrder != null })
        if (wasEmpty) player.prepare()
        val before = removal.before
        if (before != null) {
            commit(before)
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    /** Moves an entry in the order the screen shows: the play order while shuffle is on, the list otherwise (§9.2). */
    private fun moveInQueue(args: Bundle): SessionResult {
        val player = player ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        val count = player.mediaItemCount
        val from = args.getInt(EXTRA_FROM, -1)
        val to = args.getInt(EXTRA_TO, -1)
        if (from !in 0 until count || to !in 0 until count) return SessionResult(SessionError.ERROR_BAD_VALUE)
        val order = player.playOrder
        val row = order?.indices()?.get(from) ?: from
        if (player.getMediaItemAt(row).mediaId != args.getString(EXTRA_MEDIA_ID)) return SessionResult(SessionError.ERROR_BAD_VALUE)
        if (order != null) {
            player.setPlayOrder(order.moved(from, to))
            // The play order is the queue file's: the saver writes it on the new timeline; an undo no longer fits.
            version++
        } else {
            player.moveMediaItem(from, to)
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

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

        /**
         * The custom session command with which PiPlayer's own screen adds songs to the queue (docs/playback.md §9.1):
         * [EXTRA_SONG_IDS] or the list of [EXTRA_REQUEST], right after the current song when [EXTRA_NEXT], otherwise
         * after the up-next run. Its result tells [EXTRA_ADDED] and the first song's [EXTRA_TITLE].
         */
        const val ADD_TO_QUEUE_ACTION = "io.github.lorddetson.piplayer.playback.ADD_TO_QUEUE"

        val ADD_TO_QUEUE_COMMAND = SessionCommand(ADD_TO_QUEUE_ACTION, Bundle.EMPTY)

        /** File IDs, as a long array. */
        const val EXTRA_SONG_IDS = "io.github.lorddetson.piplayer.playback.SONG_IDS"

        /** The media ID of a queue request (§2.3) whose list is added; its start is ignored. */
        const val EXTRA_REQUEST = "io.github.lorddetson.piplayer.playback.REQUEST"

        const val EXTRA_NEXT = "io.github.lorddetson.piplayer.playback.NEXT"

        const val EXTRA_ADDED = "io.github.lorddetson.piplayer.playback.ADDED"

        const val EXTRA_TITLE = "io.github.lorddetson.piplayer.playback.TITLE"

        /**
         * The custom session command with which PiPlayer's own screen removes the entry at [EXTRA_ROW] of the list, which
         * must have [EXTRA_MEDIA_ID] (docs/playback.md §9.2).
         */
        const val REMOVE_FROM_QUEUE_ACTION = "io.github.lorddetson.piplayer.playback.REMOVE_FROM_QUEUE"

        val REMOVE_FROM_QUEUE_COMMAND = SessionCommand(REMOVE_FROM_QUEUE_ACTION, Bundle.EMPTY)

        /** Puts back the entry removed last, while the queue has not changed since (§9.2). */
        const val UNDO_REMOVE_ACTION = "io.github.lorddetson.piplayer.playback.UNDO_REMOVE"

        val UNDO_REMOVE_COMMAND = SessionCommand(UNDO_REMOVE_ACTION, Bundle.EMPTY)

        /**
         * The custom session command with which PiPlayer's own screen moves the entry with [EXTRA_MEDIA_ID] from place
         * [EXTRA_FROM] to [EXTRA_TO] of the order it shows: the play order while shuffle is on, the list otherwise (§9.2).
         */
        const val MOVE_IN_QUEUE_ACTION = "io.github.lorddetson.piplayer.playback.MOVE_IN_QUEUE"

        val MOVE_IN_QUEUE_COMMAND = SessionCommand(MOVE_IN_QUEUE_ACTION, Bundle.EMPTY)

        /** In the result of [REMOVE_FROM_QUEUE_COMMAND] and the arguments of [UNDO_REMOVE_COMMAND]: which removal. */
        const val EXTRA_TOKEN = "io.github.lorddetson.piplayer.playback.TOKEN"

        const val EXTRA_ROW = "io.github.lorddetson.piplayer.playback.ROW"

        const val EXTRA_MEDIA_ID = "io.github.lorddetson.piplayer.playback.MEDIA_ID"

        const val EXTRA_FROM = "io.github.lorddetson.piplayer.playback.FROM"

        const val EXTRA_TO = "io.github.lorddetson.piplayer.playback.TO"

        /** Commands that change how the player is set up rather than what it plays. */
        private val SETUP_COMMANDS = intArrayOf(
            Player.COMMAND_SET_AUDIO_ATTRIBUTES,
            Player.COMMAND_SET_VOLUME,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            Player.COMMAND_SET_VIDEO_SURFACE,
        )

        private const val TAG = "PlaybackSession"

        /** As long as a list read waits for the first sync (docs/library.md §1.4). */
        private const val READY_TIMEOUT_MS = 30_000L
    }
}
