package io.github.lorddetson.piplayer.notifications

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lorddetson.piplayer.R

internal object TagWriteNotifications {
    private const val NOTIFICATION_ID = 2_002

    @SuppressLint("MissingPermission") // The common permission check covers POST_NOTIFICATIONS.
    fun showRecovery(context: Context, pending: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!pending) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!BatchNotifications.notificationsAllowed(context)) return
        BatchNotifications.ensureChannel(context)
        val notification = NotificationCompat.Builder(context, BatchNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.tag_recovery_notification_title))
            .setContentText(context.getString(R.string.tag_recovery_notification_text))
            .setContentIntent(BatchNotifications.openFix(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
