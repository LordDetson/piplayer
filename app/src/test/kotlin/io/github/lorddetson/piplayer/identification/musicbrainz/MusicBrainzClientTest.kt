package io.github.lorddetson.piplayer.identification.musicbrainz

import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.UserAgent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicBrainzClientTest {

    @StartStop
    private val server = MockWebServer()

    private val query = """recording:(paper lanterns) AND artist:(ac\/dc) AND dur:[209000 TO 219000] AND video:false"""
    private val json = requireNotNull(javaClass.getResource("/musicbrainz/recording-search.json")).readText()

    /**
     * [interval] defaults far below the retry waits, so a test that sees a wait knows it came from the backoff and not
     * from the ordinary spacing.
     */
    private fun TestScope.client(
        maxRetries: Int = 3,
        interval: Duration = 10.milliseconds,
        limiter: RateLimiter = RateLimiter(interval, testTimeSource),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = MusicBrainzClient(
        http = HttpClients.create(UserAgent.of("0.1.0")),
        limiter = limiter,
        retryPolicy = RetryPolicy(maxRetries = maxRetries, random = Random(1)),
        baseUrl = server.url("/ws/2/"),
        clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC),
        ioDispatcher = ioDispatcher,
    )

    /** Passes work on to [Dispatchers.IO] and remembers that it was used. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        @Volatile var used = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            used = true
            Dispatchers.IO.dispatch(context, block)
        }
    }

    private fun ok() = MockResponse.Builder().body(json).build()
    private fun status(code: Int) = MockResponse.Builder().code(code).body("""{"error":"busy"}""").build()

    @Test
    fun `searches recordings with the query, the limit and JSON format`() = runTest {
        server.enqueue(ok())
        val response = client().searchRecordings(query, 25)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/ws/2/recording")
        assertThat(request.url.queryParameter("query")).isEqualTo(query)
        assertThat(request.url.queryParameter("limit")).isEqualTo("25")
        assertThat(request.url.queryParameter("fmt")).isEqualTo("json")
        assertThat(request.headers["User-Agent"]).isEqualTo(UserAgent.of("0.1.0"))
        assertThat(response.recordings).hasSize(2)
    }

    @Test
    fun `the query is percent-encoded, so its plus signs and spaces survive`() = runTest {
        server.enqueue(ok())
        client().searchRecordings("recording:(+paper +lanterns)", 25)
        val target = server.takeRequest().target
        assertThat(target).contains("%2Bpaper").doesNotContain(" ").doesNotContain("+paper")
    }

    @Test
    fun `the limit is kept between 1 and 100`() = runTest {
        server.enqueue(ok())
        server.enqueue(ok())
        client().searchRecordings(query, 500)
        client().searchRecordings(query, 0)
        assertThat(server.takeRequest().url.queryParameter("limit")).isEqualTo("100")
        assertThat(server.takeRequest().url.queryParameter("limit")).isEqualTo("1")
    }

    @Test
    fun `a busy answer is retried after a wait`() = runTest {
        server.enqueue(status(503))
        server.enqueue(ok())
        val response = client().searchRecordings(query, 25)
        assertThat(response.recordings).hasSize(2)
        assertThat(server.requestCount).isEqualTo(2)
        // The first retry waits 0.5 to 1 s; the 10 ms spacing alone would retry at once.
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(500)
    }

    @Test
    fun `after giving up on a long Retry-After, later calls fail at once until the pause is over`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "300").build())
        server.enqueue(ok())
        val client = client()
        val first = runCatching { client.searchRecordings(query, 25) }.exceptionOrNull()
        assertThat(first).isInstanceOf(MusicBrainzException.Unavailable::class.java)

        // The pause is longer than any wait a retry takes (120 s), so the next call does not wait it out.
        val second = runCatching { client.searchRecordings(query, 25) }.exceptionOrNull()
        assertThat(second).isInstanceOf(MusicBrainzException.Paused::class.java)
        assertThat(second!!.message).contains("300 s")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(testScheduler.currentTime).isLessThan(1_000)

        advanceTimeBy(300.seconds)
        client.searchRecordings(query, 25)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a Retry-After of 120 s is still waited for, with its jitter`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "120").build())
        server.enqueue(ok())
        val response = client().searchRecordings(query, 25)
        assertThat(response.recordings).hasSize(2)
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(120_000)
    }

    @Test
    fun `an HTTP-date Retry-After is measured from the server's Date header`() = runTest {
        // The server's clock is an hour behind the device's fixed 12:00, so the device clock would see a past date.
        server.enqueue(
            MockResponse.Builder().code(503)
                .addHeader("Date", "Sun, 18 Oct 2026 11:00:00 GMT")
                .addHeader("Retry-After", "Sun, 18 Oct 2026 11:00:30 GMT")
                .build(),
        )
        server.enqueue(ok())
        client().searchRecordings(query, 25)
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(30_000)
    }

    @Test
    fun `the answer is read off the caller's thread`() = runTest {
        server.enqueue(ok())
        val io = RecordingDispatcher()
        client(ioDispatcher = io).searchRecordings(query, 25)
        assertThat(io.used).isTrue()
    }

    @Test
    fun `429, 502 and 504 are retried like 503`() = runTest {
        server.enqueue(status(429))
        server.enqueue(status(502))
        server.enqueue(status(504))
        server.enqueue(ok())
        client().searchRecordings(query, 25)
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `Retry-After is honoured`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "7").build())
        server.enqueue(ok())
        client().searchRecordings(query, 25)
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(7_000)
    }

    @Test
    fun `gives up after the last retry`() = runTest {
        repeat(4) { server.enqueue(status(503)) }
        val error = runCatching { client(maxRetries = 3).searchRecordings(query, 25) }.exceptionOrNull()
        assertThat(error).isInstanceOf(MusicBrainzException.Unavailable::class.java)
        assertThat((error as MusicBrainzException.Unavailable).attempts).isEqualTo(4)
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `a rejected User-Agent fails at once`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).addHeader("X-RateLimit-Who", "ua-blocked").build())
        val error = runCatching { client().searchRecordings(query, 25) }.exceptionOrNull()
        assertThat(error).isInstanceOf(MusicBrainzException.UserAgentRejected::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `other errors are not retried`() = runTest {
        server.enqueue(status(400))
        val error = runCatching { client().searchRecordings(query, 25) }.exceptionOrNull()
        assertThat(error).isInstanceOf(MusicBrainzException.Http::class.java)
        assertThat((error as MusicBrainzException.Http).code).isEqualTo(400)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `requests in a row respect the rate limit`() = runTest {
        server.enqueue(ok())
        server.enqueue(ok())
        val client = client(interval = 1_100.milliseconds)
        client.searchRecordings(query, 25)
        client.searchRecordings(query, 25)
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(1_100)
    }
}
