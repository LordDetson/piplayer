package io.github.lorddetson.piplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.R

/** The one player of PiPlayer and its media session, which every surface talks to (docs/playback.md §1.1). */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var callback: PlaybackSessionCallback? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        check(ProcessRole.current(this) == ProcessRole.MAIN) { "The playback service runs only in the main process" }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )
        val player = PlaybackPlayer.builder(this).build()
        // Before the session's own listener, so that a skipped song's error never reaches the controllers (§2.4).
        player.addListener(BrokenSongSkipper(player, ::stopped))
        val callback = PlaybackSessionCallback(this).also { callback = it }
        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(openApp())
            .build()
    }

    /** Playback stopped on broken songs or a device error: the controllers get a message they can show (§2.4). */
    @OptIn(UnstableApi::class)
    private fun stopped(failures: Int, kind: FailureKind) {
        val error = when (kind) {
            FailureKind.SONG -> SessionError(
                SessionError.ERROR_IO,
                resources.getQuantityString(R.plurals.playback_stopped_failures, failures, failures),
                Bundle().apply { putInt(EXTRA_FAILURES, failures) },
            )
            FailureKind.DEVICE -> SessionError(SessionError.ERROR_UNKNOWN, getString(R.string.playback_stopped_device))
        }
        session?.sendError(error)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * Swiping PiPlayer away from recents keeps playback that is playing or held by a short interruption (a call), so
     * that it resumes afterwards; anything else stops the service. Media3's own rule would also stop held playback.
     */
    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        val playing = player != null && player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (!playing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        session?.let { session ->
            val player = session.player
            session.release()
            player.release()
        }
        session = null
        callback?.release()
        callback = null
        super.onDestroy()
    }

    /** What tapping the notification does: bring PiPlayer to the front, as its launcher icon would. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        checkNotNull(packageManager.getLaunchIntentForPackage(packageName)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "playback"

        /** In the extras of the message sent when playback stops: how many songs failed in a row. */
        const val EXTRA_FAILURES = "io.github.lorddetson.piplayer.FAILURES"
    }
}
