package io.github.lorddetson.piplayer.net

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets requests to one host start no closer together than [minInterval]. Callers are served in arrival order; a
 * caller that is cancelled while waiting does not use up a slot. One instance per host is shared by the whole process.
 */
class RateLimiter(
    val minInterval: Duration,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {

    /** Fair, so waiting callers go in arrival order. */
    private val queue = Mutex()
    private val lock = Any()
    private var nextAllowed: ComparableTimeMark? = null

    /** Suspends until the caller may send its request. */
    suspend fun acquire() {
        queue.withLock {
            while (true) {
                val wait = synchronized(lock) {
                    val now = timeSource.markNow()
                    val next = nextAllowed
                    if (next == null || next <= now) {
                        nextAllowed = now + minInterval
                        Duration.ZERO
                    } else {
                        next - now
                    }
                }
                if (wait == Duration.ZERO) return
                // Checked again after the wait: a pause may have moved the next slot meanwhile.
                delay(wait)
            }
        }
    }

    /** Keeps every caller, including those already waiting, from sending for at least [duration] from now. */
    fun pause(duration: Duration) {
        synchronized(lock) {
            val until = timeSource.markNow() + duration
            val next = nextAllowed
            if (next == null || next < until) nextAllowed = until
        }
    }
}
