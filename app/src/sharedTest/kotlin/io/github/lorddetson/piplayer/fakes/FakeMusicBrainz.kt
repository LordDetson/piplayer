package io.github.lorddetson.piplayer.fakes

import java.util.concurrent.ConcurrentHashMap
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * A fake MusicBrainz ws/2, served at `/ws/2/`. Searches (`/recording?query=…`) are answered by the first query part
 * set with [onSearch], otherwise with [SEARCH]; lookups by path (`/recording/{mbid}`, `/release/{mbid}`, `/isrc/{isrc}`)
 * with what [on] set, otherwise with MusicBrainz's 404 ([NOT_FOUND]). [ISRC] answers `/isrc/XXA001900003`.
 */
class FakeMusicBrainz : FixtureDispatcher() {

    private val lookups = ConcurrentHashMap<String, Fixture>(mapOf("isrc/$KNOWN_ISRC" to ISRC))
    private val searches = ConcurrentHashMap<String, Fixture>()

    /** Answers the lookup at [path] below `/ws/2/` (for example `release/{mbid}`) with [fixture]. */
    fun on(path: String, fixture: Fixture): FakeMusicBrainz = apply { lookups[path.trim('/')] = fixture }

    /** Answers searches whose query contains [queryPart] with [fixture]. */
    fun onSearch(queryPart: String, fixture: Fixture): FakeMusicBrainz = apply { searches[queryPart] = fixture }

    override fun route(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath.substringAfter("/ws/2/", missingDelimiterValue = "").trim('/')
        if (request.method != "GET" || path.isEmpty()) return NOT_FOUND.response()
        if (path == "recording" || path == "release") {
            val query = request.url.queryParameter("query") ?: return BAD_REQUEST.response()
            return (searches.entries.firstOrNull { query.contains(it.key) }?.value ?: SEARCH).response()
        }
        return (lookups[path] ?: NOT_FOUND).response()
    }

    companion object {
        const val KNOWN_ISRC = "XXA001900003"

        /** A recording search with two recordings (the same song, studio and live). */
        val SEARCH = Fixture("musicbrainz/recording-search.json")

        /** The recordings of ISRC [KNOWN_ISRC]. */
        val ISRC = Fixture("musicbrainz/isrc.json")

        /** What MusicBrainz answers for an MBID or ISRC it does not know. */
        val NOT_FOUND = Fixture("musicbrainz/not-found.json", code = 404)

        /** A sign-in page with a successful status, as a captive portal or a proxy sends it. */
        val CAPTIVE_PORTAL = Fixture("musicbrainz/captive-portal.html", contentType = "text/html; charset=utf-8")

        private val BAD_REQUEST = Fixture("musicbrainz/not-found.json", code = 400)

        /** The 403 with which MusicBrainz refuses a User-Agent. */
        fun userAgentRejected(): MockResponse =
            MockResponse.Builder().code(403).addHeader("X-RateLimit-Who", "ua-blocked").body("").build()

        /** A busy answer, with the server's Retry-After when given. */
        fun busy(code: Int = 503, retryAfterSeconds: Int? = null): MockResponse = MockResponse.Builder().code(code)
            .apply { retryAfterSeconds?.let { addHeader("Retry-After", it) } }
            .body("""{"error": "busy"}""")
            .build()
    }
}
