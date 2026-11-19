package io.github.lorddetson.piplayer.tagging.write

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TagWriteGateTest {
    @Test
    fun volumeSpecificWritesWaitForPlaybackThroughTheExternalCollection() = runBlocking<Unit> {
        val gate = TagWriteGate()
        val playing = "content://media/external/audio/media/42"
        val next = "content://media/external/audio/media/43"
        val primary = "content://media/external_primary/audio/media/42"
        val card = "content://media/0000-0000/audio/media/43"
        gate.reservePlayback(playing, next)
        assertThat(gate.tryWrite(primary)).isNull()
        assertThat(gate.tryWrite(card)).isNull()
        requireNotNull(gate.tryWrite("content://media/internal/audio/media/42")).close()

        val ready = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitAnyWritable(setOf(primary, card)) }
        assertThat(ready.isActive).isTrue()
        val previous = gate.beginRead(playing)
        gate.reservePlayback(null, null)
        assertThat(gate.tryWrite(primary)).isNull()
        requireNotNull(gate.tryWrite(card)).close()
        withTimeout(2_000) { ready.await() }
        previous.close()
        requireNotNull(gate.tryWrite(primary)).close()
    }

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
        val write = requireNotNull(gate.tryWrite("content://media/0000-0000/audio/media/42"))
        try {
            val waiting = CountDownLatch(1)
            val load = executor.submit {
                waiting.countDown()
                gate.beginRead("content://media/external/audio/media/42").close()
            }
            assertThat(waiting.await(2, SECONDS)).isTrue()
            assertThatThrownBy { load.get(100, MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            gate.beginRead("content://media/external/audio/media/43").close()
            write.close()
            load.get(2, SECONDS)
        } finally {
            write.close()
            executor.shutdownNow()
        }
    }
}
