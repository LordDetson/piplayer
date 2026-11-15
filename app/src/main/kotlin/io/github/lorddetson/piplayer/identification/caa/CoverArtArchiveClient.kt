package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.provider.ProviderAccess
import io.github.lorddetson.piplayer.library.details.PictureSize
import io.github.lorddetson.piplayer.net.CallOutcome
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryAfter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.RetryingCaller
import io.github.lorddetson.piplayer.net.wholeSecondsUp
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What Cover Art Archive answered, or what a recorded failure still answers without a request. */
sealed interface CaaResult<out T> {

    data class Found<T>(val value: T) : CaaResult<T>

    /** 404: no such image; for a release index, a release without images. */
    data object NotFound : CaaResult<Nothing>

    /** 503: busy; nothing for this address is sent before [until]. */
    data class Busy(val until: Instant) : CaaResult<Nothing>
}

/**
 * A fetched cover. [requested] is the address asked for, [url] the one that sent the image after the redirects;
 * [original] is true when the image is the uploaded original, asked for or taken after a missing thumbnail. [size] is
 * read from the image header, null when the format does not say it without decoding (WebP). [source] is the release or
 * release group of a front image ([CoverArtArchiveClient.cover]), null for an image of the release index.
 */
class CoverImage(
    val bytes: ByteArray,
    val format: ImageFormat,
    val size: PictureSize?,
    val requested: HttpUrl,
    val url: HttpUrl,
    val original: Boolean,
    val source: CoverSource?,
) {
    /** A release group's front image, shown as taken from another edition (TI §5.2). */
    val anotherEdition: Boolean get() = source?.anotherEdition == true

    override fun toString(): String = "CoverImage($format, $size, ${bytes.size} bytes, $url, original=$original)"
}

/** Failures of Cover Art Archive that callers can tell apart; a 404 and a 503 are answers ([CaaResult]), not these. */
sealed class CaaException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** The server stayed unreachable, or kept sending a page instead of its answer, through every retry. */
    class Unavailable(val attempts: Int, cause: Throwable?) :
        CaaException("Cover Art Archive unavailable after $attempts attempts", cause)

    /** The limiter is paused for longer than a call waits; no request was sent. */
    class Paused(val remaining: Duration) :
        CaaException("Cover Art Archive asked to wait another ${remaining.wholeSecondsUp()} s; no request was sent")

    /** A successful status with a body that is not the expected JSON or image, such as a captive portal's page. */
    class Unreadable(val code: Int, cause: Throwable? = null) :
        CaaException("Cover Art Archive answered HTTP $code with a body that is not its answer", cause)

    /** A successful status with a body that is no image PiPlayer reads (a PDF booklet, for example). */
    class NotAnImage(val contentType: String?) : CaaException("Cover Art Archive sent no image ($contentType)")

    /** An image larger than the client accepts. */
    class TooLarge(val limit: Long) : CaaException("Cover Art Archive sent an image over $limit bytes")

    /** Any other error status, which a retry would not change. */
    class Http(val code: Int) : CaaException("Cover Art Archive answered HTTP $code")
}

/**
 * Cover Art Archive client (TI §5.2, §8). Every request goes through the shared HTTP client, whose gate is asked on
 * every redirect hop and which sends the User-Agent on each, and through the CAA [limiter]: the redirect to the image
 * host belongs to the same call and takes no slot of its own. [access] is checked before every attempt. Failed
 * addresses are recorded in [failures] with the TTLs of [FailureTtlPolicy] (time from [clock]); a live entry answers
 * without a request, and a blocked call records nothing. A 503 is not retried: it pauses the limiter by [backoff]
 * (doubling on each 503 in a row, never shorter than Retry-After) and is recorded. Network errors, text pages and the
 * answers 429, 502 and 504 are retried by [retryPolicy] as for the other providers. The app holds one client per
 * process ([IdentificationServices.coverArtArchive]).
 */
class CoverArtArchiveClient(
    http: OkHttpClient,
    private val limiter: RateLimiter,
    retryPolicy: RetryPolicy,
    access: ProviderAccess,
    private val failures: FailureCache,
    private val baseUrl: HttpUrl,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val backoff: RetryPolicy = RetryPolicy(maxRetries = Int.MAX_VALUE),
    private val maxImageBytes: Long = MAX_IMAGE_BYTES,
) {

    private val caller = RetryingCaller(http, limiter, retryPolicy, ioDispatcher, access)
    private val ttl = FailureTtlPolicy(clock)

    /** The 503s of this provider in a row, whatever their address; any other answer ends the row. */
    private val busyInARow = AtomicInteger()

    /** What one request ended with, once [RetryingCaller] is done with it. */
    private sealed interface Answer<out T> {
        data class Got<T>(val value: T, val url: HttpUrl) : Answer<T>

        /** 404; [fromImageHost] when CAA redirected to another host (the image host) and that host answered it. */
        data class Missing(val fromImageHost: Boolean) : Answer<Nothing>

        data class Busy(val until: Instant) : Answer<Nothing>
    }

    /**
     * The [size] image of [source]. A thumbnail that is redirected and then missing (404 on the image host), or whose
     * address has a live 404 entry, is replaced by the original in a request of its own. A 404 from CAA itself means
     * there is no front image: the original's address is recorded too and not asked for.
     */
    suspend fun cover(source: CoverSource, size: CoverSize): CaaResult<CoverImage> {
        val original = CoverUrls.image(baseUrl, source, CoverSize.ORIGINAL)
        val thumbnail = if (size == CoverSize.ORIGINAL) null else CoverUrls.image(baseUrl, source, size)
        return fetch(thumbnail, original, front = source)
    }

    /**
     * The [size] image of the first of [sources] ([CoverUrls.sources]) that has a front image: the next source is asked
     * for only after [CaaResult.NotFound] for the one before, so a release without a front image gives way to its
     * release group (TI §5.2). A busy answer ends the attempt. [CoverImage.source] tells which source answered.
     */
    suspend fun cover(sources: List<CoverSource>, size: CoverSize): CaaResult<CoverImage> {
        for (source in sources) {
            val result = cover(source, size)
            if (result != CaaResult.NotFound) return result
        }
        return CaaResult.NotFound
    }

    /**
     * One image of a release index: its thumbnail of [px] pixels when it has one, else its original. Any 404 of the
     * thumbnail falls back to the image's own original, which the index listed.
     */
    suspend fun image(image: CaaImage, px: Int?): CaaResult<CoverImage> =
        fetch(px?.let(image::thumbnail), image.original, front = null)

    /** The images of a release (TI §5.2); [CaaResult.NotFound] for a release without images or an ID that is no MBID. */
    suspend fun releaseIndex(releaseId: String): CaaResult<CaaReleaseIndex> {
        val url = CoverUrls.releaseIndex(baseUrl, releaseId) ?: return CaaResult.NotFound
        recorded(url)?.let { return it.toResult() }
        return when (val answer = send(url, ::readIndex)) {
            is Answer.Got -> CaaResult.Found(answer.value)
            is Answer.Missing -> CaaResult.NotFound
            is Answer.Busy -> CaaResult.Busy(answer.until)
        }
    }

    /**
     * [thumbnail] (null: none wanted or none known), falling back to [original]. For the `front-*` addresses of [front],
     * a 404 from CAA itself means the entity has no front image, so the original is recorded instead of asked for, and a
     * live 404 entry of the original answers for every thumbnail, none of which exists without it.
     */
    private suspend fun fetch(thumbnail: HttpUrl?, original: HttpUrl, front: CoverSource?): CaaResult<CoverImage> {
        val frontAddresses = front != null
        if (frontAddresses && recorded(original)?.kind == FailureKind.NOT_FOUND) return CaaResult.NotFound
        if (thumbnail != null) {
            val entry = recorded(thumbnail)
            when (entry?.kind) {
                FailureKind.BUSY -> return CaaResult.Busy(entry.expiresAt)
                // A thumbnail known to be missing: straight to the original.
                FailureKind.NOT_FOUND -> Unit
                null -> when (val answer = send(thumbnail, ::readImage)) {
                    is Answer.Got -> return CaaResult.Found(answer.toCover(thumbnail, original = false, front))
                    is Answer.Busy -> return CaaResult.Busy(answer.until)
                    is Answer.Missing -> if (frontAddresses && !answer.fromImageHost) {
                        // CAA itself has no front image, so its original is missing too.
                        record(original, ttl.notFound())
                        return CaaResult.NotFound
                    }
                }
            }
        }
        recorded(original)?.let { return it.toResult() }
        return when (val answer = send(original, ::readImage)) {
            is Answer.Got -> CaaResult.Found(answer.toCover(original, original = true, front))
            is Answer.Missing -> CaaResult.NotFound
            is Answer.Busy -> CaaResult.Busy(answer.until)
        }
    }

    /**
     * The live failure entry of [url], if any, read on the I/O dispatcher: the app keeps its entries in a database, and
     * a caller may be on the main thread. Entries written while an answer is handled are on that dispatcher already
     * ([RetryingCaller]); [record] is for the others.
     */
    private suspend fun recorded(url: HttpUrl): FailureEntry? =
        withContext(ioDispatcher) { ttl.live(failures.get(url.toString())) }

    /** Records [entry] for [url] on the I/O dispatcher, as [recorded] reads it. */
    private suspend fun record(url: HttpUrl, entry: FailureEntry) {
        withContext(ioDispatcher) { failures.put(url.toString(), entry) }
    }

    private fun FailureEntry.toResult(): CaaResult<Nothing> = when (kind) {
        FailureKind.NOT_FOUND -> CaaResult.NotFound
        FailureKind.BUSY -> CaaResult.Busy(expiresAt)
    }

    private fun Answer.Got<Pair<ByteArray, ImageFormat>>.toCover(
        requested: HttpUrl,
        original: Boolean,
        source: CoverSource?,
    ): CoverImage {
        val (bytes, format) = value
        return CoverImage(bytes, format, ImageHeader.size(bytes), requested, url, original, source)
    }

    /**
     * Sends a GET for [url] and records its failures: a 404 for its address, a 503 for its address and as a pause of
     * the limiter; a success removes the address's entry. A blocked call records nothing ([RetryingCaller]).
     */
    private suspend fun <T> send(url: HttpUrl, read: (Response) -> CallOutcome<T>): Answer<T> =
        caller.execute(
            request = Request.Builder().url(url).get().build(),
            giveUp = { attempts, cause -> CaaException.Unavailable(attempts, cause) },
            paused = { remaining -> CaaException.Paused(remaining) },
        ) { response -> classify(url, response, read) }

    private fun <T> classify(asked: HttpUrl, response: Response, read: (Response) -> CallOutcome<T>): CallOutcome<Answer<T>> {
        val key = asked.toString()
        if (response.code == 503) {
            pauseAfterBusy(response)
            val entry = ttl.busy(failures.get(key))
            failures.put(key, entry)
            return CallOutcome.Success(Answer.Busy(entry.expiresAt))
        }
        busyInARow.set(0)
        return when {
            response.code == 404 -> {
                failures.put(key, ttl.notFound())
                // A redirect that stays on CAA (a release group sent on to one of its releases) is CAA's own answer.
                val answeredBy = response.request.url
                val fromImageHost = response.priorResponse != null &&
                    (answeredBy.host != asked.host || answeredBy.port != asked.port)
                CallOutcome.Success(Answer.Missing(fromImageHost))
            }
            response.isSuccessful -> when (val outcome = read(response)) {
                is CallOutcome.Success -> {
                    failures.remove(key)
                    CallOutcome.Success(Answer.Got(outcome.value, response.request.url))
                }
                is CallOutcome.Retry -> outcome
                is CallOutcome.Fail -> outcome
            }
            response.code in RETRIED_STATUSES -> {
                val serverNow = response.headers.getInstant("Date") ?: clock.instant()
                CallOutcome.Retry(CaaException.Http(response.code), RetryAfter.parse(response.header("Retry-After"), serverNow))
            }
            else -> CallOutcome.Fail(CaaException.Http(response.code))
        }
    }

    /** Holds every CAA request back by the backoff of TI §9: doubling on each 503 in a row, never shorter than Retry-After. */
    private fun pauseAfterBusy(response: Response) {
        val serverNow = response.headers.getInstant("Date") ?: clock.instant()
        val retryAfter = RetryAfter.parse(response.header("Retry-After"), serverNow)
        val wait = backoff.delayBefore(busyInARow.incrementAndGet(), retryAfter)
            // A Retry-After longer than a retry would wait: hold everyone back for it, within reason.
            ?: minOf(retryAfter ?: backoff.maxDelay, MAX_BUSY_PAUSE)
        limiter.pause(wait)
    }

    /** The body as an image of at most [maxImageBytes]; a text page is retried, other data fails. */
    private fun readImage(response: Response): CallOutcome<Pair<ByteArray, ImageFormat>> {
        val body = response.body
        if (body.contentLength() > maxImageBytes) return CallOutcome.Fail(CaaException.TooLarge(maxImageBytes))
        val source = body.source()
        // True when more than the limit is there to read, whatever the header said.
        if (source.request(maxImageBytes + 1)) return CallOutcome.Fail(CaaException.TooLarge(maxImageBytes))
        val bytes = source.buffer.readByteArray()
        ImageHeader.format(bytes)?.let { return CallOutcome.Success(bytes to it) }
        val type = response.header("Content-Type")
        return if (type?.trim()?.startsWith("text/", ignoreCase = true) == true) {
            // A captive portal's or a proxy's page, which a later attempt may get past.
            CallOutcome.Retry(CaaException.Unreadable(response.code))
        } else {
            CallOutcome.Fail(CaaException.NotAnImage(type))
        }
    }

    /** The body as CAA's release index; any other body is retried like MusicBrainz's (TI §8). */
    private fun readIndex(response: Response): CallOutcome<CaaReleaseIndex> = try {
        CallOutcome.Success(CaaReleaseIndex.parse(response.body.string(), baseUrl))
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException.
        CallOutcome.Retry(CaaException.Unreadable(response.code, e))
    }

    companion object {
        /** The largest image accepted; originals of a few MB are common, scans of tens of MB are not covers. */
        const val MAX_IMAGE_BYTES: Long = 32L * 1024 * 1024

        /** The longest a 503's Retry-After holds every CAA request back, as RetryingCaller's give-up pause. */
        private val MAX_BUSY_PAUSE = 1.hours

        /** Busy answers retried within the call as for MusicBrainz (TI §8, §9); a 503 is handled on its own. */
        private val RETRIED_STATUSES = setOf(429, 502, 504)
    }
}
