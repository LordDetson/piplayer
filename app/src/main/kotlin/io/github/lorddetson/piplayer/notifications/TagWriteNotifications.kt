package io.github.lorddetson.piplayer.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lorddetson.piplayer.R

internal object TagWriteNotifications {
    private const val NOTIFICATION_ID = 2_002
    const val APPLY_ID = 2_003
    private const val BATCH_RECOVERY_ID = 2_004

    fun applying(context: Context): Notification = builder(context)
        .setContentTitle(context.getString(R.string.batch_tag_write_progress))
        .setContentText(context.getString(R.string.batch_tag_write_title))
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()

    fun showRecovery(context: Context, pending: Boolean) = showRecovery(context, NOTIFICATION_ID, pending)

    fun showBatchRecovery(context: Context, pending: Boolean) = showRecovery(context, BATCH_RECOVERY_ID, pending)

    @SuppressLint("MissingPermission") // The common permission check covers POST_NOTIFICATIONS.
    private fun showRecovery(context: Context, id: Int, pending: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!pending) {
            manager.cancel(id)
            return
        }
        if (!BatchNotifications.notificationsAllowed(context)) return
        val notification = builder(context)
            .setContentTitle(context.getString(R.string.tag_recovery_notification_title))
            .setContentText(context.getString(R.string.tag_recovery_notification_text))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    private fun builder(context: Context): NotificationCompat.Builder {
        BatchNotifications.ensureChannel(context)
        return NotificationCompat.Builder(context, BatchNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(BatchNotifications.openFix(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
    }
}
