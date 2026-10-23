package io.github.lorddetson.piplayer.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A started foreground service that never enters the foreground is killed by the system after five seconds. Foreign or
 * malformed starts, and a play request with no saved queue, must end before that (docs/playback.md §4.6).
 */
@RunWith(AndroidJUnit4::class)
class ForeignServiceStartTest {

    private val context = TestFiles.targetContext
    private val store = PlaybackStateStore.get(context)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val component = "${context.packageName}/${PlaybackService::class.java.name}"

    @Before
    fun stopService() {
        context.stopService(Intent(context, PlaybackService::class.java))
        eventually("the service is not running", timeout = 10.seconds) { !PlaybackService.isRunningForTests }
        runBlocking { store.clear() }
    }

    @After
    fun cleanUp() {
        context.stopService(Intent(context, PlaybackService::class.java))
        runBlocking { store.clear() }
    }

    @Test
    fun anUnknownActionIsStoppedSafely() {
        val output = shell("am start-foreground-service -n $component -a com.example.FOREIGN")
        assertThat(output).doesNotContain("Error")

        Thread.sleep(1_000)
        eventually("the foreign service is gone", timeout = 10.seconds) { !PlaybackService.isRunningForTests }
        Thread.sleep(THE_SYSTEM_DEADLINE_MS)
        assertThat(PlaybackService.isRunningForTests).isFalse()
    }

    @Test
    fun aMalformedMediaButtonStartIsStoppedSafely() {
        val output = shell("am start-foreground-service -n $component -a android.intent.action.MEDIA_BUTTON")
        assertThat(output).doesNotContain("Error")

        Thread.sleep(1_000)
        eventually("the malformed service is gone", timeout = 10.seconds) { !PlaybackService.isRunningForTests }
        Thread.sleep(THE_SYSTEM_DEADLINE_MS)
        assertThat(PlaybackService.isRunningForTests).isFalse()
    }

    @Test
    fun aPlayStartWithNoSavedQueueIsStoppedSafely() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            context.startForegroundService(
                Intent(context, PlaybackService::class.java)
                    .setAction(Intent.ACTION_MEDIA_BUTTON)
                    .putExtra(
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY),
                    ),
            )
            Thread.sleep(500)
            assertThat(PlaybackService.isRunningForTests).isTrue()
        } finally {
            scenario.close()
        }

        eventually("the empty play start is gone", timeout = 10.seconds) { !PlaybackService.isRunningForTests }
        Thread.sleep(THE_SYSTEM_DEADLINE_MS)
        assertThat(PlaybackService.isRunningForTests).isFalse()
    }

    @Test
    fun aForeignStartWhilePlayingDoesNotStopPlayback() {
        val controller: MediaController = connectController()
        try {
            onMain {
                controller.setMediaItem(fileItem(LONG_FIXTURE))
                controller.prepare()
                controller.play()
            }
            controller.waitUntil("the song plays") { isPlaying }
            eventually("the media notification is in the foreground", timeout = 10.seconds) {
                hasForegroundNotification()
            }

            val output = shell("am start-foreground-service -n $component -a com.example.FOREIGN")
            assertThat(output).doesNotContain("Error")
            Thread.sleep(1_500)

            assertThat(onMain { controller.isPlaying }).isTrue()
        } finally {
            controller.stopAndRelease()
        }
    }

    private fun hasForegroundNotification(): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        }

    /** Runs an `am` command as the shell user, as another app or a person with adb could. */
    private fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }

    private companion object {
        /** The system's window for a started foreground service; a crash would end this test's process. */
        const val THE_SYSTEM_DEADLINE_MS = 5_000L
    }
}
