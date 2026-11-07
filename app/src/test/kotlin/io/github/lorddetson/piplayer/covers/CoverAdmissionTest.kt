package io.github.lorddetson.piplayer.covers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How many covers the provider serves at a time (docs/library.md §13.8.4). */
class CoverAdmissionTest {

    @Test
    fun atMostTheLimitAreServedAtATimeAndAFinishedOneMakesRoom() {
        val admission = CoverAdmission(max = 3)

        assertThat((1..3).map { admission.tryEnter() }).containsOnly(true)
        assertThat(admission.tryEnter()).isFalse()
        assertThat(admission.inFlight).isEqualTo(3)

        admission.exit()

        assertThat(admission.tryEnter()).isTrue()
        assertThat(admission.tryEnter()).isFalse()
    }

    @Test
    fun manyThreadsNeverGetMoreThanTheLimit() {
        val admission = CoverAdmission(max = 8)
        var most = 0
        val lock = Any()
        val threads = (1..16).map {
            Thread {
                repeat(2_000) {
                    if (admission.tryEnter()) {
                        synchronized(lock) { most = maxOf(most, admission.inFlight) }
                        admission.exit()
                    }
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertThat(most).isBetween(1, 8)
        assertThat(admission.inFlight).isZero()
    }
}
