package io.github.lorddetson.piplayer.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.StatusBarNotification
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectBrowser
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.holdsWakeLock
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.play
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The playback service as a controller in PiPlayer's own process sees it (docs/playback.md §1.1, §1.2, §1.4). */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {

    private val context = TestFiles.targetContext
    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    @Test
    fun theServiceRunsInTheMainProcess() {
        val service = context.packageManager.getServiceInfo(ComponentName(context, PlaybackService::class.java), 0)

        assertThat(service.processName).isEqualTo(context.packageName)
        assertThat(ProcessRole.current(context)).isEqualTo(ProcessRole.MAIN)
    }

    @Test
    fun aControllerPlaysAFileFromTheStart() {
        controller.play(SHORT_FIXTURE)

        assertThat(onMain { controller.duration }).isCloseTo(2_000L, within(50L))
        assertThat(onMain { controller.currentPosition }).isLessThan(1_000L)
    }

    @Test
    fun aFileCutOffPlaysUpToTheCutThenStops() {
        controller.play("decoding/flac-44100-16bit-mono-truncated.flac")

        controller.waitUntil("playback stops") { playerError != null || playbackState == Player.STATE_ENDED }

        // The file holds 1.95 s of intact audio. The FLAC decoder of API 30 rejects the cut frame, and the audio
        // already handed to the output (up to 0.75 s) is lost with the error; newer decoders end at the cut. Skipping
        // to the next track after an error comes with the queue (step 2).
        val error = onMain { controller.playerError }
        val playedMs = onMain { controller.currentPosition }
        if (error == null) {
            assertThat(playedMs).isGreaterThanOrEqualTo(1_900L)
        } else {
            assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_DECODING_FAILED)
            assertThat(playedMs).isGreaterThanOrEqualTo(1_100L)
        }
    }

    @Test
    fun playingRunsInTheForegroundUnderPiPlayersOwnNotification() {
        controller.play(LONG_FIXTURE)

        var found: StatusBarNotification? = null
        eventually("a foreground media notification is posted") {
            found = mediaNotification()?.takeIf { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }
            found != null
        }
        val notification = found!!.notification
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertThat(notification.channelId).isEqualTo(PlaybackService.NOTIFICATION_CHANNEL_ID)
        assertThat(notifications.getNotificationChannel(notification.channelId).name.toString())
            .isEqualTo(context.getString(R.string.playback_channel_name))
        assertThat(notification.smallIcon.resId).isEqualTo(R.drawable.ic_notification)
        // Tapping it opens PiPlayer.
        assertThat(notification.contentIntent?.creatorPackage).isEqualTo(context.packageName)
    }

    @Test
    fun playbackGoesOnAfterTheControllerLeaves() {
        controller.play(LONG_FIXTURE)
        onMain { controller.release() }

        Thread.sleep(1_500)
        controller = connectController()

        assertThat(onMain { controller.isPlaying }).isTrue()
        assertThat(onMain { controller.currentPosition }).isGreaterThan(1_000L)
    }

    @Test
    fun theWakeLockIsHeldOnlyWhilePlaying() {
        controller.play(LONG_FIXTURE)
        eventually("the wake lock is held") { holdsWakeLock() }

        onMain { controller.pause() }

        eventually("the wake lock is released") { !holdsWakeLock() }
    }

    @Test
    fun theLibraryRootIsAnEmptyFolderUntilTheLibraryArrives() {
        val browser = connectBrowser()
        try {
            val root = onMain { browser.getLibraryRoot(null) }.get(10, TimeUnit.SECONDS)
            assertThat(root.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
            val item = root.value!!
            assertThat(item.mediaId).isEqualTo(PlaybackSessionCallback.ROOT_ID)
            assertThat(item.mediaMetadata.isBrowsable).isTrue()
            assertThat(item.mediaMetadata.isPlayable).isFalse()

            val children = onMain { browser.getChildren(item.mediaId, 0, 100, null) }.get(10, TimeUnit.SECONDS)
            assertThat(children.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
            assertThat(children.value).isEmpty()
        } finally {
            onMain { browser.release() }
        }
    }

    private fun mediaNotification(): StatusBarNotification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
}
