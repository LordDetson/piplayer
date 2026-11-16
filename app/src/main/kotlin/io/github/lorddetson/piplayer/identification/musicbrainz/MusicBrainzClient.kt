package io.github.lorddetson.piplayer.identification.musicbrainz

import io.github.lorddetson.piplayer.identification.provider.ProviderAccess
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.text.MusicBrainzRef
import io.github.lorddetson.piplayer.identification.text.RecordingQuery
import io.github.lorddetson.piplayer.net.CallOutcome
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryAfter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.RetryingCaller
import io.github.lorddetson.piplayer.net.wholeSecondsUp
import java.io.IOException
import java.time.Clock
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Failures of the MusicBrainz web service that callers can tell apart. */
sealed class MusicBrainzException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** The server refused the User-Agent: a build or configuration error, so MusicBrainz stops for the session. */
    class UserAgentRejected(message: String) : MusicBrainzException(message)

    /** The server stayed busy or unreachable through every retry. */
    class Unavailable(val attempts: Int, cause: Throwable?) :
        MusicBrainzException("MusicBrainz unavailable after $attempts attempts", cause)

    /** The service asked to wait longer than a call waits; no request was sent. */
    class Paused(val remaining: Duration) :
        MusicBrainzException("MusicBrainz asked to wait another ${remaining.wholeSecondsUp()} s; no request was sent")

    /** A successful status with a body that is not MusicBrainz's JSON, such as a captive portal's sign-in page. */
    class Unreadable(val code: Int, cause: Throwable) :
        MusicBrainzException("MusicBrainz answered HTTP $code with a body that is not its JSON", cause)

    /** Any other error status, which a retry would not change. */
    class Http(val code: Int) : MusicBrainzException("MusicBrainz answered HTTP $code")
}

/** The MusicBrainz lookups that release choice needs (TI §4.2, §5.1); [MusicBrainzClient] is the real one. */
interface MusicBrainzLookups {

    /** The recording with its credit and ISRCs, or null when MusicBrainz does not know the ID. */
    suspend fun lookupRecording(mbid: String): RecordingDto?

    /** The release with its tracks, credits, labels, release group and ISRCs, or null for an unknown ID. */
    suspend fun lookupRelease(mbid: String): ReleaseDto?

    /** Every release of a recording, official ones or else any, over at most [maxPages] browse requests. */
    suspend fun releasesOfRecording(recordingId: String, maxPages: Int = MusicBrainzClient.MAX_PAGES): ReleaseList

    /**
     * The same, telling [onPage] the number of pages fetched so far after each page, for the progress of a run (TI §3).
     * Without its own paging a lookup reports the pages once, at the end.
     */
    suspend fun releasesOfRecording(recordingId: String, maxPages: Int, onPage: (pages: Int) -> Unit): ReleaseList =
        releasesOfRecording(recordingId, maxPages).also { onPage(it.pages) }

    /** Every release of a release group, all statuses, official ones first, over at most [maxPages] requests. */
    suspend fun releasesOfReleaseGroup(groupId: String, maxPages: Int = MusicBrainzClient.MAX_PAGES): ReleaseList
}

/**
 * MusicBrainz ws/2 client. All requests go through one [limiter] for musicbrainz.org; busy answers (503, 429, 502,
 * 504), successful answers whose body is not MusicBrainz's JSON and network errors are retried by [retryPolicy] as
 * pauses of that limiter (see [RetryingCaller]). [access] is checked before every attempt; a 403 that refuses the
 * User-Agent stops MusicBrainz through it for the rest of the process (TI §9). Answers are read and parsed on
 * [ioDispatcher], so the client can be called from any thread.
 *
 * Lookups by MBID or ISRC give null (or nothing) for MusicBrainz's 404 at once. `inc` values are joined by '+', which
 * OkHttp sends as `%2B`; the server reads both the same (checked on 15.11).
 */
class MusicBrainzClient(
    http: OkHttpClient,
    limiter: RateLimiter,
    retryPolicy: RetryPolicy,
    private val access: ProviderAccess,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MusicBrainzLookups {

    private val caller = RetryingCaller(http, limiter, retryPolicy, ioDispatcher, access)

    /** A recording search; [dismax] sends free text that the server reads as plain words (TI §3.3). */
    suspend fun searchRecordings(query: String, limit: Int, dismax: Boolean = false): RecordingSearchResponse {
        val url = baseUrl.newBuilder()
            .addPathSegment("recording")
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.coerceIn(1, MAX_LIMIT).toString())
            .apply { if (dismax) addQueryParameter("dismax", "true") }
            .addQueryParameter("fmt", "json")
            .build()
        return get(url) { body -> MusicBrainzJson.decodeFromString(body) }
    }

    /** One query of the text search as candidates; a manual query goes out with `dismax=true` (TI §3.3). */
    suspend fun search(query: RecordingQuery): List<TextCandidate> =
        searchRecordings(query.query, query.limit, dismax = query.manual).recordings.map { it.toCandidate() }

    /** Stage 1 (TI §3): `/recording/{mbid}?inc=artist-credits+isrcs`; null for a 404 and for text that is no MBID. */
    override suspend fun lookupRecording(mbid: String): RecordingDto? {
        val id = plainMbid(mbid) ?: return null
        return getOrNull(entity("recording", id, RECORDING_INC)) { body -> MusicBrainzJson.decodeFromString(body) }
    }

    /**
     * Stage 2 (TI §3): `/isrc/{isrc}?inc=artists`, the recordings of [isrc] with their artist credits, so that an
     * artist that disagrees with the tags shows in the reason; none for a 404 or for text that is no ISRC, which sends
     * nothing.
     */
    suspend fun lookupIsrc(isrc: String): List<RecordingDto> {
        val plain = plainIsrc(isrc) ?: return emptyList()
        val url = entity("isrc", plain, ISRC_INC)
        return getOrNull(url) { body -> MusicBrainzJson.decodeFromString<IsrcResponse>(body).recordings }.orEmpty()
    }

    /**
     * Stage 6 (TI §5.1): `/release/{mbid}?inc=recordings+artist-credits+labels+release-groups+isrcs`; null for a 404
     * and for text that is no MBID, which sends nothing.
     */
    override suspend fun lookupRelease(mbid: String): ReleaseDto? {
        val id = plainMbid(mbid) ?: return null
        return getOrNull(entity("release", id, RELEASE_INC)) { body -> MusicBrainzJson.decodeFromString(body) }
    }

    /** One page of a recording's releases (TI §4.2 step 1). */
    suspend fun browseReleasesOfRecording(
        recordingId: String,
        offset: Int,
        officialOnly: Boolean,
    ): ReleaseBrowseResponse =
        browse("recording", recordingId, offset, if (officialOnly) OFFICIAL else null, RECORDING_BROWSE_INC)

    /** One page of a release group's releases, all statuses (TI §4.2 step 6). */
    suspend fun browseReleasesOfReleaseGroup(groupId: String, offset: Int): ReleaseBrowseResponse =
        browse("release-group", groupId, offset, status = null, inc = GROUP_BROWSE_INC)

    /**
     * Pages through the official releases of the recording; when it has none, through its releases of any status
     * (TI §4.2 step 1). Both walks together send at most [maxPages] requests.
     */
    override suspend fun releasesOfRecording(recordingId: String, maxPages: Int): ReleaseList =
        releasesOfRecording(recordingId, maxPages) {}

    override suspend fun releasesOfRecording(recordingId: String, maxPages: Int, onPage: (pages: Int) -> Unit): ReleaseList {
        val official = walk(maxPages, onPage) { offset ->
            browseReleasesOfRecording(recordingId, offset, officialOnly = true)
        }
        if (official.releases.isNotEmpty() || official.pages >= maxPages) return official
        val any = walk(maxPages - official.pages, { onPage(official.pages + it) }) { offset ->
            browseReleasesOfRecording(recordingId, offset, officialOnly = false)
        }
        return any.copy(pages = official.pages + any.pages, nonOfficialFallback = true)
    }

    /** Pages through the group's releases of every status: official ones first, otherwise in the server's order. */
    override suspend fun releasesOfReleaseGroup(groupId: String, maxPages: Int): ReleaseList {
        val all = walk(maxPages, onPage = {}) { offset -> browseReleasesOfReleaseGroup(groupId, offset) }
        return all.copy(releases = all.releases.sortedBy { !it.status.equals(OFFICIAL, ignoreCase = true) })
    }

    /** A pasted link or ID (TI §3.3): a bare MBID is tried as a recording, then, after a 404, as a release. */
    suspend fun lookup(ref: MusicBrainzRef): RefLookup {
        val found = when (ref) {
            is MusicBrainzRef.Recording -> lookupRecording(ref.mbid)?.let(RefLookup::Recording)
            is MusicBrainzRef.Release -> lookupRelease(ref.mbid)?.let(RefLookup::Release)
            is MusicBrainzRef.AnyEntity ->
                lookupRecording(ref.mbid)?.let(RefLookup::Recording) ?: lookupRelease(ref.mbid)?.let(RefLookup::Release)
            is MusicBrainzRef.Unsupported ->
                throw IllegalArgumentException("A '${ref.entity}' link cannot be looked up")
        }
        return found ?: RefLookup.NotFound
    }

    /**
     * Sends pages from offset 0 until the reported count is reached, a page comes back empty or [maxPages] pages are
     * sent. The next offset grows by the releases actually received, which can be fewer than the limit. [onPage] hears
     * the number of pages after each one.
     */
    private suspend fun walk(
        maxPages: Int,
        onPage: (pages: Int) -> Unit,
        page: suspend (offset: Int) -> ReleaseBrowseResponse,
    ): ReleaseList {
        val releases = LinkedHashMap<String, ReleaseDto>()
        var offset = 0
        var total = 0
        var pages = 0
        var exhausted = false
        while (pages < maxPages) {
            val answer = page(offset)
            pages++
            onPage(pages)
            total = answer.releaseCount
            answer.releases.forEach { releases.putIfAbsent(it.id, it) }
            offset += answer.releases.size
            if (answer.releases.isEmpty() || offset >= total) {
                exhausted = true
                break
            }
        }
        return ReleaseList(releases.values.toList(), total, pages, capped = !exhausted)
    }

    private suspend fun browse(
        entity: String,
        mbid: String,
        offset: Int,
        status: String?,
        inc: String,
    ): ReleaseBrowseResponse {
        val url = baseUrl.newBuilder()
            .addPathSegment("release")
            .addQueryParameter(entity, mbid)
            .apply { status?.let { addQueryParameter("status", it) } }
            .addQueryParameter("inc", inc)
            .addQueryParameter("limit", MAX_LIMIT.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("fmt", "json")
            .build()
        return get(url) { body -> MusicBrainzJson.decodeFromString(body) }
    }

    /** `/{type}/{id}?inc=…&fmt=json`, for an MBID or an ISRC. */
    private fun entity(type: String, mbid: String, inc: String): HttpUrl = baseUrl.newBuilder()
        .addPathSegment(type)
        .addPathSegment(mbid)
        .addQueryParameter("inc", inc)
        .addQueryParameter("fmt", "json")
        .build()

    private suspend fun <T> get(url: HttpUrl, parse: (String) -> T): T = send(url, notFound = null, parse)

    /** Like [get], but MusicBrainz's 404 for an unknown MBID or ISRC gives null at once instead of an error. */
    private suspend fun <T : Any> getOrNull(url: HttpUrl, parse: (String) -> T): T? =
        send(url, notFound = { null }, parse)

    private suspend fun <T> send(url: HttpUrl, notFound: (() -> T)?, parse: (String) -> T): T = caller.execute(
        request = Request.Builder().url(url).get().build(),
        giveUp = { attempts, cause -> MusicBrainzException.Unavailable(attempts, cause) },
        paused = { remaining -> MusicBrainzException.Paused(remaining) },
    ) { response ->
        if (response.code == 404 && notFound != null) CallOutcome.Success(notFound()) else classify(response, parse)
    }

    private fun <T> classify(response: Response, parse: (String) -> T): CallOutcome<T> {
        if (response.isSuccessful) {
            return try {
                CallOutcome.Success(parse(response.body.string()))
            } catch (e: IllegalArgumentException) {
                // Not MusicBrainz's JSON (SerializationException is an IllegalArgumentException): a captive portal's
                // or a proxy's page, which a later attempt may get past.
                CallOutcome.Retry(MusicBrainzException.Unreadable(response.code, e))
            }
        }
        val who = response.header("X-RateLimit-Who")
        return when {
            response.code == 403 && who?.startsWith("ua-") == true -> {
                access.stop(StopReason.USER_AGENT_REJECTED)
                CallOutcome.Fail(MusicBrainzException.UserAgentRejected("MusicBrainz refused the User-Agent ($who)"))
            }
            response.code !in RETRIED_STATUSES -> CallOutcome.Fail(MusicBrainzException.Http(response.code))
            else -> {
                val serverNow = response.headers.getInstant("Date") ?: clock.instant()
                CallOutcome.Retry(
                    MusicBrainzException.Http(response.code),
                    RetryAfter.parse(response.header("Retry-After"), serverNow),
                )
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://musicbrainz.org/ws/2/"
        const val MAX_LIMIT = 100

        /** The most browse requests one release list takes: 1 000 releases (TI §4.2). */
        const val MAX_PAGES = 10
        private val RETRIED_STATUSES = setOf(429, 502, 503, 504)
        private const val OFFICIAL = "official"
        private const val RECORDING_INC = "artist-credits+isrcs"
        private const val ISRC_INC = "artists"
        private const val RELEASE_INC = "recordings+artist-credits+labels+release-groups+isrcs"
        private const val RECORDING_BROWSE_INC = "release-groups+media+labels"
        private const val GROUP_BROWSE_INC = "media+labels"
        private val ISRC = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
        private val MBID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** [text] as an MBID in lower case, or null when it is none (a broken tag), so nothing is sent for it. */
        fun plainMbid(text: String): String? = text.trim().lowercase(Locale.ROOT).takeIf(MBID::matches)

        /** [text] as a plain ISRC ("gb-cee-86-00016" gives "GBCEE8600016"), or null when it is none. */
        fun plainIsrc(text: String): String? =
            text.filterNot { it == '-' || it.isWhitespace() }.uppercase(Locale.ROOT).takeIf(ISRC::matches)
    }
}
