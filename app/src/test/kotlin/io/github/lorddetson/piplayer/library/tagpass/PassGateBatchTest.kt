package io.github.lorddetson.piplayer.library.tagpass

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** When a batch may decode a fingerprint (TI §8), through the gate the tag pass shares. */
@OptIn(ExperimentalCoroutinesApi::class)
class PassGateBatchTest {

    private fun TestScope.gate() = PassGate(now = { currentTime })

    /** Whether a batch asking the gate now gets through without time passing. */
    private fun TestScope.isBatchOpen(gate: PassGate): Boolean {
        var through = false
        val job = launch {
            gate.awaitBatchOpen()
            through = true
        }
        runCurrent()
        job.cancel()
        return through
    }

    @Test
    fun `it is open with nothing showing and nothing playing`() = runTest {
        assertThat(isBatchOpen(gate())).isTrue()
    }

    @Test
    fun `music playing does not close it, and stopping the music does not open or hold it either`() = runTest {
        val gate = gate()
        gate.setPlaying(true)
        assertThat(isBatchOpen(gate)).isTrue()

        gate.setPlaying(false)
        assertThat(isBatchOpen(gate)).isTrue()
    }

    @Test
    fun `an underrun holds it for a minute`() = runTest {
        val gate = gate()
        gate.audioUnderrun()

        assertThat(isBatchOpen(gate)).isFalse()
        advanceTimeBy(59_000)
        assertThat(isBatchOpen(gate)).isFalse()
        advanceTimeBy(2_000)
        assertThat(isBatchOpen(gate)).isTrue()
    }

    @Test
    fun `another underrun starts the minute again`() = runTest {
        val gate = gate()
        gate.audioUnderrun()
        advanceTimeBy(50_000)

        gate.audioUnderrun()

        advanceTimeBy(50_000)
        assertThat(isBatchOpen(gate)).isFalse()
        advanceTimeBy(11_000)
        assertThat(isBatchOpen(gate)).isTrue()
    }

    @Test
    fun `a hot device holds it until a minute after it has cooled`() = runTest {
        val gate = gate()
        gate.setHot(true)
        advanceTimeBy(600_000)
        assertThat(isBatchOpen(gate)).isFalse()

        gate.setHot(false)
        assertThat(isBatchOpen(gate)).isFalse()
        advanceTimeBy(61_000)
        assertThat(isBatchOpen(gate)).isTrue()
    }

    @Test
    fun `a batch waiting at the gate goes on when the hold ends`() = runTest {
        val gate = gate()
        gate.audioUnderrun()
        var through = false
        launch {
            gate.awaitBatchOpen()
            through = true
        }

        advanceTimeBy(61_000)
        runCurrent()

        assertThat(through).isTrue()
    }
}