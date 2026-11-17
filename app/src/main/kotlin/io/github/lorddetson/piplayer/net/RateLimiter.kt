package io.github.lorddetson.piplayer.net

import java.io.IOException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The next request may start only after [remaining], longer than the caller was willing to wait. */
class RateLimitPauseException(val remaining: Duration) :
    IOException("The next request may start only in ${remaining.wholeSecondsUp()} s")

/** Whole seconds, rounded up, so that a wait of 299.2 s reads as 300 s. */
fun Duration.wholeSecondsUp(): Long = (inWholeMilliseconds + 999) / 1_000

/**
 * The part of a [RateLimiter] that survives a process (TI §8, §9): the time, in milliseconds since the epoch, of the
 * last request the limiter let go. [restore] is asked once, before the first acquire of a process; [persist] is called
 * whenever a request takes its slot. A limiter without one keeps its state in memory only, as before batches (M4a).
 */
interface RateLimitPersistence {

    /** The time of the last request a previous process stored, or null when it stored none. */
    suspend fun restore(): Long?

    /** Stores the time of the last request, overwriting the previous value; a failed write must not fail the acquire. */
    suspend fun persist(lastRequestMillis: Long)
}

/**
 * Lets requests to one host start no closer together than [minInterval]. Callers are served in arrival order; a
 * caller that is cancelled while waiting does not use up a slot. One instance per host is shared by the whole process.
 *
 * With a [persistence] the interval also holds across processes (TI §8): every granted slot stores the time of its
 * request, and a restored last request moves this limiter's next allowed time to at least that time plus
 * [minInterval]. [wallClock] ties the monotonic [timeSource] to the wall-clock milliseconds the persistence uses, so a
 * batch that survived the death of its process does not reset the limit in the next one.
 */
class RateLimiter(
    val minInterval: Duration,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val persistence: RateLimitPersistence? = null,
) {

    /** Fair, so waiting callers go in arrival order. */
    private val queue = Mutex()
    private val lock = Any()
    private var nextAllowed: ComparableTimeMark? = null

    /** Whether the persisted last request has been read; the restore happens on the first [acquire]. */
    private var restored = false

    /**
     * Suspends until the caller may send its request. When the next slot is more than [maxWait] away (a long pause
     * after the server asked to wait), the caller gets [RateLimitPauseException] at once instead, also one that was
     * already waiting when the pause began; the slot is left for others and the pause stays.
     */
    suspend fun acquire(maxWait: Duration = Duration.INFINITE) {
        queue.withLock {
            if (persistence != null && !restored) {
                restored = true
                restoreLastRequest()
            }
            while (true) {
                var grantedAt: Long? = null
                val wait = synchronized(lock) {
                    val now = timeSource.markNow()
                    val next = nextAllowed
                    if (next == null || next <= now) {
                        nextAllowed = now + minInterval
                        if (persistence != null) grantedAt = wallClock()
                        Duration.ZERO
                    } else {
                        next - now
                    }
                }
                if (wait == Duration.ZERO) {
                    // The write happens before the request goes out, so a process restart cannot reset the interval.
                    val at = grantedAt
                    if (persistence != null && at != null) persistence.persist(at)
                    return
                }
                if (wait > maxWait) throw RateLimitPauseException(wait)
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

    /** Moves [nextAllowed] to the restored last request plus [minInterval], when that lies in the future. */
    private suspend fun restoreLastRequest() {
        val store = persistence ?: return
        val lastRequest = store.restore() ?: return
        val until = synchronized(lock) {
            val remaining = lastRequest + minInterval.inWholeMilliseconds - wallClock()
            if (remaining <= 0) null else timeSource.markNow() + remaining.milliseconds
        } ?: return
        synchronized(lock) {
            // A pause set before the first acquire stays: the restore never shortens the limiter.
            val next = nextAllowed
            if (next == null || next < until) nextAllowed = until
        }
    }
}