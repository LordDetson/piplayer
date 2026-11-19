package io.github.lorddetson.piplayer.tagging.write

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TagWriteGateTest {
    @Test
    fun defersThePlayingAndNextUrisAndAnyFileStillOpenForPlayback() {
        val gate = TagWriteGate()
        gate.reservePlayback("playing", "next")
        val previous = gate.beginRead("previous")
        try {
            assertThat(gate.tryWrite("playing")).isNull()
            assertThat(gate.tryWrite("next")).isNull()
            assertThat(gate.tryWrite("previous")).isNull()
            requireNotNull(gate.tryWrite("unrelated")).close()
        } finally {
            previous.close()
        }
        gate.reservePlayback("next", "after next")
        requireNotNull(gate.tryWrite("playing")).close()
        requireNotNull(gate.tryWrite("previous")).close()
        assertThat(gate.tryWrite("after next")).isNull()
    }

    @Test
    fun aNewPlaybackReadWaitsForAnInProgressWriteAndAnUnrelatedReadDoesNot() {
        val gate = TagWriteGate()
        val executor = Executors.newSingleThreadExecutor()
        val write = requireNotNull(gate.tryWrite("writing"))
        try {
            val waiting = CountDownLatch(1)
            val load = executor.submit {
                waiting.countDown()
                gate.beginRead("writing").close()
            }
            assertThat(waiting.await(2, SECONDS)).isTrue()
            assertThatThrownBy { load.get(100, MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            gate.beginRead("unrelated").close()
            write.close()
            load.get(2, SECONDS)
        } finally {
            write.close()
            executor.shutdownNow()
        }
    }
}
