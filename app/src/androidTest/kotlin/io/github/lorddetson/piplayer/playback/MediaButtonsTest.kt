package io.github.lorddetson.piplayer.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.PlaybackState
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The shuffle and repeat buttons of the notification and the system media controls (docs/playback.md §3.7). */
@RunWith(AndroidJUnit4::class)
class MediaButtonsTest {

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private lateinit var controller: MediaController

    /** Two songs, so that the notification also has a next button. */
    @Before
    fun connect() {
        controller = connectController()
        val items = listOf(fileItem(LONG_FIXTURE), fileItem(LONG_FIXTURE))
        onMain {
            controller.setMediaItems(items)
            controller.prepare()
            controller.play()
        }
        controller.waitUntil("the first song plays") { isPlaying }
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    private fun notificationActions(): List<Notification.Action> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
            ?.notification?.actions.orEmpty().toList()

    private fun notificationTitles() = notificationActions().map { it.title.toString() }

    /** Waits until the notification's last two actions are named [shuffle] and [repeat]. */
    private fun expectNotification(shuffle: Int, repeat: Int) =
        eventually("the notification ends with ${text(shuffle)} and ${text(repeat)}, not ${notificationTitles()}") {
            notificationTitles().takeLast(2) == listOf(text(shuffle), text(repeat))
        }

    /**
     * Presses at once, within the burst of notification updates that playback starts with: the system drops some of
     * them, and the service has to post the notification again (§3.7).
     */
    private fun pressInNotification(title: Int) = notificationActions().single { it.title == text(title) }.actionIntent.send()

    @Test
    fun theNotificationOffersShuffleAndRepeatAfterTheSkipButtons() {
        expectNotification(R.string.playback_shuffle_off, R.string.playback_repeat_off)

        assertThat(notificationActions()).hasSize(5)
    }

    @Test
    fun theNotificationButtonsSwitchShuffleAndGoRoundTheRepeatModes() {
        expectNotification(R.string.playback_shuffle_off, R.string.playback_repeat_off)

        pressInNotification(R.string.playback_shuffle_off)
        controller.waitUntil("shuffle is on") { shuffleModeEnabled }
        expectNotification(R.string.playback_shuffle_on, R.string.playback_repeat_off)

        pressInNotification(R.string.playback_repeat_off)
        controller.waitUntil("repeat all") { repeatMode == Player.REPEAT_MODE_ALL }
        expectNotification(R.string.playback_shuffle_on, R.string.playback_repeat_all)

        pressInNotification(R.string.playback_repeat_all)
        controller.waitUntil("repeat one") { repeatMode == Player.REPEAT_MODE_ONE }
        expectNotification(R.string.playback_shuffle_on, R.string.playback_repeat_one)

        pressInNotification(R.string.playback_repeat_one)
        controller.waitUntil("repeat off") { repeatMode == Player.REPEAT_MODE_OFF }

        pressInNotification(R.string.playback_shuffle_on)
        controller.waitUntil("shuffle is off") { !shuffleModeEnabled }
        expectNotification(R.string.playback_shuffle_off, R.string.playback_repeat_off)
    }

    @Test
    fun theButtonsFollowChangesFromTheApp() {
        onMain {
            controller.shuffleModeEnabled = true
            controller.repeatMode = Player.REPEAT_MODE_ONE
        }

        expectNotification(R.string.playback_shuffle_on, R.string.playback_repeat_one)
    }

    @Test
    fun theSystemMediaControlsGetShuffleAndRepeatAsCustomActions() {
        val connected = CountDownLatch(1)
        val browser = onMain {
            MediaBrowser(context, ComponentName(context, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                }, null).also { it.connect() }
        }
        try {
            assertThat(connected.await(10, TimeUnit.SECONDS)).describedAs("connected").isTrue()
            val platform = onMain { android.media.session.MediaController(context, browser.sessionToken) }
            fun actions(): List<PlaybackState.CustomAction> = onMain { platform.playbackState?.customActions.orEmpty() }
            fun expect(vararg names: Int) = eventually("custom actions ${names.map(::text)}, not ${actions().map { it.name }}") {
                actions().map { it.name.toString() } == names.map(::text)
            }
            fun press(name: Int) {
                val action = actions().single { it.name == text(name) }
                onMain { platform.transportControls.sendCustomAction(action.action, action.extras) }
            }

            expect(R.string.playback_shuffle_off, R.string.playback_repeat_off)
            press(R.string.playback_shuffle_off)
            controller.waitUntil("shuffle is on") { shuffleModeEnabled }
            expect(R.string.playback_shuffle_on, R.string.playback_repeat_off)
            press(R.string.playback_repeat_off)
            controller.waitUntil("repeat all") { repeatMode == Player.REPEAT_MODE_ALL }
            expect(R.string.playback_shuffle_on, R.string.playback_repeat_all)
        } finally {
            onMain { browser.disconnect() }
        }
    }
}
