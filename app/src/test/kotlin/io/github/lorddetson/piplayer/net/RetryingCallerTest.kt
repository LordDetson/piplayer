package io.github.lorddetson.piplayer.net

import io.github.lorddetson.piplayer.fakes.TestServices
import java.io.IOException
import java.net.UnknownHostException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The gate, the stops and the network check in the shared send loop (TI §8, §9). */
class RetryingCallerTest {

    @StartStop
    private val server = MockWebServer()

    private class Unavailable(val attempts: Int, cause: Throwable) : IOException("gave up after $attempts", cause)
    private class Paused : IOException("paused")

    private val interval = 1_100.milliseconds

    private fun TestScope.caller(
        guard: CallGuard = CallGuard {},
        gate: OnlineGate = TestServices.openGate(),
        http: OkHttpClient = HttpClients.create(TestServices.USER_AGENT, gate),
        limiter: RateLimiter = RateLimiter(interval, testTimeSource),
    ) = RetryingCaller(http, limiter, RetryPolicy(maxRetries = 3, random = Random(1)), Dispatchers.IO, guard)

    private suspend fun RetryingCaller.get(): String = execute(
        request = Request.Builder().url(server.url("/")).build(),
        giveUp = { attempts, cause -> Unavailable(attempts, cause) },
        paused = { Paused() },
    ) { response ->
        when {
            response.isSuccessful -> CallOutcome.Success(response.body.string())
            else -> CallOutcome.Retry(IOException("HTTP ${response.code}"))
        }
    }

    private fun ok() = MockResponse.Builder().body("ok").build()

    @Test
    fun `a blocked call sends nothing, is not retried and leaves the limiter free`() = runTest {
        var blocked = true
        var checks = 0
        val caller = caller(guard = CallGuard { checks++; if (blocked) throw GateClosedException() })

        val error = runCatching { caller.get() }.exceptionOrNull()
        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(checks).isEqualTo(1)
        assertThat(server.requestCount).isZero()
        assertThat(testScheduler.currentTime).isZero()

        // No pause and no slot used: the next call goes out at once.
        blocked = false
        server.enqueue(ok())
        assertThat(caller.get()).isEqualTo("ok")
        assertThat(testScheduler.currentTime).isZero()
    }

    @Test
    fun `the client's gate stops a call the guard let through, without a retry or a pause`() = runTest {
        val gate = InMemoryOnlineGate()
        val caller = caller(gate = gate)

        val error = runCatching { caller.get() }.exceptionOrNull()
        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount).isZero()
        assertThat(testScheduler.currentTime).isZero()
    }

    @Test
    fun `the guard is asked again before every attempt`() = runTest {
        var state = NetworkState.VALIDATED
        val caller = caller(
            guard = CallGuard {
                when (state) {
                    NetworkState.VALIDATED -> Unit
                    NetworkState.NO_NETWORK -> throw NoNetworkException()
                    NetworkState.CAPTIVE_PORTAL -> throw CaptivePortalException()
                }
            },
        )
        // The network goes away while the first answer is read.
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                state = NetworkState.NO_NETWORK
                return MockResponse.Builder().code(503).build()
            }
        }

        val error = runCatching { caller.get() }.exceptionOrNull()
        assertThat(error).isInstanceOf(NoNetworkException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `closing the gate during the backoff after a 503 sends no second request`() = runTest {
        // Open at the first attempt; closed from 300 ms on, inside the first backoff (0.5 to 1 s).
        val gate = OnlineGate { testScheduler.currentTime < 300 }
        val caller = caller(guard = CallGuard { if (!gate.isOpen()) throw GateClosedException() }, gate = gate)
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(ok())

        val error = runCatching { caller.get() }.exceptionOrNull()
        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(500)
    }

    @Test
    fun `a failing DNS lookup is retried and given up, and only the backoff pauses the limiter`() = runTest {
        val gate = TestServices.openGate()
        var dnsFails = true
        val http = HttpClients.create(TestServices.USER_AGENT, gate).newBuilder()
            .dns { host -> if (dnsFails) throw UnknownHostException("test: no address for $host") else okhttp3.Dns.SYSTEM.lookup(host) }
            .build()
        val caller = caller(http = http, gate = gate)

        val error = runCatching { caller.get() }.exceptionOrNull()
        assertThat(error).isInstanceOf(Unavailable::class.java)
        assertThat((error as Unavailable).attempts).isEqualTo(4)
        assertThat(error.cause).isInstanceOf(UnknownHostException::class.java)
        assertThat(server.requestCount).isZero()
        // Three backoff waits of 0.5-1 s, 1-2 s and 2-4 s, and nothing after giving up.
        val gaveUpAt = testScheduler.currentTime
        assertThat(gaveUpAt).isBetween(3_500, 7_000)

        dnsFails = false
        server.enqueue(ok())
        assertThat(caller.get()).isEqualTo("ok")
        assertThat((testScheduler.currentTime - gaveUpAt).milliseconds).isLessThanOrEqualTo(interval)
    }

    // What the HTTP layer reports to the trace of the coroutine (TI §10.1).

    @Test
    fun `every attempt sent is reported to the coroutine's trace with the status of its answer`() = runTest {
        val trace = HttpTrace()
        val caller = caller()
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(ok())

        assertThat(withContext(trace) { caller.get() }).isEqualTo("ok")

        val summary = trace.summary()!!
        assertThat(summary.requests).isEqualTo(2)
        assertThat(summary.lastStatus).isEqualTo(200)
        assertThat(summary.elapsedMs).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `an attempt without an answer is reported without a status`() = runTest {
        val gate = TestServices.openGate()
        val http = HttpClients.create(TestServices.USER_AGENT, gate).newBuilder()
            .dns { host -> throw UnknownHostException("test: no address for $host") }
            .build()
        val trace = HttpTrace()

        runCatching { withContext(trace) { caller(http = http, gate = gate).get() } }

        assertThat(trace.summary()).isEqualTo(HttpSummary(requests = 4, lastStatus = null, elapsedMs = 0))
    }

    @Test
    fun `a blocked call reports nothing, and a call outside a trace sends as before`() = runTest {
        val trace = HttpTrace()
        val blocked = caller(guard = CallGuard { throw GateClosedException() })

        runCatching { withContext(trace) { blocked.get() } }
        assertThat(trace.summary()).isNull()

        server.enqueue(ok())
        assertThat(caller().get()).isEqualTo("ok")
    }

    @Test
    fun `cancelling a call while its body is read ends it at once, closes the call and pauses nothing`(): Unit = runBlocking {
        // Real time: a body of 64 bytes at one byte each 500 ms takes 32 s to read.
        server.enqueue(MockResponse.Builder().body("x".repeat(64)).throttleBody(1, 500, TimeUnit.MILLISECONDS).build())
        val limiter = RateLimiter(interval)
        // A retry would pause the limiter for 30 to 60 s.
        val caller = RetryingCaller(
            HttpClients.create(TestServices.USER_AGENT, TestServices.openGate()),
            limiter,
            RetryPolicy(maxRetries = 3, baseDelay = 60.seconds, maxDelay = 60.seconds),
            Dispatchers.IO,
            CallGuard {},
        )
        val reading = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val call = scope.async {
                caller.execute(
                    request = Request.Builder().url(server.url("/")).build(),
                    giveUp = { attempts, cause -> Unavailable(attempts, cause) },
                    paused = { Paused() },
                ) { response ->
                    reading.complete(Unit)
                    CallOutcome.Success(response.body.string())
                }
            }
            withTimeout(10_000) { reading.await() }

            call.cancel()
            // At once, not when the body would have been read.
            withTimeout(5_000) { call.join() }

            assertThat(call.isCancelled).isTrue()
            // No backoff pause: the next caller waits at most the interval.
            limiter.acquire(maxWait = 5.seconds)
        } finally {
            scope.cancel()
        }
    }
}
