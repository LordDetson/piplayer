package io.github.lorddetson.piplayer.tagging.write

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.notifications.TagWriteNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
private const val PLAN = "io.github.lorddetson.piplayer.tagging.write.APPLY_PLAN"

/** The approved selection lives only in this process. A restart must prepare a new request from the queue. */
@MainThread
internal class BatchTagApply(private val app: PiPlayerApplication) {
    enum class Outcome { FINISHED, PAUSED, FAILED }

    class Run(val group: TagApplyGroup) {
        val stopping = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Outcome>()
        val waitingForPlayback = MutableStateFlow(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active: Run? = null

    fun current(planId: String): Run? = active?.takeIf { it.group.planId == planId }

    fun start(activity: Context, group: TagApplyGroup): Run {
        current(group.planId)?.let { return it }
        check(active == null) { "Another batch is applying tags" }
        val approved = group.files.values.toList()
        require(approved.isNotEmpty())
        val uris = ClipData.newRawUri(null, approved.first())
        for (uri in approved.drop(1)) uris.addItem(ClipData.Item(uri))
        val intent = Intent(activity, BatchTagApplyService::class.java)
            .putExtra(PLAN, group.planId)
            .addFlags(GRANT_FLAGS)
            .apply { clipData = uris }
        val run = Run(group)
        active = run
        try {
            checkNotNull(ContextCompat.startForegroundService(activity, intent))
        } catch (error: RuntimeException) {
            active = null
            throw error
        }
        return run
    }

    fun approvedBy(intent: Intent?): Run? {
        val planId = intent?.getStringExtra(PLAN) ?: return null
        val run = current(planId) ?: return null
        val clip = intent.clipData ?: return null
        if (intent.flags and GRANT_FLAGS != GRANT_FLAGS) return null
        val uris = (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        return run.takeIf { uris == it.group.files.values.toList() }
    }

    fun execute(run: Run, finishService: () -> Unit) {
        scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val writer = app.batchTagWriter
                    writer.markApplyStarted(run.group.planId)
                    writer.applyWhenAvailable(run.group, run.stopping) { run.waitingForPlayback.value = it }
                }
                if (run.stopping.isCompleted) Outcome.PAUSED else Outcome.FINISHED
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not write the approved batch", error)
                Outcome.FAILED
            }
            try {
                val pending = withContext(Dispatchers.IO) { app.batchTagWriter.hasStartedApplyRemaining() }
                TagWriteNotifications.showBatchRecovery(app, pending)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not update the batch recovery notification", error)
            } finally {
                finish(run, outcome)
                finishService()
            }
        }
    }

    fun finish(run: Run, outcome: Outcome) {
        if (active === run) active = null
        run.outcome.complete(outcome)
    }

    private companion object {
        const val TAG = "BatchTagApply"
    }
}

class BatchTagApplyService : Service() {
    private val apply get() = (application as PiPlayerApplication).batchTagApply
    private var running: BatchTagApply.Run? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running != null) return START_NOT_STICKY
        val approved = apply.approvedBy(intent)
        if (approved == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, TagWriteNotifications.APPLY_ID, TagWriteNotifications.applying(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (error: RuntimeException) {
            Log.w("BatchTagApplyService", "Could not start foreground tag writing", error)
            apply.finish(approved, BatchTagApply.Outcome.FAILED)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        running = approved
        apply.execute(approved) {
            running = null
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Finish the file already in progress and start no new ones. Android requires a prompt stopSelf call.
        running?.stopping?.complete(Unit)
        stopSelf()
    }

    override fun onDestroy() {
        running?.stopping?.complete(Unit)
        super.onDestroy()
    }
}
