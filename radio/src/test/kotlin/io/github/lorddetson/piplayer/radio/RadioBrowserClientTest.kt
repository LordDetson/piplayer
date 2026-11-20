package io.github.lorddetson.piplayer.radio

import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.OnlineGate
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RadioBrowserClientTest {

    @Test
    fun `search sends only the selected filters to a directory mirror over HTTPS`() = runBlocking {
        val directory = directory { answer(it, "[]") }
        directory.client.search(RadioStationQuery(" Радио & Jazz ", " by ", " english ", "jazz", 10, 5))

        val request = directory.requests.single()
        assertEquals("https://first.api.radio-browser.info/json/stations/search", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals(USER_AGENT, request.header("User-Agent"))
        assertEquals("application/json", request.header("Accept"))
        val form = request.body as FormBody
        assertEquals(
            mapOf(
                "name" to "Радио & Jazz",
                "countrycode" to "BY",
                "language" to "english",
                "tag" to "jazz",
                "hidebroken" to "true",
                "offset" to "10",
                "limit" to "5",
            ),
            (0 until form.size).associate { form.name(it) to form.value(it) },
        )
    }

    @Test
    fun `station pages preserve the server offset even when rows are skipped`() = runBlocking {
        val valid = row(
            original = "http://station.example/playlist.pls",
            resolved = "https://live.example/stream",
            info = """, "tags":"jazz, news, jazz, ","countrycode":"by","language":"english, russian", "extra":true""",
        )
        val broken = row(id = OTHER_ID, info = """, "lastcheckok":0""")
        val malformed = """{"stationuuid":3,"name":"Broken API row"}"""
        val directory = directory { answer(it, "[$valid,$broken,$malformed,$valid]") }
        val page = directory.client.search(RadioStationQuery(offset = 6, limit = 4))

        assertEquals(10, page.nextOffset)
        val match = page.stations.single()
        assertEquals(UUID.fromString(STATION_ID), match.id)
        assertEquals("Pi Jazz", match.name)
        assertEquals("https://live.example/stream", match.streamUrl.toString())
        assertEquals("BY", match.countryCode)
        assertEquals(listOf("english", "russian"), match.languages)
        assertEquals(listOf("jazz", "news"), match.tags)
        assertEquals(1, directory.requests.size)
    }

    @Test
    fun `invalid stream URLs are filtered and an invalid resolved URL falls back to the original`() = runBlocking {
        val fallback = row(resolved = "file:///music/private.mp3")
        val credentials = row(id = OTHER_ID, original = "https://user:password@live.example/stream")
        val noId = row(id = "1-2-3-4-5")
        val noName = row(name = " ")
        val relative = row(original = "//live.example/stream")
        val directory = directory { answer(it, "[$fallback,$credentials,$noId,$noName,$relative]") }

        val result = directory.client.search(RadioStationQuery())

        assertNull(result.nextOffset)
        assertEquals(
            listOf("https://station.example/stream"),
            result.stations.map { it.streamUrl.toString() },
        )
    }

    @Test
    fun `cleartext-only stations are skipped and a secure original remains playable`() = runBlocking {
        val cleartext = row(id = OTHER_ID, original = "http://live.example/stream", resolved = "http://cdn.example/stream")
        val secure = row(resolved = "http://cdn.example/stream")
        val directory = directory { answer(it, "[$cleartext,$secure]") }

        val page = directory.client.search(RadioStationQuery())

        assertEquals(listOf("https://station.example/stream"), page.stations.map { it.streamUrl.toString() })
    }

    @Test
    fun `catalog excludes flagged HLS and playlist files but keeps a direct fallback`() = runBlocking {
        val hls = row(id = OTHER_ID, original = "https://example.net/stream", info = """, "hls":1""")
        val playlist = row(id = OTHER_ID, original = "https://example.net/station.m3u8")
        val fallback = row(resolved = "https://example.net/station.pls", info = """, "hls":0""")
        val directory = directory { answer(it, "[$hls,$playlist,$fallback]") }

        val page = directory.client.search(RadioStationQuery())

        assertEquals(listOf("https://station.example/stream"), page.stations.map { it.streamUrl.toString() })
    }

    @Test
    fun `retry uses the next distinct trusted mirror`() = runBlocking {
        val directory = directory(
            mirrors = listOf(
                "attacker.invalid",
                "first.api.radio-browser.info.evil.example",
                "FIRST.api.radio-browser.info.",
                "first.api.radio-browser.info",
                "second.api.radio-browser.info",
                "third.api.radio-browser.info",
            ),
        ) { request ->
            when (request.url.host) {
                "first.api.radio-browser.info" -> answer(request, "busy", 503)
                "second.api.radio-browser.info" -> throw IOException("unreachable")
                else -> answer(request, "[${row()}]")
            }
        }

        assertEquals(1, directory.client.search(RadioStationQuery()).stations.size)
        assertEquals(
            listOf("first.api.radio-browser.info", "second.api.radio-browser.info", "third.api.radio-browser.info"),
            directory.requests.map { it.url.host },
        )
    }

    @Test
    fun `a declined search never discovers mirrors`() {
        val directory = directory(gate = OnlineGate { false }) { error("An HTTP request escaped the closed gate") }

        assertThrows(GateClosedException::class.java) { runBlocking { directory.client.search(RadioStationQuery()) } }
        assertEquals(0, directory.discoveries)
        assertEquals(emptyList<Request>(), directory.requests)
    }

    @Test
    fun `a gate closed between mirrors stops failover`() {
        var open = true
        val directory = directory(gate = OnlineGate { open }) { request ->
            open = false
            answer(request, "busy", 503)
        }

        assertThrows(GateClosedException::class.java) { runBlocking { directory.client.search(RadioStationQuery()) } }
        assertEquals(1, directory.requests.size)
    }

    @Test
    fun `directory redirects cannot transmit the search to other origins`() {
        val directory = directory { request ->
            answer(request, "redirect", 307).newBuilder()
                .header("Location", "https://external.example/collect")
                .build()
        }

        val failure = assertThrows(RadioBrowserException.Http::class.java) {
            runBlocking { directory.client.search(RadioStationQuery(name = "Jazz")) }
        }
        assertEquals(307, failure.code)
        assertEquals(1, directory.requests.size)
    }

    @Test
    fun `HTTP rate limit is reported without moving to another mirror`() {
        val directory = directory { answer(it, "slow down", 429) }

        val failure = assertThrows(RadioBrowserException.Http::class.java) {
            runBlocking { directory.client.search(RadioStationQuery()) }
        }
        assertEquals(429, failure.code)
        assertEquals(1, directory.requests.size)
    }

    @Test
    fun `an HTML answer is an invalid response instead of an empty station page`() {
        val directory = directory(mirrors = listOf("first.api.radio-browser.info")) {
            answer(it, "<html>Sign in to Wi-Fi</html>")
        }

        assertThrows(RadioBrowserException.InvalidResponse::class.java) {
            runBlocking { directory.client.search(RadioStationQuery()) }
        }
    }

    @Test
    fun `unbounded page parameters fail before network discovery`() {
        val directory = directory { error("No request should have been built") }

        listOf(RadioStationQuery(offset = -1), RadioStationQuery(limit = 0), RadioStationQuery(limit = 501))
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) { runBlocking { directory.client.search(invalid) } }
            }
        assertEquals(0, directory.discoveries)
    }

    private fun directory(
        mirrors: List<String> = listOf("first.api.radio-browser.info", "second.api.radio-browser.info"),
        gate: OnlineGate = OnlineGate { true },
        respond: (Request) -> Response,
    ): Directory = Directory(mirrors, gate, respond)

    private class Directory(mirrors: List<String>, gate: OnlineGate, respond: (Request) -> Response) {
        val requests = mutableListOf<Request>()
        var discoveries = 0
            private set

        private val http = HttpClients.create(USER_AGENT, gate).newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            respond(request)
        }.build()

        val client = RadioBrowserClient(http, gate) {
            discoveries++
            mirrors
        }
    }

    private fun answer(request: Request, body: String, status: Int = 200): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("Fixture")
        .body(body.toResponseBody())
        .build()

    private fun row(
        id: String = STATION_ID,
        name: String = " Pi Jazz ",
        original: String = "https://station.example/stream",
        resolved: String = "",
        info: String = "",
    ): String = """{"stationuuid":"$id","name":"$name","url":"$original","url_resolved":"$resolved"$info}"""

    private companion object {
        const val USER_AGENT = "PiPlayer/Test ( https://example.net/piplayer )"
        const val STATION_ID = "f88c9699-48ef-4a60-b685-b0bf99a9de28"
        const val OTHER_ID = "297fe742-d108-46b7-a6d2-1e94c80f50fb"
    }
}
