package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.pipeline.PipelineStage
import io.github.lorddetson.piplayer.spike.SpikeViewModel.State
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The spike screen's runs, one at a time in [scope] (the screen's, on its main thread): [identify] runs on [ioDispatcher]
 * with a callback for its progress. Only the current run changes [state]: a run that was cancelled or replaced keeps going
 * on its thread until its next suspension point, and what it reports meanwhile lands nowhere.
 */
class SpikeRunner(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val identify: suspend (uri: String, onProgress: (PipelineProgress) -> Unit) -> PipelineResult,
) {
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** The current run; read and written on [scope]'s thread only. */
    private var job: Job? = null

    /** Stops a running identification and forgets the last result. */
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = State.Idle
    }

    fun identify(uri: String) {
        job?.cancel()
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            val runScope = this
            mutableState.value = State.Running(PipelineProgress.Started(PipelineStage.LOCAL_READ))
            mutableState.value = try {
                val result = withContext(ioDispatcher) {
                    identify(uri) { progress ->
                        // Back on the screen's thread as a child of the run: once the run is cancelled, the child never
                        // starts, and a run that another replaced is no longer the current one.
                        runScope.launch { if (job === self) mutableState.value = State.Running(progress) }
                    }
                }
                State.Finished(SpikeReports.text(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
        job = run
        run.start()
    }
}
