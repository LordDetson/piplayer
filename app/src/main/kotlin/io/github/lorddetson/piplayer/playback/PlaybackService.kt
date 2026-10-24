package io.github.lorddetson.piplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.R

/** The one player of PiPlayer and its media session, which every surface talks to (docs/playback.md §1.1, §4.2). */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var callback: PlaybackSessionCallback? = null
    private var saver: PlaybackStateSaver? = null
    private var controls: ServiceControls? = null
    private var effects: AudioEffectSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private val postNotificationAgain = Runnable { postNotification() }
    private var lastStartId = 0
    private var startCheckKind = StartIntentRules.Kind.MEDIA_BUTTON

    private val startCheck = Runnable {
        if (!isInForeground() && needsForeground(startCheckKind)) stopSelfSafely(lastStartId)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        check(ProcessRole.current(this) == ProcessRole.MAIN) { "The playback service runs only in the main process" }
        ensureNotificationChannel()
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
        player.addListener(MediaButtons.listener(this, ::showButtons))
        val effects = AudioEffectSession(
            open = { sendBroadcast(effectIntent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, it)) },
            close = { sendBroadcast(effectIntent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, it)) },
        ).also { effects = it }
        player.addListener(effects)
        val controls = ServiceControls(player, StopAfterCurrentStore(this), ::publishControls).also { controls = it }
        val store = PlaybackStateStore.get(this)
        val saver = PlaybackStateSaver(player, store).also { saver = it }
        val callback = PlaybackSessionCallback(this, store, controls, saver::setQueue).also { callback = it }
        session = MediaLibrarySession.Builder(this, SessionPlayer(player), callback)
            .setSessionActivity(openApp())
            .setMediaButtonPreferences(MediaButtons.of(this, player.shuffleModeEnabled, player.repeatMode))
            .build()
        saver.start()
        isRunningForTests = true
    }

    /**
     * Media3 starts the service for a media button, a notification action or its own notification; anything else is
     * another app, or malformed, and must not leave the system waiting for a foreground notification it will never get
     * (docs/playback.md §4.6). A legitimate start that fails to enter the foreground, such as a play request with no
     * saved queue, is stopped just before the system's five-second deadline.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent == null) return super.onStartCommand(intent, flags, startId)
        val kind = StartIntentRules.classify(
            intent.action,
            intent.hasExtra(StartIntentRules.MEDIA3_SELF_UID_EXTRA),
        )
        val malformed = kind == StartIntentRules.Kind.MEDIA_BUTTON &&
            intent.keyEvent()?.let { StartIntentRules.isHandledMediaKey(it.keyCode) } != true
        if (kind == StartIntentRules.Kind.FOREIGN || malformed) {
            // Already in the foreground: the system has its notification and Media3 ignores what it does not know.
            if (isInForeground()) return super.onStartCommand(intent, flags, startId)
            Log.w(TAG, "Stopping after a foreign or malformed start: ${intent.action}")
            stopSelfSafely(startId)
            return Service.START_NOT_STICKY
        }
        val result = super.onStartCommand(intent, flags, startId)
        scheduleStartCheck(kind)
        return result
    }

    /**
     * New shuffle and repeat buttons for the notification and the system controls (§3.7). The system drops
     * notification updates that come faster than five a second, as they do when playback starts, so the notification
     * is posted again a second later: otherwise the buttons could keep the old mode.
     */
    @OptIn(UnstableApi::class)
    private fun showButtons(buttons: List<CommandButton>) {
        session?.setMediaButtonPreferences(buttons)
        handler.removeCallbacks(postNotificationAgain)
        handler.postDelayed(postNotificationAgain, NOTIFICATION_AGAIN_MS)
    }

    @OptIn(UnstableApi::class)
    private fun postNotification() = triggerNotificationUpdate()

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
        saver?.saveNow()
        val player = session?.player
        val playing = player != null && player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (!playing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(postNotificationAgain)
        controls?.release()
        controls = null
        effects?.release()
        effects = null
        handler.removeCallbacks(startCheck)
        saver?.saveNow()
        saver?.stop()
        saver = null
        session?.let { session ->
            val player = session.player
            session.release()
            player.release()
        }
        session = null
        callback?.release()
        callback = null
        isRunningForTests = false
        super.onDestroy()
    }

    /**
     * The sleep timer's and "stop after current"'s state goes to PiPlayer's own controllers only (§6.5): the session-wide
     * extras would also reach the platform session, which other apps read.
     */
    @OptIn(UnstableApi::class)
    private fun publishControls(extras: Bundle) {
        val session = session ?: return
        session.connectedControllers
            .filter { it.uid == Process.myUid() && !session.isMediaNotificationController(it) }
            .forEach { session.setSessionExtras(it, extras) }
    }

    /** An announcement of PiPlayer's audio [sessionId] to effect apps (§6.6); Android lets it reach them in the background. */
    private fun effectIntent(action: String, sessionId: Int) = Intent(action)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)

    /** What tapping the notification does: bring PiPlayer to the front, as its launcher icon would. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        checkNotNull(packageManager.getLaunchIntentForPackage(packageName)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun Intent.keyEvent(): KeyEvent? = runCatching {
        IntentCompat.getParcelableExtra(this, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    }.getOrNull()

    /**
     * Whether the service is really in the foreground now. Media3's own flag is never reset after its ten-minute
     * timeout, so the notification is asked instead (docs/playback.md §4.6).
     */
    private fun isInForeground(): Boolean =
        getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        }

    /**
     * Whether [kind] is a start that the system expects to enter the foreground. A media button and Media3's own start
     * come through startForegroundService; a notification action is sent with a plain startService and has no deadline.
     * A media button that ended paused, stopped on a broken song or found no queue would never get a notification, so it
     * is stopped before the deadline instead.
     */
    private fun needsForeground(kind: StartIntentRules.Kind): Boolean {
        val player = session?.player ?: return true
        val active = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        return when (kind) {
            // A self start is only sent when Media3 is putting a playing service into the foreground.
            StartIntentRules.Kind.SELF_START, StartIntentRules.Kind.MEDIA_BUTTON -> !active
            StartIntentRules.Kind.CUSTOM_ACTION -> player.mediaItemCount == 0
            StartIntentRules.Kind.FOREIGN -> true
        }
    }

    private fun scheduleStartCheck(kind: StartIntentRules.Kind) {
        startCheckKind = kind
        handler.removeCallbacks(startCheck)
        handler.postDelayed(startCheck, START_CHECK_MS)
    }

    /**
     * Enters the foreground with a plain notification and leaves it at once, so that a start the system would otherwise
     * kill the app for ends without a crash; Media3 does the same for an action it cannot resolve.
     */
    private fun stopSelfSafely(startId: Int) {
        if (startId != lastStartId) return
        handler.removeCallbacks(startCheck)
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                SHUTDOWN_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not enter the foreground to stop the service safely", e)
        } finally {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun ensureNotificationChannel() {
        val notifications = getSystemService(NotificationManager::class.java)
        if (notifications.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            notifications.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "playback"

        /** After the system's window for notification updates (§3.7). */
        private const val NOTIFICATION_AGAIN_MS = 1_100L

        /** Before the system's five-second deadline for a started foreground service (§4.6). */
        private const val START_CHECK_MS = 4_000L

        /** The plain notification of a safe stop; the media notification has another ID. */
        private const val SHUTDOWN_NOTIFICATION_ID = 1_002

        /** In the extras of the message sent when playback stops: how many songs failed in a row. */
        const val EXTRA_FAILURES = "io.github.lorddetson.piplayer.FAILURES"

        private const val TAG = "PlaybackService"

        /** For tests that stop the service and wait until it is gone; the service outlives a test otherwise. */
        @VisibleForTesting
        @Volatile
        var isRunningForTests = false
            private set
    }
}
