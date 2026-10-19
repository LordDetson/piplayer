package io.github.lorddetson.piplayer.spike

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the spike on a picked file. The work survives configuration changes; closing the screen cancels it ([cancel]),
 * and so does the end of the screen's owner.
 */
class SpikeViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Idle : State

        data class Running(val fileName: String?, val stage: SpikeStage) : State

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
            mutableState.value = State.Running(null, SpikeStage.READING_TAGS)
            mutableState.value = try {
                val report = withContext(Dispatchers.IO) {
                    OpenedFile.open(getApplication(), uri).use { file ->
                        SpikeServices.spike().run(file) { stage ->
                            mutableState.value = State.Running(file.displayName, stage)
                        }
                    }
                }
                State.Finished(SpikeReports.text(report))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
