package io.github.lorddetson.piplayer.net

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What a client makes of one answer. */
sealed interface CallOutcome<out T> {

    data class Success<T>(val value: T) : CallOutcome<T>

    /** A busy answer: try again after the backoff, and never sooner than [retryAfter]. */
    data class Retry(val cause: Throwable, val retryAfter: Duration? = null) : CallOutcome<Nothing>

    /** An answer that a retry would not change. */
    data class Fail(val error: IOException) : CallOutcome<Nothing>
}

/**
 * Sends the requests of one service through the [limiter] of its host and retries busy answers and network errors as
 * [retryPolicy] allows (TI §9). Every wait is a pause of the shared limiter, so it holds back every caller and not
 * only the one that was turned away; that holds when the client gives up too, for at most [maxGiveUpPause]. Answers
 * are read on [ioDispatcher], so a client can be called from any thread.
 */
class RetryingCaller(
    private val http: OkHttpClient,
    private val limiter: RateLimiter,
    private val retryPolicy: RetryPolicy,
    private val ioDispatcher: CoroutineDispatcher,
    private val maxGiveUpPause: Duration = 1.hours,
) {

    /**
     * Sends [request] until [handle] makes a success or a failure of an answer; a network error counts as busy. When
     * no retry is left, the error from [giveUp] is thrown.
     */
    suspend fun <T> execute(
        request: Request,
        giveUp: (attempts: Int, cause: Throwable) -> IOException,
        handle: (Response) -> CallOutcome<T>,
    ): T {
        var attempt = 0
        while (true) {
            attempt++
            limiter.acquire()
            val outcome = try {
                http.newCall(request).await().use { response -> withContext(ioDispatcher) { handle(response) } }
            } catch (e: IOException) {
                CallOutcome.Retry(e)
            }
            when (outcome) {
                is CallOutcome.Success -> return outcome.value
                is CallOutcome.Fail -> throw outcome.error
                is CallOutcome.Retry -> {
                    val wait = retryPolicy.delayBefore(attempt, outcome.retryAfter)
                    if (wait == null) {
                        outcome.retryAfter?.let { limiter.pause(minOf(it, maxGiveUpPause)) }
                        throw giveUp(attempt, outcome.cause)
                    }
                    limiter.pause(wait)
                }
            }
        }
    }
}
