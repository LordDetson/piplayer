package io.github.lorddetson.piplayer.identification.musicbrainz

import io.github.lorddetson.piplayer.identification.provider.ProviderAccess
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.net.CallOutcome
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryAfter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.RetryingCaller
import io.github.lorddetson.piplayer.net.wholeSecondsUp
import java.io.IOException
import java.time.Clock
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

/**
 * MusicBrainz ws/2 client. All requests go through one [limiter] for musicbrainz.org; busy answers (503, 429, 502,
 * 504), successful answers whose body is not MusicBrainz's JSON and network errors are retried by [retryPolicy] as
 * pauses of that limiter (see [RetryingCaller]). [access] is checked before every attempt; a 403 that refuses the
 * User-Agent stops MusicBrainz through it for the rest of the process (TI §9). Answers are read and parsed on
 * [ioDispatcher], so the client can be called from any thread.
 */
class MusicBrainzClient(
    http: OkHttpClient,
    limiter: RateLimiter,
    retryPolicy: RetryPolicy,
    private val access: ProviderAccess,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val caller = RetryingCaller(http, limiter, retryPolicy, ioDispatcher, access)

    suspend fun searchRecordings(query: String, limit: Int): RecordingSearchResponse {
        val url = baseUrl.newBuilder()
            .addPathSegment("recording")
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.coerceIn(1, MAX_LIMIT).toString())
            .addQueryParameter("fmt", "json")
            .build()
        return get(url) { body -> MusicBrainzJson.decodeFromString(body) }
    }

    private suspend fun <T> get(url: HttpUrl, parse: (String) -> T): T = caller.execute(
        request = Request.Builder().url(url).get().build(),
        giveUp = { attempts, cause -> MusicBrainzException.Unavailable(attempts, cause) },
        paused = { remaining -> MusicBrainzException.Paused(remaining) },
    ) { response -> classify(response, parse) }

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
        private val RETRIED_STATUSES = setOf(429, 502, 503, 504)
    }
}
