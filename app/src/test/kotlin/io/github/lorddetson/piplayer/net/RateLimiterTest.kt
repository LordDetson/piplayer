package io.github.lorddetson.piplayer.net

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {

    private val interval = 1_100.milliseconds

    private fun TestScope.limiter() = RateLimiter(interval, testTimeSource)

    @Test
    fun `the first request goes out at once`() = runTest {
        limiter().acquire()
        assertThat(testScheduler.currentTime).isEqualTo(0)
    }

    @Test
    fun `requests in a row are spaced by the interval`() = runTest {
        val limiter = limiter()
        val starts = List(3) {
            limiter.acquire()
            testScheduler.currentTime
        }
        assertThat(starts).containsExactly(0, 1_100, 2_200)
    }

    @Test
    fun `parallel callers never start closer together than the interval`() = runTest {
        val limiter = limiter()
        val starts = mutableListOf<Long>()
        repeat(5) {
            launch {
                limiter.acquire()
                starts += testScheduler.currentTime
            }
        }
        testScheduler.advanceUntilIdle()
        assertThat(starts).containsExactly(0, 1_100, 2_200, 3_300, 4_400)
    }

    @Test
    fun `no wait once the interval has passed`() = runTest {
        val limiter = limiter()
        limiter.acquire()
        advanceTimeBy(5.seconds)
        limiter.acquire()
        assertThat(testScheduler.currentTime).isEqualTo(5_000)
    }

    @Test
    fun `a pause holds back the next request, also one that is already waiting`() = runTest {
        val limiter = limiter()
        limiter.acquire()
        var started = -1L
        launch {
            limiter.acquire()
            started = testScheduler.currentTime
        }
        runCurrent()
        limiter.pause(5.seconds)
        testScheduler.advanceUntilIdle()
        assertThat(started).isEqualTo(5_000)
    }

    @Test
    fun `a pause shorter than the remaining interval does not shorten it`() = runTest {
        val limiter = limiter()
        limiter.acquire()
        limiter.pause(100.milliseconds)
        limiter.acquire()
        assertThat(testScheduler.currentTime).isEqualTo(1_100)
    }

    @Test
    fun `a caller cancelled while waiting does not use up a slot`() = runTest {
        val limiter = limiter()
        limiter.acquire()
        val cancelled = launch { limiter.acquire() }
        runCurrent()
        cancelled.cancel()
        limiter.acquire()
        assertThat(testScheduler.currentTime).isEqualTo(1_100)
    }
}
