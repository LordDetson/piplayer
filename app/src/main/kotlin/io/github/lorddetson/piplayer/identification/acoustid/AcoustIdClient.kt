package io.github.lorddetson.piplayer.identification.acoustid

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
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSink
import okio.buffer

/** Failures of the AcoustID web service that callers can tell apart. */
sealed class AcoustIdException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** The application key was refused (error 4): a build or setup error, so AcoustID stops for the session. */
    class InvalidApiKey(serverMessage: String) :
        AcoustIdException("AcoustID refused the application key: $serverMessage")

    /** The service stayed busy or unreachable through every retry. */
    class Unavailable(val attempts: Int, cause: Throwable?) :
        AcoustIdException("AcoustID unavailable after $attempts attempts", cause)

    /** The service asked to wait longer than a call waits; no request was sent. */
    class Paused(val remaining: Duration) :
        AcoustIdException("AcoustID asked to wait another ${remaining.wholeSecondsUp()} s; no request was sent")

    /** An error answer in AcoustID's own format that a retry would not change, such as an invalid fingerprint (3). */
    class Service(val code: Int, val httpCode: Int, message: String) :
        AcoustIdException("AcoustID error $code (HTTP $httpCode): $message")

    /** An answer without AcoustID's error body, for example a page from the proxy in front of the service. */
    class Http(val code: Int) : AcoustIdException("AcoustID answered HTTP $code without a readable body")
}

/**
 * One compressed, base64-encoded Chromaprint [fingerprint] with the exact length of the file it was made from, sent
 * with [AcoustIdClient.lookupBatch] (TI §3.2, M4a).
 */
data class AcoustIdFingerprint(val fingerprint: String, val durationMs: Long)

/**
 * AcoustID `/v2/lookup` client (TI §3.2). The fingerprint goes in a gzip-compressed form POST, so the application key
 * never appears in a URL. All requests go through one [limiter] for api.acoustid.org; busy answers (429 and error 14,
 * 500, 502, 503, 504 and the proxy's 520 to 524) and network errors are retried by [retryPolicy] as pauses of that
 * limiter. [access] is checked before every attempt; error 4 (invalid application key) stops AcoustID through it for
 * the rest of the process (TI §3.2). Answers are read and parsed on [ioDispatcher].
 */
class AcoustIdClient(
    http: OkHttpClient,
    limiter: RateLimiter,
    retryPolicy: RetryPolicy,
    private val access: ProviderAccess,
    private val apiKey: String,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val caller = RetryingCaller(http, limiter, retryPolicy, ioDispatcher, access)

    init {
        require(apiKey.isNotBlank()) { "An AcoustID application key is required" }
    }

    /**
     * Looks up a compressed, base64-encoded Chromaprint [fingerprint] of a file whose exact length is [durationMs].
     * The answer lists AcoustID tracks with their score and linked recordings, best first.
     */
    suspend fun lookup(fingerprint: String, durationMs: Long): AcoustIdResponse {
        val form = FormBody.Builder()
            .add("client", apiKey)
            .add("duration", secondsOf(fingerprint, durationMs))
            .add("fingerprint", fingerprint)
            .add("meta", META)
            .add("format", "json")
            .build()
        return post(form)
    }

    /**
     * Looks up [fingerprints], 10 to 20 compressed Chromaprint fingerprints each with its own duration, in one
     * gzip-compressed form POST. The answer has the same [AcoustIdResponse] shape as [lookup], with the results of the
     * fingerprints listed in their order. A batch never sends `maxdurationdiff` (TI §3.2): it is a legacy flag that
     * will likely be removed or replaced.
     */
    suspend fun lookupBatch(fingerprints: List<AcoustIdFingerprint>): AcoustIdResponse {
        require(fingerprints.size in MIN_BATCH_SIZE..MAX_BATCH_SIZE) {
            "A batch holds $MIN_BATCH_SIZE to $MAX_BATCH_SIZE fingerprints, not ${fingerprints.size}"
        }
        val form = FormBody.Builder().add("client", apiKey)
        fingerprints.forEach { item ->
            form.add("duration", secondsOf(item.fingerprint, item.durationMs))
            form.add("fingerprint", item.fingerprint)
        }
        return post(form.add("meta", META).add("format", "json").build())
    }

    /** The duration in whole seconds, after the same checks as [lookup]. */
    private fun secondsOf(fingerprint: String, durationMs: Long): String {
        require(fingerprint.isNotBlank()) { "The fingerprint is empty" }
        val seconds = durationSeconds(durationMs)
        require(seconds >= 1) { "A duration of $durationMs ms is shorter than AcoustID takes" }
        return seconds.toString()
    }

    /** Sends a gzip-compressed form POST to `/v2/lookup` and reads the answer through the caller. */
    private suspend fun post(form: FormBody): AcoustIdResponse {
        val request = Request.Builder()
            .url(baseUrl.resolve("lookup")!!)
            .header("Content-Encoding", "gzip")
            .post(form.gzipped())
            .build()
        return caller.execute(
            request = request,
            giveUp = { attempts, cause -> AcoustIdException.Unavailable(attempts, cause) },
            paused = { remaining -> AcoustIdException.Paused(remaining) },
            handle = ::classify,
        )
    }

    private fun classify(response: Response): CallOutcome<AcoustIdResponse> {
        val answer = try {
            AcoustIdJson.decodeFromString<AcoustIdResponse>(response.body.string())
        } catch (_: IllegalArgumentException) {
            null // not AcoustID's JSON (SerializationException is an IllegalArgumentException)
        }
        if (response.isSuccessful && answer?.status == "ok") return CallOutcome.Success(answer)
        val error = answer?.error
        return when {
            error?.code == INVALID_API_KEY -> {
                access.stop(StopReason.INVALID_API_KEY)
                CallOutcome.Fail(AcoustIdException.InvalidApiKey(error.message))
            }
            error?.code == TOO_MANY_REQUESTS || response.code in RETRIED_STATUSES -> {
                val serverNow = response.headers.getInstant("Date") ?: clock.instant()
                CallOutcome.Retry(
                    error?.let { AcoustIdException.Service(it.code, response.code, it.message) }
                        ?: AcoustIdException.Http(response.code),
                    RetryAfter.parse(response.header("Retry-After"), serverNow),
                )
            }
            error != null -> CallOutcome.Fail(AcoustIdException.Service(error.code, response.code, error.message))
            else -> CallOutcome.Fail(AcoustIdException.Http(response.code))
        }
    }

    /** The same body, gzip-compressed; the caller sets Content-Encoding. */
    private fun RequestBody.gzipped(): RequestBody {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { writeTo(it) }
        return compressed.readByteString().toRequestBody(contentType())
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.acoustid.org/v2/"

        /** Space-separated: a literal "+" would reach the server as "%2B", and it would ignore the whole value. */
        const val META = "recordings releasegroups releases tracks isrcs compress"

        /** A margin over the documented limit of three requests per second. */
        val MIN_INTERVAL = 350.milliseconds

        /** A batch request carries 10 to 20 fingerprints; larger ones time out at the server (TI §3.2). */
        const val MIN_BATCH_SIZE = 10
        const val MAX_BATCH_SIZE = 20

        /**
         * Statuses retried like error 14 ("too many requests"): busy or failing servers, and the errors of the proxy in
         * front (Cloudflare's 520 to 524) when the service behind it does not answer.
         */
        private val RETRIED_STATUSES = setOf(429, 500, 502, 503, 504, 520, 521, 522, 523, 524)
        private const val INVALID_API_KEY = 4
        private const val TOO_MANY_REQUESTS = 14

        /** AcoustID takes whole seconds only; the exact duration is rounded to the nearest one. */
        fun durationSeconds(durationMs: Long): Long = (durationMs + 500) / 1_000
    }
}
