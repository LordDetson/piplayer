package io.github.lorddetson.piplayer.notifications

import android.content.Context
import io.github.lorddetson.piplayer.identification.batch.BatchControl

/**
 * What a user's Pause, Resume and Stop do to a batch, wherever the tap came from (TI §8, PIM-26): the Fix tab's menu and
 * the notification's actions end in the same place, so the batch's state and its notification never disagree.
 *
 * The notification is stopped before the batch changes, so that a late progress post cannot overwrite the paused one;
 * a resumed batch is followed again, since its jobs were cancelled when it was paused.
 */
object BatchUserActions {

    suspend fun pause(context: Context, batchUuid: String): Boolean {
        if (!BatchControl.of(context).pause(batchUuid)) return false
        BatchNotifier.of(context).stopWatching()
        BatchNotifications.post(context, BatchNotifications.paused(context, batchUuid))
        return true
    }

    suspend fun resume(context: Context, batchUuid: String): Boolean {
        if (!BatchControl.of(context).resume(batchUuid)) return false
        BatchNotifier.of(context).started(batchUuid)
        return true
    }

    suspend fun stop(context: Context, batchUuid: String): Boolean {
        if (!BatchControl.of(context).stop(batchUuid)) return false
        BatchNotifier.of(context).stopWatching()
        BatchNotifications.cancel(context)
        return true
    }
}