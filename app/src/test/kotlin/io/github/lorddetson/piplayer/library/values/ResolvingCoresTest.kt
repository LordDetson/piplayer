package io.github.lorddetson.piplayer.library.values

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Bulk decoding and resolving leave a core for the writing thread, playback and the interface (TI §10.3). */
class ResolvingCoresTest {

    @Test
    fun `bulk resolving leaves one core free`() = runBlocking<Unit> {
        val cores = Runtime.getRuntime().availableProcessors()
        assumeTrue(cores > 1, "one core has none to leave")
        val running = AtomicInteger()
        val most = AtomicInteger()

        coroutineScope {
            repeat(cores * 3) {
                launch(ResolvingCores.dispatcher) {
                    most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    // Blocking, as decoding and resolving do.
                    Thread.sleep(300)
                    running.decrementAndGet()
                }
            }
        }

        assertThat(ResolvingCores.count).isEqualTo(cores - 1)
        assertThat(most.get()).isBetween(1, cores - 1)
    }
}
