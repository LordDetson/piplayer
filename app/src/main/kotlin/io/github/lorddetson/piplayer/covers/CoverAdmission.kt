package io.github.lorddetson.piplayer.covers

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many covers the provider serves at a time (docs/library.md §13.8.4): each holds two descriptors and a place in the
 * loader's line until it is written, so a caller that opens thousands cannot exhaust the process.
 */
class CoverAdmission(private val max: Int) {

    private val count = AtomicInteger()

    val inFlight: Int get() = count.get()

    /** Takes a place, or false when all [max] are taken. */
    fun tryEnter(): Boolean {
        while (true) {
            val now = count.get()
            if (now >= max) return false
            if (count.compareAndSet(now, now + 1)) return true
        }
    }

    /** Gives a place back. */
    fun exit() {
        count.decrementAndGet()
    }
}
