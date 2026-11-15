package io.github.lorddetson.piplayer.fakes

import java.util.concurrent.ConcurrentHashMap
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * A fake MusicBrainz ws/2, served at `/ws/2/`. Searches (`/recording?query=…`) are answered by the first query part
 * set with [onSearch], otherwise with [SEARCH]; lookups by path (`/recording/{mbid}`, `/release/{mbid}`, `/isrc/{isrc}`)
 * with what [on] set, otherwise with MusicBrainz's 404 ([NOT_FOUND]). [ISRC] answers `/isrc/XXA001900003`. Release
 * browses (`/release?recording=…` or `?release-group=…`) are answered page by page with what [onBrowse] set for the
 * filter, the status and the offset, otherwise with an empty page ([EMPTY_BROWSE]).
 */
class FakeMusicBrainz : FixtureDispatcher() {

    private val lookups = ConcurrentHashMap<String, Fixture>(mapOf("isrc/$KNOWN_ISRC" to ISRC))
    private val searches = ConcurrentHashMap<String, Fixture>()
    private val browses = ConcurrentHashMap<String, Map<Int, Fixture>>()

    /** Answers the lookup at [path] below `/ws/2/` (for example `release/{mbid}`) with [fixture]. */
    fun on(path: String, fixture: Fixture): FakeMusicBrainz = apply { lookups[path.trim('/')] = fixture }

    /** Answers searches whose query contains [queryPart] with [fixture]. */
    fun onSearch(queryPart: String, fixture: Fixture): FakeMusicBrainz = apply { searches[queryPart] = fixture }

    /**
     * Answers the browse of releases whose [entity] (`recording` or `release-group`) is [mbid], sent with [status] (null
     * for none), with the page of [pages] at the request's offset.
     */
    fun onBrowse(entity: String, mbid: String, pages: Map<Int, Fixture>, status: String? = null): FakeMusicBrainz =
        apply { browses[browseKey(entity, mbid, status)] = pages }

    /** Serves the made-up Harbor Lights world: the recording, its releases, their lookups and the release group. */
    fun withHarborLights(): FakeMusicBrainz = apply {
        on("recording/$HARBOR_RECORDING", HARBOR_RECORDING_LOOKUP)
        on("release/$HARBOR_RELEASE", HARBOR_RELEASE_LOOKUP)
        on("release/$HARBOR_CD_RELEASE", HARBOR_CD_LOOKUP)
        on("release/$HARBOR_PROMO_RELEASE", HARBOR_PROMO_LOOKUP)
        onBrowse("recording", HARBOR_RECORDING, HARBOR_BROWSE, status = "official")
        onBrowse("release-group", HARBOR_GROUP, mapOf(0 to HARBOR_GROUP_BROWSE))
    }

    override fun route(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath.substringAfter("/ws/2/", missingDelimiterValue = "").trim('/')
        if (request.method != "GET" || path.isEmpty()) return NOT_FOUND.response()
        if (path == "release" && request.url.queryParameter("query") == null) {
            val entity = BROWSE_ENTITIES.firstOrNull { request.url.queryParameter(it) != null }
                ?: return BAD_REQUEST.response()
            val key = browseKey(entity, request.url.queryParameter(entity)!!, request.url.queryParameter("status"))
            val offset = request.url.queryParameter("offset")?.toIntOrNull() ?: 0
            return (browses[key]?.get(offset) ?: EMPTY_BROWSE).response()
        }
        if (path == "recording" || path == "release") {
            val query = request.url.queryParameter("query") ?: return BAD_REQUEST.response()
            return (searches.entries.firstOrNull { query.contains(it.key) }?.value ?: SEARCH).response()
        }
        return (lookups[path] ?: NOT_FOUND).response()
    }

    private fun browseKey(entity: String, mbid: String, status: String?) = "$entity=$mbid&status=${status.orEmpty()}"

    companion object {
        private val BROWSE_ENTITIES = listOf("recording", "release-group")

        /** A browse page of no releases: what an unknown filter gets. */
        val EMPTY_BROWSE = Fixture("musicbrainz/release-browse-empty.json")

        /** A search answer without recordings. */
        val SEARCH_EMPTY = Fixture("musicbrainz/recording-search-empty.json")

        /** "Paper Lanterns" of the made-up Harbor Lights world, looked up by its MBID. */
        const val HARBOR_RECORDING = "11111111-1111-4111-8111-111111111111"
        val HARBOR_RECORDING_LOOKUP = Fixture("musicbrainz/recording-lookup-harbor.json")

        /** The studio album, with the recording on tracks 3 and 11. */
        const val HARBOR_RELEASE = "44444444-4444-4444-8444-444444444444"
        val HARBOR_RELEASE_LOOKUP = Fixture("musicbrainz/release-harbor-lights.json")

        /** The recording's two official releases on two pages: a live album first, the studio album second. */
        val HARBOR_BROWSE = mapOf(
            0 to Fixture("musicbrainz/release-browse-harbor-1.json"),
            1 to Fixture("musicbrainz/release-browse-harbor-2.json"),
        )

        /** A CD edition of the studio album whose track 3 is another recording of the song. */
        const val HARBOR_CD_RELEASE = "46464646-4646-4646-8646-464646464646"
        val HARBOR_CD_LOOKUP = Fixture("musicbrainz/release-harbor-cd.json")

        /** A promo of the studio album whose track 3 is another song. */
        const val HARBOR_PROMO_RELEASE = "47474747-4747-4747-8747-474747474747"
        val HARBOR_PROMO_LOOKUP = Fixture("musicbrainz/release-harbor-promo.json")

        /** The studio album's release group: its three releases of all statuses on one page. */
        const val HARBOR_GROUP = "55555555-5555-4555-8555-555555555555"
        val HARBOR_GROUP_BROWSE = Fixture("musicbrainz/release-group-harbor.json")

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
