package io.github.lorddetson.piplayer.net

import io.github.lorddetson.piplayer.fakes.FakeCaa
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class HttpClientsTest {

    @StartStop
    private val server = MockWebServer()

    /** A second host, like archive.org behind Cover Art Archive. */
    @StartStop
    private val other = MockWebServer()

    private val userAgent = UserAgent.of("0.1.0")
    private val gate = TestServices.openGate()

    private fun client(gate: OnlineGate = this.gate) = HttpClients.create(userAgent, gate)

    @Test
    fun `every request carries the app's User-Agent`() {
        server.enqueue(MockResponse.Builder().body("ok").build())
        client().newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo(userAgent)
    }

    @Test
    fun `a User-Agent set by the caller is replaced`() {
        server.enqueue(MockResponse.Builder().body("ok").build())
        val request = Request.Builder().url(server.url("/")).header("User-Agent", "okhttp/5.5.0").build()
        client().newCall(request).execute().close()
        assertThat(server.takeRequest().headers.values("User-Agent")).containsExactly(userAgent)
    }

    @Test
    fun `a hung answer cannot hold a call forever`() {
        assertThat(client().callTimeoutMillis).isPositive()
    }

    @Test
    fun `await returns the response of the call`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).body("created").build())
        val response = client().newCall(Request.Builder().url(server.url("/")).build()).await()
        response.use {
            assertThat(it.code).isEqualTo(201)
            assertThat(it.body.string()).isEqualTo("created")
        }
    }

    @Test
    fun `nothing is sent while the gate is closed`() = runTest {
        server.enqueue(MockResponse.Builder().body("ok").build())
        val closed = client(InMemoryOnlineGate())
        val request = Request.Builder().url(server.url("/")).build()

        assertThat(runCatching { closed.newCall(request).execute() }.exceptionOrNull())
            .isInstanceOf(GateClosedException::class.java)
        assertThat(runCatching { closed.newCall(request).await() }.exceptionOrNull())
            .isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `the gate is asked again before a redirect hop`() {
        val gate = TestServices.openGate()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                gate.set(OnlineConsent.NONE)
                return MockResponse.Builder().code(307).addHeader("Location", other.url("/image.jpg")).build()
            }
        }
        other.enqueue(MockResponse.Builder().body("image").build())

        val error = runCatching { client(gate).newCall(Request.Builder().url(server.url("/front")).build()).execute() }
            .exceptionOrNull()
        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(other.requestCount).isZero()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a hop refused on a reused connection ends the call instead of trying again`() {
        val gate = TestServices.openGate()
        val client = client(gate)
        server.enqueue(MockResponse.Builder().body("warm").build())
        client.newCall(Request.Builder().url(server.url("/warm")).build()).execute().close()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                gate.set(OnlineConsent.NONE)
                return MockResponse.Builder().code(307).addHeader("Location", server.url("/next")).build()
            }
        }

        val error = runCatching { client.newCall(Request.Builder().url(server.url("/first")).build()).execute() }
            .exceptionOrNull()
        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    /** Records, in order, the connections the client starts and the times the gate said no. */
    private class Watch(private val gate: InMemoryOnlineGate) : EventListener(), OnlineGate {
        val events = CopyOnWriteArrayList<String>()

        override fun isOpen(): Boolean = gate.isOpen().also { if (!it) events += REFUSED }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            events += "connect $inetSocketAddress"
        }

        fun refusals(): Int = events.count { it == REFUSED }

        fun afterFirstRefusal(): List<String> = events.drop(events.indexOf(REFUSED) + 1)

        companion object {
            const val REFUSED = "refused"
        }
    }

    /**
     * Closes [gate] while the first answer is made and answers [answer], which OkHttp repeats on the same host by
     * itself, below the application interceptors.
     */
    private fun closeAndAsk(server: MockWebServer, gate: InMemoryOnlineGate, answer: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target == "/warm") return MockResponse.Builder().body("warm").build()
                gate.set(OnlineConsent.NONE)
                return answer
            }
        }
    }

    /** The repeat is refused by the hop check: the call ends with no request written and no connection opened. */
    private fun assertRepeatRefused(client: OkHttpClient, watch: Watch, warm: Boolean, server: MockWebServer = this.server) {
        if (warm) client.newCall(Request.Builder().url(server.url("/warm")).build()).execute().use { it.body.string() }
        val requestsBefore = server.requestCount

        val error = runCatching { client.newCall(Request.Builder().url(server.url("/first")).build()).execute() }
            .exceptionOrNull()

        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount - requestsBefore).isEqualTo(1)
        assertThat(watch.refusals()).isEqualTo(1)
        assertThat(watch.afterFirstRefusal()).isEmpty()
    }

    private fun watched(gate: InMemoryOnlineGate): Pair<Watch, OkHttpClient> {
        val watch = Watch(gate)
        return watch to client(watch).newBuilder().eventListener(watch).build()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a 408 repeat refused by the gate opens no connection and is not tried again`() {
        val gate = TestServices.openGate()
        val (watch, client) = watched(gate)
        closeAndAsk(server, gate, MockResponse.Builder().code(408).build())
        assertRepeatRefused(client, watch, warm = false)
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a 503 repeat with Retry-After 0 refused on a reused connection is not tried again`() {
        val gate = TestServices.openGate()
        val (watch, client) = watched(gate)
        closeAndAsk(server, gate, MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
        assertRepeatRefused(client, watch, warm = true)
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a repeat refused on a pooled HTTP 2 connection is not tried again`() {
        val gate = TestServices.openGate()
        val watch = Watch(gate)
        val h2 = MockWebServer().apply { protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE) }
        h2.start()
        try {
            val client = client(watch).newBuilder().eventListener(watch)
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
            closeAndAsk(h2, gate, MockResponse.Builder().code(408).build())
            assertRepeatRefused(client, watch, warm = true, server = h2)
        } finally {
            h2.close()
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a refused repeat does not move on to the host's next address`() {
        val gate = TestServices.openGate()
        val watch = Watch(gate)
        // The host has a second address, as musicbrainz.org has an A and an AAAA record; nothing listens there.
        val dns = Dns { listOf(InetAddress.getLoopbackAddress(), InetAddress.getByName("127.0.0.2")) }
        val client = client(watch).newBuilder().eventListener(watch).dns(dns).build()
        closeAndAsk(server, gate, MockResponse.Builder().code(408).build())
        assertRepeatRefused(client, watch, warm = false)
    }

    @Test
    fun `a Cover Art Archive image comes through the redirect with the User-Agent on both hops`() {
        val log = RecordingRequestLog()
        val caa = FakeCaa { other.url("/") }.withImages(RELEASE)
        server.dispatcher = log.wrap(caa)
        other.dispatcher = log.wrap(caa.archive)

        val response = client().newCall(Request.Builder().url(server.url("/release/$RELEASE/front-250")).build()).execute()
        val bytes = response.use { it.body.bytes() }

        assertThat(bytes).isEqualTo(FakeCaa.FRONT_250.fixture.bytes())
        assertThat(log.entries.map { it.userAgent }).containsExactly(userAgent, userAgent)
        assertThat(log.to("${other.hostName}:${other.port}")).hasSize(1)
    }

    @Test
    fun `no Cover Art Archive host is asked while the gate is closed`() {
        val caa = FakeCaa { other.url("/") }.withImages(RELEASE)
        server.dispatcher = caa
        other.dispatcher = caa.archive

        val error = runCatching {
            client(InMemoryOnlineGate()).newCall(Request.Builder().url(server.url("/release/$RELEASE/front-250")).build()).execute()
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount + other.requestCount).isZero()
    }

    /** Resolves [ARCHIVE_HOST] to the loopback address, like archive.org for the image hop, and counts its lookups. */
    private class ArchiveDns : Dns {
        val archiveLookups = AtomicInteger()
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == ARCHIVE_HOST) {
                archiveLookups.incrementAndGet()
                return listOf(InetAddress.getLoopbackAddress())
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private fun redirectToArchive(gate: InMemoryOnlineGate, closeOnRedirect: Boolean) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (closeOnRedirect) gate.set(OnlineConsent.NONE)
                val location = other.url("/download/front.jpg").newBuilder().host(ARCHIVE_HOST).build()
                return MockResponse.Builder().code(307).addHeader("Location", location).build()
            }
        }
        other.enqueue(MockResponse.Builder().body("image").build())
    }

    @Test
    fun `a redirect to another host is followed with the User-Agent`() {
        val gate = TestServices.openGate()
        val dns = ArchiveDns()
        redirectToArchive(gate, closeOnRedirect = false)

        val response = client(gate).newBuilder().dns(dns).build()
            .newCall(Request.Builder().url(server.url("/front")).build()).execute()

        response.use { assertThat(it.body.string()).isEqualTo("image") }
        assertThat(dns.archiveLookups.get()).isEqualTo(1)
        val hop = other.takeRequest()
        assertThat(hop.headers["Host"]).startsWith(ARCHIVE_HOST)
        assertThat(hop.headers["User-Agent"]).isEqualTo(userAgent)
    }

    @Test
    fun `a closed gate stops a redirect before the new host is even looked up`() {
        val gate = TestServices.openGate()
        val dns = ArchiveDns()
        redirectToArchive(gate, closeOnRedirect = true)

        val error = runCatching {
            client(gate).newBuilder().dns(dns).build().newCall(Request.Builder().url(server.url("/front")).build()).execute()
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(dns.archiveLookups.get()).isZero()
        assertThat(other.requestCount).isZero()
    }

    @Test
    fun `a redirected POST is not sent again`() {
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", other.url("/v2/lookup")).build())
        val request = Request.Builder().url(server.url("/v2/lookup")).post("fingerprint=x".toRequestBody()).build()

        client().newCall(request).execute().use { assertThat(it.code).isEqualTo(307) }
        assertThat(other.requestCount).isZero()
    }

    @Test
    fun `a relative Location is followed on the same host`() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/release/x/front-250.jpg").build())
        server.enqueue(MockResponse.Builder().body("thumb").build())

        client().newCall(Request.Builder().url(server.url("/release/x/front-250")).build()).execute()
            .use { assertThat(it.body.string()).isEqualTo("thumb") }
        server.takeRequest()
        assertThat(server.takeRequest().target).isEqualTo("/release/x/front-250.jpg")
    }

    @Test
    fun `an endless redirect ends the call`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(302).addHeader("Location", server.url("/again")).build()
        }
        val error = runCatching { client().newCall(Request.Builder().url(server.url("/")).build()).execute() }
            .exceptionOrNull()
        assertThat(error).isInstanceOf(ProtocolException::class.java)
        assertThat(server.requestCount).isLessThanOrEqualTo(21)
    }

    private companion object {
        const val RELEASE = "44444444-4444-4444-8444-444444444444"
        const val ARCHIVE_HOST = "archive.test"
    }
}
