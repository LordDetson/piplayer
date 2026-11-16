package io.github.lorddetson.piplayer.spike

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineStage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the identification pipeline on a picked file through [SpikeServices]. The work survives configuration changes;
 * closing the screen cancels it ([cancel]), and so does the end of the screen's owner.
 */
class SpikeViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Idle : State

        /** The run goes on; [progress] is what it reported last. */
        data class Running(val progress: PipelineProgress) : State

        data class Finished(val text: String) : State

        data class Failed(val error: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Stops a running identification and forgets the last result. */
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = State.Idle
    }

    fun identify(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            mutableState.value = State.Running(PipelineProgress.Started(PipelineStage.LOCAL_READ))
            mutableState.value = try {
                // Off the main thread: the services build their HTTP client when first asked.
                val result = withContext(Dispatchers.IO) {
                    val pipeline = SpikeServices.pipeline(getApplication())
                    SpikeServices.run(uri.toString(), pipeline) { mutableState.value = State.Running(it) }
                }
                State.Finished(SpikeReports.text(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
