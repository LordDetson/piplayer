package io.github.lorddetson.piplayer.covers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How many covers the provider serves at a time, in all and to one caller (docs/library.md §13.8.4). */
class CoverAdmissionTest {

    @Test
    fun atMostTheLimitAreServedAtATimeAndAFinishedOneMakesRoom() {
        val admission = CoverAdmission(max = 3, perCaller = 3)

        assertThat((1..3).map { admission.tryEnter(uid = it) }).containsOnly(true)
        assertThat(admission.tryEnter(uid = 4)).isFalse()
        assertThat(admission.inFlight).isEqualTo(3)

        admission.exit(uid = 2)

        assertThat(admission.tryEnter(uid = 4)).isTrue()
        assertThat(admission.tryEnter(uid = 5)).isFalse()
    }

    @Test
    fun aCallerOverItsShareIsRefusedWhileAnotherIsServed() {
        val admission = CoverAdmission(max = 64, perCaller = 8)

        assertThat((1..8).map { admission.tryEnter(uid = 10_500) }).containsOnly(true)
        assertThat(admission.tryEnter(uid = 10_500)).isFalse()
        assertThat(admission.tryEnter(uid = 10_600)).isTrue()

        admission.exit(uid = 10_500)

        assertThat(admission.tryEnter(uid = 10_500)).isTrue()
        assertThat(admission.inFlight).isEqualTo(9)
        assertThat(admission.inFlightOf(10_500)).isEqualTo(8)
    }

    @Test
    fun manyThreadsNeverGetMoreThanTheLimitsAndGiveEverythingBack() {
        val admission = CoverAdmission(max = 8, perCaller = 3)
        var most = 0
        var mostOfOne = 0
        val lock = Any()
        val threads = (1..16).map { t ->
            Thread {
                val uid = t % 4
                repeat(2_000) {
                    if (admission.tryEnter(uid)) {
                        synchronized(lock) {
                            most = maxOf(most, admission.inFlight)
                            mostOfOne = maxOf(mostOfOne, admission.inFlightOf(uid))
                        }
                        admission.exit(uid)
                    }
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertThat(most).isBetween(1, 8)
        assertThat(mostOfOne).isBetween(1, 3)
        assertThat(admission.inFlight).isZero()
        assertThat((0..3).map(admission::inFlightOf)).containsOnly(0)
    }
}
