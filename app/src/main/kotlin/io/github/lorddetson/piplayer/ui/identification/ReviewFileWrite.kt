package io.github.lorddetson.piplayer.ui.identification

import android.util.Log
import androidx.activity.result.IntentSenderRequest
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.tagging.write.BatchTagWriter
import io.github.lorddetson.piplayer.tagging.write.ReviewTagPlan
import io.github.lorddetson.piplayer.tagging.write.WriteBackResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ReviewFileWrite(
    private val fileId: Long,
    private val writer: BatchTagWriter,
    private val scope: CoroutineScope,
) {
    data class State(val decisionId: Long? = null, val busy: Boolean = false, val message: Int? = null)

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var awaitingConsent: ReviewTagPlan? = null

    fun start(action: LastAction, launch: (IntentSenderRequest) -> Unit) {
        if (mutable.value.busy) return
        mutable.value = State(decisionId = action.decisionId, busy = true)
        scope.launch {
            runWrite {
                val plan = writer.reviewPlan(fileId, action.decisionId, action.editId)
                if (plan == null) {
                    show(R.string.review_file_write_unavailable)
                    return@runWrite
                }
                val sender = withContext(Dispatchers.IO) { writer.request(plan.file) }
                if (sender == null) {
                    apply(plan)
                } else {
                    awaitingConsent = plan
                    launch(IntentSenderRequest.Builder(sender).build())
                }
            }
        }
    }

    fun consentResult(granted: Boolean) {
        val plan = awaitingConsent ?: return
        awaitingConsent = null
        if (granted) scope.launch { runWrite { apply(plan) } }
        else show(R.string.manual_tag_cancelled)
    }

    private suspend fun apply(plan: ReviewTagPlan) {
        val result = writer.applyReview(plan) { waiting ->
            show(R.string.review_file_write_waiting.takeIf { waiting }, busy = true)
        }
        show(when (result) {
            WriteBackResult.VERIFIED -> R.string.manual_tag_written
            WriteBackResult.SOURCE_CHANGED -> R.string.review_file_write_changed
            else -> R.string.review_file_write_failed
        })
    }

    private suspend fun runWrite(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            awaitingConsent = null
            Log.w("ReviewFileWrite", "Could not write the approved review tags", error)
            show(R.string.review_file_write_failed)
        }
    }

    private fun show(message: Int?, busy: Boolean = false) {
        mutable.value = mutable.value.copy(busy = busy, message = message)
    }
}
