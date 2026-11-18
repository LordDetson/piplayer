package io.github.lorddetson.piplayer.notifications

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackService
import io.github.lorddetson.piplayer.ui.Places
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The batch notification on a device (TI §8, PIM-21): its own channel, the progress it says, and the notification that
 * opens the Fix tab. The system's own drawing and its permission dialog are not automated (TI §11.3).
 */
@RunWith(AndroidJUnit4::class)
class BatchNotificationsTest {

    private val context = TestFiles.targetContext

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun theChannelIsMadeLazilyUnderItsOwnId() {
        assertThat(BatchNotifications.CHANNEL_ID).isNotEqualTo(PlaybackService.NOTIFICATION_CHANNEL_ID)

        BatchNotifications.ensureChannel(context)
        BatchNotifications.ensureChannel(context)

        val channel = manager().getNotificationChannel(BatchNotifications.CHANNEL_ID)
        assertThat(channel).isNotNull()
        assertThat(channel.id).isEqualTo(BatchNotifications.CHANNEL_ID)
        assertThat(channel.name.toString()).isEqualTo(context.getString(R.string.batch_channel_name))
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
        assertThat(manager().notificationChannels.count { it.id == BatchNotifications.CHANNEL_ID }).isEqualTo(1)
    }

    @Test
    fun theProgressNotificationSaysTheFilesAndTheTimeLeft() {
        val notification = BatchNotifications.progress(context, BatchProgress(45, 120), Eta.Minutes(3), "batch-1")
        val extras = notification.extras

        assertThat(notification.channelId).isEqualTo(BatchNotifications.CHANNEL_ID)
        assertThat(extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
            .isEqualTo(context.resources.getQuantityString(R.plurals.batch_notification_title, 120, 120))
        val files = context.resources.getQuantityString(R.plurals.batch_notification_files, 120, 45, 120)
        val eta = context.getString(R.string.batch_notification_eta_minutes, 3)
        assertThat(extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()).isEqualTo("$files · $eta")
        assertThat(extras.getInt(NotificationCompat.EXTRA_PROGRESS_MAX)).isEqualTo(120)
        assertThat(extras.getInt(NotificationCompat.EXTRA_PROGRESS)).isEqualTo(45)
        // It stays while the batch runs, and an update does not buzz the phone again.
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotZero()
        assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotZero()
    }

    @Test
    fun aBatchWithoutAnEstimateStillSaysItsFiles() {
        val notification = BatchNotifications.progress(context, BatchProgress(0, 3), Eta.Unknown, "batch-1")

        val text = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        val files = context.resources.getQuantityString(R.plurals.batch_notification_files, 3, 0, 3)
        assertThat(text).isEqualTo("$files · ${context.getString(R.string.batch_notification_eta_unknown)}")
    }

    @Test
    fun theEndNotificationOpensTheFixTab() {
        val notification = BatchNotifications.finished(context, 120)

        assertThat(notification.channelId).isEqualTo(BatchNotifications.CHANNEL_ID)
        assertThat(notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
            .isEqualTo(context.getString(R.string.batch_notification_done_title))
        assertThat(notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
            .isEqualTo(context.resources.getQuantityString(R.plurals.batch_notification_done_text, 120, 120))
        // A tap opens PiPlayer at the Fix tab, and the notification goes once tapped.
        assertThat(notification.contentIntent).isNotNull()
        assertThat(Places.of(BatchNotifications.fixIntent(context))).isEqualTo(Places.FIX)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isZero()
        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isNotZero()
    }
}
