package io.github.lorddetson.piplayer.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.shell
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The platform session (the notification, the lock screen, Bluetooth) never gets the queue (docs/playback.md §2.3),
 * also when a media button resumes playback after the process died (§4.5). Read from `dumpsys media_session`, so that
 * no extra controller connects.
 */
@RunWith(AndroidJUnit4::class)
class PlatformQueueTest {

    private val songs = SongSourceRule {
        listOf(
            SongSourceRule.song(1, "Alpha", LONG_FIXTURE),
            SongSourceRule.song(2, "Bravo", LONG_FIXTURE),
            SongSourceRule.song(3, "Charlie", LONG_FIXTURE),
        )
    }
    private val store = PlaybackStateStore.get(TestFiles.targetContext)
    private val context = TestFiles.targetContext

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(songs)

    @Before
    fun stopService() {
        context.stopService(Intent(context, PlaybackService::class.java))
        eventually("the service is not running", timeout = 10.seconds) { !PlaybackService.isRunningForTests }
        runBlocking { store.clear() }
    }

    @After
    fun cleanUp() {
        connectController().stopAndRelease()
        runBlocking { store.clear() }
    }

    @Test
    fun aQueueStartedFromTheAppIsNotPublished() {
        val controller: MediaController = connectController()
        try {
            controller.tap(QueueRequest(startSongId = 1, startRow = 0))
            controller.waitUntil("the queue plays") { isPlaying && mediaItemCount == 3 }

            expectPlayingWithoutAQueue()
        } finally {
            // A controller left connected would keep the service bound for the tests after this one.
            onMain { controller.release() }
        }
    }

    @Test
    fun aQueueResumedByAMediaButtonIsNotPublished() {
        writeSavedQueue()

        // As after process death: the system hands the button to the service, which nothing is bound to.
        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(Intent.ACTION_MEDIA_BUTTON)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)),
        )

        expectPlayingWithoutAQueue()
    }

    @Test
    fun aQueueResumedInAServiceTheSystemRestartedIsNotPublished() {
        writeSavedQueue()
        // As when the system starts the service again after its process was killed while playing: the service and its
        // session exist, but no controller has asked Media3 for the session. A binding Media3 does not serve does that.
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) = Unit
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val unserved = Intent(context, PlaybackService::class.java).setAction("${context.packageName}.test.UNSERVED")
        assertThat(context.bindService(unserved, connection, Context.BIND_AUTO_CREATE)).isTrue()
        try {
            eventually("the platform session exists", timeout = 10.seconds) { platformSession() != null }
            // The system's media button session is PiPlayer's, so the key reaches it and not the button receiver.
            eventually("PiPlayer has the media buttons", timeout = 10.seconds) {
                "Media button session is ${context.packageName}/" in shell("dumpsys media_session")
            }

            // A headset's play button, which the system hands to the running session.
            shell("input keyevent KEYCODE_MEDIA_PLAY")

            expectPlayingWithoutAQueue()
            eventually("playback runs in the foreground under its notification", timeout = 10.seconds) {
                hasForegroundNotification()
            }
        } finally {
            context.unbindService(connection)
        }
    }

    private fun writeSavedQueue() = runBlocking {
        store.write(
            PlaybackSnapshot(
                queue = QueueRequest(startSongId = 1, startRow = 0),
                songId = 2,
                row = 1,
                positionMs = 1_000,
                shuffle = false,
                repeatMode = Player.REPEAT_MODE_OFF,
                title = "Bravo",
                artist = "Artist",
                album = "Album",
                durationMs = 12_000,
                uri = null,
            ),
        )
    }

    /** Waits until the platform session plays, then watches its queue for a while. */
    private fun expectPlayingWithoutAQueue() {
        eventually("the platform session plays", timeout = 15.seconds) { platformSession()?.state == PLAYING }
        val seen = mutableSetOf<Int>()
        repeat(SAMPLES) {
            platformSession()?.let { seen += it.queueSize }
            Thread.sleep(100)
        }
        assertThat(seen).describedAs("queue sizes the platform session published").containsOnly(0)
    }

    private fun hasForegroundNotification(): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        }

    private class PlatformSession(val state: Int, val queueSize: Int)

    private fun platformSession(): PlatformSession? {
        val dump = shell("dumpsys media_session")
        val start = dump.indexOf("package=${context.packageName}").takeIf { it >= 0 } ?: return null
        val record = dump.substring(start, minOf(dump.length, start + 4_000))
        // "state=3" on Android 11, "state=PLAYING(3)" on Android 16 and later.
        val state = Regex("""state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)""").find(record)?.groupValues?.get(1)?.toInt()
        val size = Regex("""queueTitle=[^\n]*, size=(\d+)""").find(record)?.groupValues?.get(1)?.toInt()
        return if (state == null || size == null) null else PlatformSession(state, size)
    }

    private companion object {
        /** The platform's PlaybackState.STATE_PLAYING. */
        const val PLAYING = 3
        const val SAMPLES = 20
    }
}
