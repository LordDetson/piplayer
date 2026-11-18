package io.github.lorddetson.piplayer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The notification's Pause, Resume and Stop actions (TI §8, PIM-26). A broadcast receiver, so that a tap changes the
 * batch with the app in the background; the work itself is [BatchUserActions], which the Fix tab uses too.
 *
 * Not exported: only PiPlayer's own notifications send these intents (BatchNotifications.control).
 */
class BatchActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uuid = intent.getStringExtra(BatchNotifications.EXTRA_BATCH) ?: return
        val action = intent.action ?: return
        val app = context.applicationContext
        val pending = goAsync()
        // A broadcast may not outlive its receiver for long; this is a status write, a cancel and a notification.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    BatchNotifications.ACTION_PAUSE -> BatchUserActions.pause(app, uuid)
                    BatchNotifications.ACTION_RESUME -> BatchUserActions.resume(app, uuid)
                    BatchNotifications.ACTION_STOP -> BatchUserActions.stop(app, uuid)
                }
            } catch (e: Exception) {
                Log.w(TAG, "The batch action $action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BatchActionReceiver"
    }
}