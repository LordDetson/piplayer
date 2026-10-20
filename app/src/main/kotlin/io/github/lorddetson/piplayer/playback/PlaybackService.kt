package io.github.lorddetson.piplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.R

/** The one player of PiPlayer and its media session, which every surface talks to (docs/playback.md §1.1). */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null

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
        session = MediaLibrarySession.Builder(this, PlaybackPlayer.builder(this).build(), PlaybackSessionCallback(packageName))
            .setSessionActivity(openApp())
            .build()
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
    }
}
