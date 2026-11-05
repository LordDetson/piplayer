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

/** When the tag pass may read (docs/library.md §5.5). */
@OptIn(ExperimentalCoroutinesApi::class)
class PassGateTest {

    private fun TestScope.gate() = PassGate(now = { currentTime })

    /** Whether a run asking the gate now gets through without time passing. */
    private fun TestScope.isOpen(gate: PassGate): Boolean {
        var through = false
        val job = launch {
            gate.awaitOpen()
            through = true
        }
        runCurrent()
        job.cancel()
        return through
    }

    @Test
    fun `it is closed while nothing shows and nothing plays`() = runTest {
        assertThat(isOpen(gate())).isFalse()
    }

    @Test
    fun `it is open while the screen shows the app`() = runTest {
        val gate = gate()
        gate.setUiVisible(true)
        assertThat(isOpen(gate)).isTrue()

        gate.setUiVisible(false)
        assertThat(isOpen(gate)).isFalse()
    }

    @Test
    fun `it is open while music plays, with the screen off`() = runTest {
        val gate = gate()
        gate.setPlaying(true)
        assertThat(isOpen(gate)).isTrue()

        gate.setPlaying(false)
        assertThat(isOpen(gate)).isFalse()
    }

    @Test
    fun `a run waiting at the gate goes on when it opens`() = runTest {
        val gate = gate()
        var through = false
        launch {
            gate.awaitOpen()
            through = true
        }
        runCurrent()
        assertThat(through).isFalse()

        gate.setPlaying(true)
        runCurrent()

        assertThat(through).isTrue()
    }

    @Test
    fun `an underrun holds it for a minute`() = runTest {
        val gate = gate()
        gate.setPlaying(true)

        gate.audioUnderrun()

        assertThat(isOpen(gate)).isFalse()
        advanceTimeBy(59_000)
        assertThat(isOpen(gate)).isFalse()
        advanceTimeBy(2_000)
        assertThat(isOpen(gate)).isTrue()
    }

    @Test
    fun `another underrun starts the minute again`() = runTest {
        val gate = gate()
        gate.setPlaying(true)
        gate.audioUnderrun()
        advanceTimeBy(50_000)

        gate.audioUnderrun()

        advanceTimeBy(50_000)
        assertThat(isOpen(gate)).isFalse()
        advanceTimeBy(11_000)
        assertThat(isOpen(gate)).isTrue()
    }

    @Test
    fun `a hot device holds it until a minute after it has cooled`() = runTest {
        val gate = gate()
        gate.setPlaying(true)

        gate.setHot(true)
        advanceTimeBy(600_000)
        assertThat(isOpen(gate)).isFalse()

        gate.setHot(false)
        assertThat(isOpen(gate)).isFalse()
        advanceTimeBy(61_000)
        assertThat(isOpen(gate)).isTrue()
    }

    @Test
    fun `a run waiting out the minute goes on by itself`() = runTest {
        val gate = gate()
        gate.setPlaying(true)
        gate.audioUnderrun()
        var through = false
        launch {
            gate.awaitOpen()
            through = true
        }

        advanceTimeBy(61_000)
        runCurrent()

        assertThat(through).isTrue()
    }

    /** Whether a walk waiting for the gate to close would be told now. */
    private fun TestScope.isClosed(gate: PassGate): Boolean {
        var told = false
        val job = launch {
            gate.awaitClosed()
            told = true
        }
        runCurrent()
        job.cancel()
        return told
    }

    @Test
    fun `a walk hears when the app goes away and the music stops`() = runTest {
        val gate = gate()
        gate.setUiVisible(true)
        assertThat(isClosed(gate)).isFalse()

        gate.setUiVisible(false)
        assertThat(isClosed(gate)).isTrue()
    }

    @Test
    fun `a walk hears of an underrun and of heat while the rest stays open`() = runTest {
        val gate = gate()
        gate.setPlaying(true)
        assertThat(isClosed(gate)).isFalse()

        gate.audioUnderrun()
        assertThat(isClosed(gate)).isTrue()
        advanceTimeBy(60_001)
        assertThat(isClosed(gate)).isFalse()

        gate.setHot(true)
        assertThat(isClosed(gate)).isTrue()
    }

    @Test
    fun `a walk under way is told at the moment the gate closes`() = runTest {
        val gate = gate()
        gate.setUiVisible(true)
        var told = false
        val job = launch {
            gate.awaitClosed()
            told = true
        }
        runCurrent()
        assertThat(told).isFalse()

        gate.audioUnderrun()
        runCurrent()

        assertThat(told).isTrue()
        job.cancel()
    }
}
