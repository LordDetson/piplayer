package io.github.lorddetson.piplayer.net

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Waits between retries of a request the server turned away: doubling from [baseDelay] up to [maxDelay], each wait
 * taken at random between half and all of that step so that many devices do not retry in step, and never shorter
 * than the server's Retry-After (plus up to a second of jitter).
 */
class RetryPolicy(
    val maxRetries: Int,
    val baseDelay: Duration = 1.seconds,
    val maxDelay: Duration = 32.seconds,
    val maxRetryAfter: Duration = 120.seconds,
    private val random: Random = Random.Default,
) {

    /** The longest wait before a retry: a Retry-After of [maxRetryAfter] plus its jitter, or the backoff cap. */
    val longestWait: Duration get() = maxOf(maxDelay, maxRetryAfter + RETRY_AFTER_JITTER)

    /**
     * The wait before retry number [retry] (1 for the first retry), or null when no retry is left or the server asks
     * for a wait longer than [maxRetryAfter]. A Retry-After of zero is ignored.
     */
    fun delayBefore(retry: Int, retryAfter: Duration?): Duration? {
        require(retry >= 1) { "Retries are counted from 1: $retry" }
        if (retry > maxRetries) return null
        if (retryAfter != null && retryAfter > maxRetryAfter) return null
        val step = minOf(maxDelay, baseDelay * (1L shl minOf(retry - 1, 30)).toDouble())
        val jittered = step * (0.5 + 0.5 * random.nextDouble())
        return if (retryAfter != null && retryAfter >= jittered) {
            retryAfter + RETRY_AFTER_JITTER * random.nextDouble()
        } else {
            jittered
        }
    }

    private companion object {
        val RETRY_AFTER_JITTER = 1.seconds
    }
}

/** The Retry-After header: delta seconds or an HTTP date. */
object RetryAfter {

    private val digits = Regex("""\d+""")

    /** [now] is the server's time when known (its Date header), so a clock difference does not move an HTTP date. */
    fun parse(value: String?, now: Instant): Duration? {
        val text = value?.trim() ?: return null
        // A number too long for a Long is still an answer: wait forever, which ends the retries.
        if (digits.matches(text)) return text.toLongOrNull()?.seconds ?: Duration.INFINITE
        val date = try {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            return null
        }
        return java.time.Duration.between(now, date).toKotlinDuration().coerceAtLeast(Duration.ZERO)
    }
}
