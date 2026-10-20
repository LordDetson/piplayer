package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
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
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import java.util.concurrent.Executors

/** Who may connect to the session and what they may play or browse (docs/playback.md §1.4, §2.3). */
class PlaybackSessionCallback(private val context: Context) : MediaLibrarySession.Callback {

    private val packageName = context.packageName

    /** Expands queue requests off the main thread, one at a time. */
    private val expansion = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    /**
     * Every controller may browse and control playback. Changing how the player is set up stays with PiPlayer itself
     * and the controllers Android trusts (the system, media control permissions, enabled notification listeners); a
     * controller that names PiPlayer's package from another app is refused. The queue is the user's music library, so
     * only controllers that may read it get the timeline (§1.4); the media notification's controller does not either,
     * so that the platform session publishes no queue (§2.3).
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
        return ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
            .setAvailablePlayerCommands(playerCommands)
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
     */
    @OptIn(UnstableApi::class)
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        val request = mediaItems.singleOrNull()?.let { QueueRequest.parse(it.mediaId) }
        if (request != null && !mayReadLibrary(mediaSession, controller)) {
            return Futures.immediateFailedFuture(UnsupportedOperationException("This app may not read the song list"))
        }
        if (request == null) {
            return Futures.transform(
                onAddMediaItems(mediaSession, controller, mediaItems),
                { items -> MediaItemsWithStartPosition(items, startIndex, startPositionMs) },
                MoreExecutors.directExecutor(),
            )
        }
        return expansion.submit<MediaItemsWithStartPosition> {
            val songs = SongList.current()?.takeIf { list -> list.any { it.id == request.startSongId } }
                ?: SongList.load(context)
            val start = QueueRequest.startIndex(songs, request)
            if (start < 0) throw UnsupportedOperationException("The song list is empty")
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

    /** Releases the expansion thread; the service calls it when it is destroyed. */
    fun release() {
        expansion.shutdown()
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

    companion object {
        /** The library root; its folders arrive with the library in M2a. */
        const val ROOT_ID = "root"

        /** Commands that change how the player is set up rather than what it plays. */
        private val SETUP_COMMANDS = intArrayOf(
            Player.COMMAND_SET_AUDIO_ATTRIBUTES,
            Player.COMMAND_SET_VOLUME,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            Player.COMMAND_SET_VIDEO_SURFACE,
        )

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
