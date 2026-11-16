package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.pipeline.PipelineStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The spike screen's runs (TI §3 "One run", debug spike): progress reaches the screen only while its run is the
 * current one. A run keeps going on its thread until its next suspension point after it was cancelled, so a callback
 * can still arrive then; here the test calls the run's callback itself after the cancel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpikeRunnerTest {

    private val result = PipelineResult(IdentificationOutcome.Disabled)

    /** The callback each run was given, by its URI. */
    private val callbacks = mutableMapOf<String, (PipelineProgress) -> Unit>()

    private fun TestScope.runner(block: suspend (String, (PipelineProgress) -> Unit) -> PipelineResult) =
        SpikeRunner(backgroundScope, StandardTestDispatcher(testScheduler), block)

    @Test
    fun `a run shows its progress and then its report`() = runTest {
        val done = CompletableDeferred<Unit>()
        val runner = runner { _, onProgress ->
            onProgress(PipelineProgress.Started(PipelineStage.FINGERPRINT))
            done.await()
            result
        }

        runner.identify("content://a")
        runCurrent()

        assertThat(runner.state.value)
            .isEqualTo(SpikeViewModel.State.Running(PipelineProgress.Started(PipelineStage.FINGERPRINT)))
        done.complete(Unit)
        runCurrent()
        assertThat(runner.state.value).isInstanceOf(SpikeViewModel.State.Finished::class.java)
    }

    @Test
    fun `progress of a cancelled run leaves the screen idle`() = runTest {
        val runner = runner { uri, onProgress ->
            callbacks[uri] = onProgress
            awaitCancellation()
        }
        runner.identify("content://a")
        runCurrent()

        runner.cancel()
        callbacks.getValue("content://a")(PipelineProgress.ReleasePages("recording", 2))
        runCurrent()

        assertThat(runner.state.value).isEqualTo(SpikeViewModel.State.Idle)
    }

    @Test
    fun `progress of a replaced run does not overwrite the next run's`() = runTest {
        val runner = runner { uri, onProgress ->
            callbacks[uri] = onProgress
            if (uri == "content://b") onProgress(PipelineProgress.Started(PipelineStage.TEXT_SEARCH))
            awaitCancellation()
        }
        runner.identify("content://a")
        runCurrent()
        runner.identify("content://b")
        runCurrent()

        callbacks.getValue("content://a")(PipelineProgress.ReleasePages("recording", 3))
        runCurrent()

        assertThat(runner.state.value)
            .isEqualTo(SpikeViewModel.State.Running(PipelineProgress.Started(PipelineStage.TEXT_SEARCH)))
    }
}
