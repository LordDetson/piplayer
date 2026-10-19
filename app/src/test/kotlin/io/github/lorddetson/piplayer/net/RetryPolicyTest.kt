package io.github.lorddetson.piplayer.net

import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.AbstractLongAssert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import org.junit.jupiter.api.Test

class RetryPolicyTest {

    private fun assertMillis(duration: Duration?): AbstractLongAssert<*> = assertThat(duration?.inWholeMilliseconds)

    @RepeatedTest(20)
    fun `waits double from one second, randomly between half and all of it`(repetition: RepetitionInfo) {
        val policy = RetryPolicy(maxRetries = 6, random = Random(repetition.currentRepetition))
        assertMillis(policy.delayBefore(1, null)).isBetween(500, 1_000)
        assertMillis(policy.delayBefore(2, null)).isBetween(1_000, 2_000)
        assertMillis(policy.delayBefore(3, null)).isBetween(2_000, 4_000)
    }

    @Test
    fun `waits stay within the maximum and keep their jitter there`() {
        val waits = (1..20).map { seed -> RetryPolicy(maxRetries = 10, random = Random(seed)).delayBefore(10, null) }
        assertThat(waits.map { it?.inWholeMilliseconds }).allSatisfy { assertThat(it).isBetween(16_000, 32_000) }
        assertThat(waits.toSet()).hasSizeGreaterThan(1)
    }

    @Test
    fun `no wait is given once the retries are used up`() {
        val policy = RetryPolicy(maxRetries = 3)
        assertThat(policy.delayBefore(3, null)).isNotNull()
        assertThat(policy.delayBefore(4, null)).isNull()
    }

    @Test
    fun `a Retry-After longer than the backoff is waited out, plus up to a second of jitter`() {
        val waits = (1..20).map { seed -> RetryPolicy(maxRetries = 3, random = Random(seed)).delayBefore(1, 10.seconds) }
        assertThat(waits.map { it?.inWholeMilliseconds }).allSatisfy { assertThat(it).isBetween(10_000, 11_000) }
        assertThat(waits.toSet()).hasSizeGreaterThan(1)
    }

    @Test
    fun `a Retry-After of zero is ignored`() {
        val policy = RetryPolicy(maxRetries = 3, random = Random(1))
        assertMillis(policy.delayBefore(1, 0.seconds)).isBetween(500, 1_000)
    }

    @Test
    fun `a Retry-After longer than the allowed maximum ends the retries`() {
        val policy = RetryPolicy(maxRetries = 3)
        assertThat(policy.delayBefore(1, 300.seconds)).isNull()
    }

    @Test
    fun `Retry-After is read as delta seconds or as an HTTP date`() {
        val now = Instant.parse("2026-10-18T12:00:00Z")
        assertThat(RetryAfter.parse("7", now)).isEqualTo(7.seconds)
        assertThat(RetryAfter.parse("0", now)).isEqualTo(0.seconds)
        assertThat(RetryAfter.parse("Sun, 18 Oct 2026 12:00:30 GMT", now)).isEqualTo(30.seconds)
        assertThat(RetryAfter.parse("Sun, 18 Oct 2026 11:59:00 GMT", now)).isEqualTo(0.seconds)
    }

    @Test
    fun `a Retry-After too long to count ends the retries instead of being ignored`() {
        val parsed = RetryAfter.parse("40000000000000000000", Instant.parse("2026-10-18T12:00:00Z"))
        assertThat(parsed).isNotNull()
        assertThat(RetryPolicy(maxRetries = 3).delayBefore(1, parsed)).isNull()
    }

    @Test
    fun `an absent or unreadable Retry-After is null`() {
        val now = Instant.parse("2026-10-18T12:00:00Z")
        assertThat(RetryAfter.parse(null, now)).isNull()
        assertThat(RetryAfter.parse("soon", now)).isNull()
        assertThat(RetryAfter.parse("-5", now)).isNull()
    }
}
