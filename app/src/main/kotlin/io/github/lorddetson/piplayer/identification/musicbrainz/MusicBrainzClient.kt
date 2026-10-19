package io.github.lorddetson.piplayer.identification.musicbrainz

import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryAfter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.await
import java.io.IOException
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * 504) and network errors are retried by [retryPolicy]. A retry's wait is a pause of the shared limiter, so every
 * caller waits, not only the one that was turned away; that holds when the client gives up too. Answers are read and
 * parsed on [ioDispatcher], so the client can be called from any thread.
 */
class MusicBrainzClient(
    private val http: OkHttpClient,
    private val limiter: RateLimiter,
    private val retryPolicy: RetryPolicy,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun searchRecordings(query: String, limit: Int): RecordingSearchResponse {
        val url = baseUrl.newBuilder()
            .addPathSegment("recording")
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.coerceIn(1, MAX_LIMIT).toString())
            .addQueryParameter("fmt", "json")
            .build()
        return get(url) { body -> MusicBrainzJson.decodeFromString(body) }
    }

    private suspend fun <T> get(url: HttpUrl, parse: (String) -> T): T {
        val request = Request.Builder().url(url).get().build()
        var attempt = 0
        while (true) {
            attempt++
            limiter.acquire()
            val retry = try {
                http.newCall(request).await().use { response ->
                    if (response.isSuccessful) return withContext(ioDispatcher) { parse(response.body.string()) }
                    retryOrThrow(response)
                }
            } catch (e: MusicBrainzException) {
                throw e
            } catch (e: IOException) {
                Retry(e, retryAfter = null)
            }
            val wait = retryPolicy.delayBefore(attempt, retry.retryAfter)
            if (wait == null) {
                retry.retryAfter?.let { limiter.pause(minOf(it, MAX_GIVE_UP_PAUSE)) }
                throw MusicBrainzException.Unavailable(attempt, retry.cause)
            }
            limiter.pause(wait)
        }
    }

    private class Retry(val cause: Throwable, val retryAfter: Duration?)

    private fun retryOrThrow(response: Response): Retry {
        val who = response.header("X-RateLimit-Who")
        when {
            response.code == 403 && who?.startsWith("ua-") == true ->
                throw MusicBrainzException.UserAgentRejected("MusicBrainz refused the User-Agent ($who)")
            response.code !in RETRIED_STATUSES -> throw MusicBrainzException.Http(response.code)
        }
        val serverNow = response.headers.getInstant("Date") ?: clock.instant()
        return Retry(MusicBrainzException.Http(response.code), RetryAfter.parse(response.header("Retry-After"), serverNow))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://musicbrainz.org/ws/2/"
        const val MAX_LIMIT = 100
        private val RETRIED_STATUSES = setOf(429, 502, 503, 504)

        /** The longest pause kept after giving up, however long the server asked to wait. */
        private val MAX_GIVE_UP_PAUSE = 1.hours
    }
}
