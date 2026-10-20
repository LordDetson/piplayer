package io.github.lorddetson.piplayer.playback

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
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Who may connect to the session and what they may play or browse (docs/playback.md §1.4). [packageName] is
 * PiPlayer's own.
 */
class PlaybackSessionCallback(private val packageName: String) : MediaLibrarySession.Callback {

    /**
     * Every controller may browse and control playback. Changing how the player is set up stays with PiPlayer itself
     * and the controllers Android trusts (the system, media control permissions, enabled notification listeners); a
     * controller that names PiPlayer's package from another app is refused.
     */
    @OptIn(UnstableApi::class)
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): ConnectionResult {
        val fromPiPlayer = controller.uid == Process.myUid()
        if (controller.packageName == packageName && !fromPiPlayer) return ConnectionResult.reject()
        val playerCommands = if (fromPiPlayer || controller.isTrusted) {
            ConnectionResult.DEFAULT_PLAYER_COMMANDS
        } else {
            ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().removeAll(*SETUP_COMMANDS).build()
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
