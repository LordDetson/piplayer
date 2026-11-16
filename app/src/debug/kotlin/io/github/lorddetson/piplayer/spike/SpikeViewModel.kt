package io.github.lorddetson.piplayer.spike

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * Runs the identification pipeline on a picked file through [SpikeServices], one run at a time ([SpikeRunner]). The work
 * survives configuration changes; closing the screen cancels it ([cancel]), and so does the end of the screen's owner.
 */
class SpikeViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Idle : State

        /** The run goes on; [progress] is what it reported last. */
        data class Running(val progress: PipelineProgress) : State

        data class Finished(val text: String) : State

        data class Failed(val error: String) : State
    }

    private val runner = SpikeRunner(viewModelScope) { uri, onProgress ->
        // Off the main thread: the services build their HTTP client when first asked.
        SpikeServices.run(uri, SpikeServices.pipeline(getApplication()), onProgress)
    }

    val state: StateFlow<State> = runner.state

    /** Stops a running identification and forgets the last result. */
    fun cancel() = runner.cancel()

    fun identify(uri: Uri) = runner.identify(uri.toString())
}
