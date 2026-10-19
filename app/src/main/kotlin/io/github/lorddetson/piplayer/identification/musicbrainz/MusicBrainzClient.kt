package io.github.lorddetson.piplayer.identification.musicbrainz

import io.github.lorddetson.piplayer.net.CallOutcome
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryAfter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.RetryingCaller
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Failures of the MusicBrainz web service that callers can tell apart. */
sealed class MusicBrainzException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** The server refused the User-Agent: a build or configuration error, so the client must not keep trying. */
    class UserAgentRejected(message: String) : MusicBrainzException(message)

    /** The server stayed busy or unreachable through every retry. */
    class Unavailable(val attempts: Int, cause: Throwable?) :
        MusicBrainzException("MusicBrainz unavailable after $attempts attempts", cause)

    /** Any other error status, which a retry would not change. */
    class Http(val code: Int) : MusicBrainzException("MusicBrainz answered HTTP $code")
}

/**
 * MusicBrainz ws/2 client. All requests go through one [limiter] for musicbrainz.org; busy answers (503, 429, 502,
 * 504) and network errors are retried by [retryPolicy] as pauses of that limiter (see [RetryingCaller]). Answers are
 * read and parsed on [ioDispatcher], so the client can be called from any thread.
 */
class MusicBrainzClient(
    http: OkHttpClient,
    limiter: RateLimiter,
    retryPolicy: RetryPolicy,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val caller = RetryingCaller(http, limiter, retryPolicy, ioDispatcher)

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
    ) { response -> classify(response, parse) }

    private fun <T> classify(response: Response, parse: (String) -> T): CallOutcome<T> {
        if (response.isSuccessful) return CallOutcome.Success(parse(response.body.string()))
        val who = response.header("X-RateLimit-Who")
        return when {
            response.code == 403 && who?.startsWith("ua-") == true ->
                CallOutcome.Fail(MusicBrainzException.UserAgentRejected("MusicBrainz refused the User-Agent ($who)"))
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
