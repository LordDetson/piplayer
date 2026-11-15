package io.github.lorddetson.piplayer.identification.musicbrainz

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.provider.ProviderAccess
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatus
import io.github.lorddetson.piplayer.identification.provider.ProviderStatusStore
import io.github.lorddetson.piplayer.identification.provider.ProviderStoppedException
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.net.CaptivePortalException
import io.github.lorddetson.piplayer.net.Connectivity
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.NetworkState
import io.github.lorddetson.piplayer.net.NoNetworkException
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.UserAgent
import java.io.IOException
import java.net.UnknownHostException
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
import mockwebserver3.Dispatcher
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import okhttp3.OkHttpClient
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
        gate: OnlineGate = TestServices.openGate(),
        connectivity: Connectivity = TestServices.FakeConnectivity(),
        statuses: ProviderStatusStore = ProviderStatusStore(),
        configureHttp: OkHttpClient.Builder.() -> Unit = {},
    ) = MusicBrainzClient(
        http = HttpClients.create(UserAgent.of("0.1.0"), gate).newBuilder().apply(configureHttp).build(),
        limiter = limiter,
        retryPolicy = RetryPolicy(maxRetries = maxRetries, random = Random(1)),
        access = ProviderAccess(ProviderId.MUSICBRAINZ, gate, connectivity, statuses),
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

    @Test
    fun `nothing is sent while online lookups are off`() = runTest {
        server.enqueue(ok())
        val error = runCatching { client(gate = InMemoryOnlineGate()).searchRecordings(query, 25) }.exceptionOrNull()
        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `a network that is not validated sends nothing`() = runTest {
        server.enqueue(ok())
        val offline = TestServices.FakeConnectivity(NetworkState.NO_NETWORK)
        assertThat(runCatching { client(connectivity = offline).searchRecordings(query, 25) }.exceptionOrNull())
            .isInstanceOf(NoNetworkException::class.java)
        val portal = TestServices.FakeConnectivity(NetworkState.CAPTIVE_PORTAL)
        assertThat(runCatching { client(connectivity = portal).searchRecordings(query, 25) }.exceptionOrNull())
            .isInstanceOf(CaptivePortalException::class.java)
        assertThat(server.requestCount).isZero()
        assertThat(testScheduler.currentTime).isZero()
    }

    @Test
    fun `a rejected User-Agent stops MusicBrainz for the session`() = runTest {
        server.enqueue(FakeMusicBrainz.userAgentRejected())
        server.enqueue(ok())
        val statuses = ProviderStatusStore()
        val client = client(statuses = statuses)

        assertThat(runCatching { client.searchRecordings(query, 25) }.exceptionOrNull())
            .isInstanceOf(MusicBrainzException.UserAgentRejected::class.java)
        assertThat(statuses.status(ProviderId.MUSICBRAINZ).value)
            .isEqualTo(ProviderStatus.Stopped(StopReason.USER_AGENT_REJECTED))

        val later = runCatching { client.searchRecordings(query, 25) }.exceptionOrNull()
        assertThat(later).isInstanceOf(ProviderStoppedException::class.java)
        assertThat((later as ProviderStoppedException).reason).isEqualTo(StopReason.USER_AGENT_REJECTED)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a 403 that does not name the User-Agent fails the call only`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).addHeader("X-RateLimit-Who", "ip-10.0.0.1").build())
        server.enqueue(ok())
        val statuses = ProviderStatusStore()
        val client = client(statuses = statuses)

        assertThat(runCatching { client.searchRecordings(query, 25) }.exceptionOrNull())
            .isInstanceOf(MusicBrainzException.Http::class.java)
        assertThat(statuses.status(ProviderId.MUSICBRAINZ).value).isEqualTo(ProviderStatus.Active)
        assertThat(client.searchRecordings(query, 25).recordings).hasSize(2)
    }

    @Test
    fun `an HTML page with a successful status is retried and then reported as unavailable`() = runTest {
        repeat(4) { server.enqueue(FakeMusicBrainz.CAPTIVE_PORTAL.response()) }
        val error = runCatching { client(maxRetries = 3).searchRecordings(query, 25) }.exceptionOrNull()

        assertThat(error).isInstanceOf(MusicBrainzException.Unavailable::class.java)
        assertThat((error as MusicBrainzException.Unavailable).attempts).isEqualTo(4)
        assertThat(error.cause).isInstanceOf(MusicBrainzException.Unreadable::class.java)
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `a sign-in page once, then the real answer, succeeds`() = runTest {
        server.enqueue(FakeMusicBrainz.CAPTIVE_PORTAL.response())
        server.enqueue(ok())
        assertThat(client().searchRecordings(query, 25).recordings).hasSize(2)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `dropped connections are retried and given up, and only the backoff pauses the limiter`() = runTest {
        var dropping = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (dropping) MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() else ok()
        }
        val client = client(interval = 1_100.milliseconds)

        val error = runCatching { client.searchRecordings(query, 25) }.exceptionOrNull()
        assertThat(error).isInstanceOf(MusicBrainzException.Unavailable::class.java)
        assertThat((error as MusicBrainzException.Unavailable).attempts).isEqualTo(4)
        assertThat(error.cause).isInstanceOf(IOException::class.java)
        val gaveUpAt = testScheduler.currentTime
        assertThat(gaveUpAt).isBetween(3_500, 7_000)

        dropping = false
        client.searchRecordings(query, 25)
        assertThat(testScheduler.currentTime - gaveUpAt).isLessThanOrEqualTo(1_100)
    }

    @Test
    fun `a failing DNS lookup is retried and given up without reaching the server`() = runTest {
        val client = client(configureHttp = { dns { host -> throw UnknownHostException("test: no address for $host") } })
        val error = runCatching { client.searchRecordings(query, 25) }.exceptionOrNull()
        assertThat(error).isInstanceOf(MusicBrainzException.Unavailable::class.java)
        assertThat(error!!.cause).isInstanceOf(UnknownHostException::class.java)
        assertThat(server.requestCount).isZero()
    }
}
