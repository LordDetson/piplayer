package io.github.lorddetson.piplayer.net

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HttpClientsTest {

    @StartStop
    private val server = MockWebServer()

    private val userAgent = UserAgent.of("0.1.0")

    @Test
    fun `every request carries the app's User-Agent`() {
        server.enqueue(MockResponse.Builder().body("ok").build())
        HttpClients.create(userAgent).newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo(userAgent)
    }

    @Test
    fun `a User-Agent set by the caller is replaced`() {
        server.enqueue(MockResponse.Builder().body("ok").build())
        val request = Request.Builder().url(server.url("/")).header("User-Agent", "okhttp/5.5.0").build()
        HttpClients.create(userAgent).newCall(request).execute().close()
        assertThat(server.takeRequest().headers.values("User-Agent")).containsExactly(userAgent)
    }

    @Test
    fun `a hung answer cannot hold a call forever`() {
        assertThat(HttpClients.create(userAgent).callTimeoutMillis).isPositive()
    }

    @Test
    fun `await returns the response of the call`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).body("created").build())
        val response = HttpClients.create(userAgent).newCall(Request.Builder().url(server.url("/")).build()).await()
        response.use {
            assertThat(it.code).isEqualTo(201)
            assertThat(it.body.string()).isEqualTo("created")
        }
    }
}
