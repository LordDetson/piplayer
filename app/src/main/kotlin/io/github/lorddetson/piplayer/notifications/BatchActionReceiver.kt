package io.github.lorddetson.piplayer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.lorddetson.piplayer.identification.batch.BatchControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The notification's Pause, Resume and Stop actions (TI §8, PIM-26). A broadcast receiver, so that a tap changes the
 * batch with the app in the background: it stops the notifier that follows the batch first, so that a late progress
 * post cannot overwrite the paused notification, then changes the batch and tells the user where it stands.
 *
 * Not exported: only PiPlayer's own notifications send these intents (BatchNotifications.control).
 */
class BatchActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uuid = intent.getStringExtra(BatchNotifications.EXTRA_BATCH) ?: return
        val action = intent.action ?: return
        val app = context.applicationContext
        val pending = goAsync()
        // A broadcast may not outlive its receiver by long; the work is a status write and a cancel, all tiny.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val control = BatchControl.of(app)
                when (action) {
                    BatchNotifications.ACTION_PAUSE -> if (control.pause(uuid)) {
                        BatchNotifier.of(app).stopWatching()
                        // The paused notification keeps the way back: Resume and Stop.
                        BatchNotifications.post(app, BatchNotifications.paused(app, uuid))
                    }
                    BatchNotifications.ACTION_RESUME -> if (control.resume(uuid)) BatchNotifier.of(app).started(uuid)
                    BatchNotifications.ACTION_STOP -> if (control.stop(uuid)) {
                        BatchNotifier.of(app).stopWatching()
                        BatchNotifications.cancel(app)
                    }
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