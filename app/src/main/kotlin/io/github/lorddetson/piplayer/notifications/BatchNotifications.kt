package io.github.lorddetson.piplayer.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.ui.Places

/**
 * The notifications of a batch (TI §8, PIM-21), apart from the playback service's (docs/playback.md §4):
 *
 * - a channel of their own, `batches`, made when the first batch posts, so that a listener hears the batches without
 *   touching playback and a device that never starts a batch has no such channel;
 * - the progress of the batch that goes on, updated as its jobs report their files;
 * - the notification that says the batch waits for review, a tap of which opens the Fix tab ([Places.FIX]).
 *
 * These are plain notifications, never a foreground service: WorkManager runs the batch's jobs and the notification is
 * only its window. Without the notification permission (API 33 and later) nothing is posted and the batch runs the same,
 * with its progress in the Fix tab.
 */
object BatchNotifications {

    /** The batches' channel (TI §8); the playback service keeps `playback` (docs/playback.md §4.6). */
    const val CHANNEL_ID = "batches"

    /** The ID of the one batch notification; the playback service's own IDs stay out of its way. */
    const val NOTIFICATION_ID = 2_001

    /** The request code of the intent a tap sends. */
    private const val REQUEST_CODE = 2_001

    private const val TAG = "BatchNotifications"

    /** Creates the batches' channel unless the device has one; the first notification that posts calls it. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.batch_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.batch_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** The notification of a batch that goes on (TI §8): its files done of its files in all, and the time left. */
    fun progress(context: Context, progress: BatchProgress, eta: Eta, batchUuid: String, paused: Boolean = false): Notification {
        val resources = context.resources
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(pausedTitleOr(context, progress.total, paused))
            .setContentText(progressText(context, progress, eta))
            .setProgress(progress.total, progress.done, progress.fraction == null)
            .setContentIntent(openFix(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                0,
                context.getString(if (paused) R.string.fix_batch_resume else R.string.fix_batch_pause),
                control(context, if (paused) ACTION_RESUME else ACTION_PAUSE, batchUuid),
            )
            .addAction(0, context.getString(R.string.fix_batch_stop), control(context, ACTION_STOP, batchUuid))
            .build()
    }

    /** The notification of a batch the user paused (TI §8): it keeps its place until Resume or Stop is tapped. */
    fun paused(context: Context, batchUuid: String, filesTotal: Int): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.batch_notification_paused_title))
        .setContentText(context.resources.getQuantityString(R.plurals.batch_notification_files, filesTotal, 0, filesTotal))
        .setContentIntent(openFix(context))
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .addAction(0, context.getString(R.string.fix_batch_resume), control(context, ACTION_RESUME, batchUuid))
        .addAction(0, context.getString(R.string.fix_batch_stop), control(context, ACTION_STOP, batchUuid))
        .build()

    private fun pausedTitleOr(context: Context, files: Int, paused: Boolean): String =
        if (paused) context.getString(R.string.batch_notification_paused_title) else
            context.resources.getQuantityString(R.plurals.batch_notification_title, files, files)

    /** The notification of a batch that reached review (TI §8): a tap opens the Fix tab, which holds the review queue. */
    fun finished(context: Context, filesTotal: Int): Notification {
        val resources = context.resources
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.batch_notification_done_title))
            .setContentText(resources.getQuantityString(R.plurals.batch_notification_done_text, filesTotal, filesTotal))
            .setContentIntent(openFix(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
    }

    /** The line under the title of the progress notification (TI §8): the files done, then the time left. */
    fun progressText(context: Context, progress: BatchProgress, eta: Eta): String {
        val files = context.resources.getQuantityString(
            R.plurals.batch_notification_files,
            progress.total,
            progress.done,
            progress.total,
        )
        return listOf(files, etaText(context, eta)).joinToString(" · ")
    }

    /** The time left of a batch in the words of the notification (TI §8). */
    fun etaText(context: Context, eta: Eta): String = when (eta) {
        Eta.Unknown -> context.getString(R.string.batch_notification_eta_unknown)
        Eta.LessThanAMinute -> context.getString(R.string.batch_notification_eta_less_than_a_minute)
        is Eta.Minutes -> context.getString(R.string.batch_notification_eta_minutes, eta.minutes)
        Eta.OverAnHour -> context.getString(R.string.batch_notification_eta_over_an_hour)
    }

    /** Shows [notification] as the batch's; nothing at all while the user allows no notifications (TI §8). */
    @SuppressLint("MissingPermission") // notificationsAllowed below checks POST_NOTIFICATIONS on API 33 and later.
    fun post(context: Context, notification: Notification) {
        ensureChannel(context)
        if (!notificationsAllowed(context)) return
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "The batch notification was refused", e)
        }
    }

    /** Takes the batch's notification away: a cancelled batch, or one whose jobs ended without reaching review. */
    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    /** Whether the app may post: the permission on API 33 and later, and the channel or app not turned off by the user. */
    fun notificationsAllowed(context: Context): Boolean {
        val permission = NotificationPermission.name
        if (permission.isNotEmpty() &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** What a tap opens: PiPlayer at the Fix tab (TI §8), which the activity reads off its intent ([Places]). */
    fun openFix(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE,
        fixIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The intent a tap sends: the activity with the place [Places.FIX], its single top instance coming forward. */
    fun fixIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).putExtra(Places.EXTRA_PLACE, Places.FIX)

    /** What the notification's actions ask for (TI §8, PIM-26): pause, resume or stop the batch [batchUuid]. */
    const val ACTION_PAUSE = "io.github.lorddetson.piplayer.notifications.BATCH_PAUSE"
    const val ACTION_RESUME = "io.github.lorddetson.piplayer.notifications.BATCH_RESUME"
    const val ACTION_STOP = "io.github.lorddetson.piplayer.notifications.BATCH_STOP"

    /** The batch an action names. */
    const val EXTRA_BATCH = "io.github.lorddetson.piplayer.notifications.BATCH"

    /**
     * The broadcast one action sends, to [BatchActionReceiver] for the batch [batchUuid]: a broadcast, not an activity,
     * so that a tap works with the app in the background and does not bring the screen up for a pause.
     */
    fun control(context: Context, action: String, batchUuid: String): PendingIntent {
        val intent = Intent(context, BatchActionReceiver::class.java)
            .setAction(action)
            .setPackage(context.packageName)
            .putExtra(EXTRA_BATCH, batchUuid)
        return PendingIntent.getBroadcast(
            context,
            (action + batchUuid).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
