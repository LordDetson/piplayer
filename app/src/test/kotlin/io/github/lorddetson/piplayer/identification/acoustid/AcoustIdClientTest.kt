package io.github.lorddetson.piplayer.identification.acoustid

import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.UserAgent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
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
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@OptIn(ExperimentalCoroutinesApi::class)
class AcoustIdClientTest {

    @StartStop
    private val server = MockWebServer()

    private val key = "testKey123"
    private val fingerprint = "AQAAT0mUaEkSRZEGAA"
    private val json = requireNotNull(javaClass.getResource("/acoustid/lookup.json")).readText()

    private fun TestScope.client(
        maxRetries: Int = 3,
        interval: Duration = 10.milliseconds,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = AcoustIdClient(
        http = HttpClients.create(UserAgent.of("0.1.0")),
        limiter = RateLimiter(interval, testTimeSource),
        retryPolicy = RetryPolicy(maxRetries = maxRetries, random = Random(1)),
        apiKey = key,
        baseUrl = server.url("/v2/"),
        clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC),
        ioDispatcher = ioDispatcher,
    )

    private fun ok() = MockResponse.Builder().body(json).build()

    private fun error(httpCode: Int, code: Int, message: String) = MockResponse.Builder().code(httpCode)
        .body("""{"error": {"code": $code, "message": "$message"}, "status": "error"}""").build()

    /** The form fields of a request, after undoing the gzip compression and the form encoding. */
    private fun RecordedRequest.form(): Map<String, String> {
        val bytes = requireNotNull(body).toByteArray()
        val text = GZIPInputStream(bytes.inputStream()).bufferedReader().readText()
        // The form encoding is the query encoding, so HttpUrl decodes it ("+" and %20 both become spaces).
        val parsed = "http://localhost/?$text".toHttpUrl()
        return parsed.queryParameterNames.associateWith { requireNotNull(parsed.queryParameter(it)) }
    }

    @Test
    fun `posts a gzip-compressed form to the lookup endpoint`() = runTest {
        server.enqueue(ok())
        client().lookup(fingerprint, 214_400)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v2/lookup")
        assertThat(request.headers["Content-Encoding"]).isEqualTo("gzip")
        assertThat(request.headers["Content-Type"]).startsWith("application/x-www-form-urlencoded")
        assertThat(request.headers["User-Agent"]).isEqualTo(UserAgent.of("0.1.0"))
        assertThat(request.form()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "client" to key,
                "duration" to "214",
                "fingerprint" to fingerprint,
                "meta" to "recordings releasegroups releases tracks isrcs compress",
                "format" to "json",
            ),
        )
    }

    @Test
    fun `the application key never appears in the URL`() = runTest {
        server.enqueue(ok())
        client().lookup(fingerprint, 214_400)
        assertThat(server.takeRequest().target).doesNotContain(key).doesNotContain("?")
    }

    @ParameterizedTest
    @CsvSource("187499, 187", "187500, 188", "999, 1", "1500, 2", "641000, 641")
    fun `the duration is rounded to the nearest second`(milliseconds: Long, seconds: Long) {
        assertThat(AcoustIdClient.durationSeconds(milliseconds)).isEqualTo(seconds)
    }

    @Test
    fun `a duration under half a second and an empty fingerprint are refused before any request`() = runTest {
        val client = client()
        assertThat(runCatching { client.lookup(fingerprint, 499) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { client.lookup("", 214_400) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `parses tracks, recordings and release groups`() = runTest {
        server.enqueue(ok())
        val response = client().lookup(fingerprint, 214_400)

        assertThat(response.status).isEqualTo("ok")
        assertThat(response.results).hasSize(2)
        val best = response.results[0]
        assertThat(best.score).isEqualTo(0.97)
        assertThat(best.recordings).hasSize(3)
        val recording = best.recordings[0]
        assertThat(recording.title).isEqualTo("Paper Lanterns")
        assertThat(recording.duration).isEqualTo(214.4)
        assertThat(recording.artists.map { it.name + it.joinPhrase }).containsExactly("The Quiet Harbors feat. ", "Mira Vale")
        assertThat(recording.releaseGroups.map { it.title }).containsExactly("Harbor Lights", "Quiet Songs")
        assertThat(recording.releaseGroups[1].secondaryTypes).containsExactly("Compilation")
        assertThat(recording.releaseGroups[0].releases.single().trackCount).isEqualTo(11)
        assertThat(recording.isrcs).containsExactly("XXA001900003")
        assertThat(best.recordings[2].title).isNull()
        assertThat(response.results[1].recordings).isEmpty()
    }

    @Test
    fun `an invalid application key fails at once`() = runTest {
        server.enqueue(error(400, 4, "invalid API key"))
        val error = runCatching { client().lookup(fingerprint, 214_400) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AcoustIdException.InvalidApiKey::class.java)
        assertThat(error!!.message).doesNotContain(key)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `too many requests is retried after a wait`() = runTest {
        server.enqueue(error(429, 14, "rate limit (3 requests per second) exceeded, try again later"))
        server.enqueue(ok())
        val response = client().lookup(fingerprint, 214_400)
        assertThat(response.results).hasSize(2)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(500)
    }

    @Test
    fun `server errors 500, 502, 503 and 504 are retried`() = runTest {
        server.enqueue(error(500, 5, "internal error"))
        server.enqueue(MockResponse.Builder().code(502).body("<html>bad gateway</html>").build())
        server.enqueue(error(503, 13, "service unavailable"))
        server.enqueue(MockResponse.Builder().code(504).build())
        server.enqueue(ok())
        client(maxRetries = 4).lookup(fingerprint, 214_400)
        assertThat(server.requestCount).isEqualTo(5)
    }

    @Test
    fun `the proxy's origin errors 520 to 524 are retried`() = runTest {
        (520..524).forEach { code ->
            server.enqueue(MockResponse.Builder().code(code).body("<html>origin error</html>").build())
        }
        server.enqueue(ok())
        client(maxRetries = 5).lookup(fingerprint, 214_400)
        assertThat(server.requestCount).isEqualTo(6)
    }

    @Test
    fun `Retry-After is honoured when a proxy sends one`() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "7").build())
        server.enqueue(ok())
        client().lookup(fingerprint, 214_400)
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(7_000)
    }

    @Test
    fun `after giving up on a long Retry-After, later lookups fail at once until the pause is over`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "300").build())
        server.enqueue(ok())
        val client = client()
        val first = runCatching { client.lookup(fingerprint, 214_400) }.exceptionOrNull()
        assertThat(first).isInstanceOf(AcoustIdException.Unavailable::class.java)

        val second = runCatching { client.lookup(fingerprint, 214_400) }.exceptionOrNull()
        assertThat(second).isInstanceOf(AcoustIdException.Paused::class.java)
        assertThat(second!!.message).contains("300 s").doesNotContain(key)
        assertThat(server.requestCount).isEqualTo(1)

        advanceTimeBy(300.seconds)
        client.lookup(fingerprint, 214_400)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `gives up after the last retry`() = runTest {
        repeat(4) { server.enqueue(error(503, 13, "service unavailable")) }
        val error = runCatching { client(maxRetries = 3).lookup(fingerprint, 214_400) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AcoustIdException.Unavailable::class.java)
        assertThat((error as AcoustIdException.Unavailable).attempts).isEqualTo(4)
    }

    @Test
    fun `other AcoustID errors are reported with their code and not retried`() = runTest {
        server.enqueue(error(400, 3, "invalid fingerprint"))
        val error = runCatching { client().lookup(fingerprint, 214_400) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AcoustIdException.Service::class.java)
        error as AcoustIdException.Service
        assertThat(error.code).isEqualTo(3)
        assertThat(error.httpCode).isEqualTo(400)
        assertThat(error.message).contains("invalid fingerprint")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an error status without AcoustID's error body is an HTTP error and not retried`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("<!DOCTYPE html><title>Attention Required</title>").build())
        val error = runCatching { client().lookup(fingerprint, 214_400) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AcoustIdException.Http::class.java)
        assertThat((error as AcoustIdException.Http).code).isEqualTo(403)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an error inside a successful HTTP answer is still an error`() = runTest {
        server.enqueue(error(200, 3, "invalid fingerprint"))
        val error = runCatching { client().lookup(fingerprint, 214_400) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AcoustIdException.Service::class.java)
        assertThat((error as AcoustIdException.Service).code).isEqualTo(3)
    }

    @Test
    fun `the answer is read off the caller's thread`() = runTest {
        server.enqueue(ok())
        val io = object : CoroutineDispatcher() {
            @Volatile var used = false
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                used = true
                Dispatchers.IO.dispatch(context, block)
            }
        }
        client(ioDispatcher = io).lookup(fingerprint, 214_400)
        assertThat(io.used).isTrue()
    }

    @Test
    fun `requests in a row respect the rate limit`() = runTest {
        server.enqueue(ok())
        server.enqueue(ok())
        val client = client(interval = AcoustIdClient.MIN_INTERVAL)
        client.lookup(fingerprint, 214_400)
        client.lookup(fingerprint, 214_400)
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(350)
    }
}
