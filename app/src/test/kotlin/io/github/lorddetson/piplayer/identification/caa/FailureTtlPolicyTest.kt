package io.github.lorddetson.piplayer.identification.caa

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** TI §11.1 "Cover URLs", failure TTLs: 404 for 7 days; 503 for 5 minutes, doubling on each further 503 up to 6 hours. */
class FailureTtlPolicyTest {

    private val clock = TestClock()
    private val policy = FailureTtlPolicy(clock)

    @Test
    fun `a 404 keeps the address for 7 days`() {
        val entry = policy.notFound()
        assertThat(entry).isEqualTo(FailureEntry(FailureKind.NOT_FOUND, clock.now, clock.after(7.days), busyInARow = 0))
    }

    @Test
    fun `a first 503 keeps the address for 5 minutes`() {
        val entry = policy.busy(previous = null)
        assertThat(entry).isEqualTo(FailureEntry(FailureKind.BUSY, clock.now, clock.after(5.minutes), busyInARow = 1))
    }

    @Test
    fun `each further 503 doubles the time, up to 6 hours`() {
        val minutes = mutableListOf<Long>()
        var entry: FailureEntry? = null
        repeat(10) {
            entry = policy.busy(entry).also { minutes += java.time.Duration.between(it.failedAt, it.expiresAt).toMinutes() }
            clock.advance(1.hours)
        }
        assertThat(minutes).containsExactly(5L, 10L, 20L, 40L, 80L, 160L, 320L, 360L, 360L, 360L)
    }

    @Test
    fun `an expired 503 entry still doubles the next one`() {
        val first = policy.busy(null)
        clock.advance(10.minutes)
        assertThat(policy.live(first)).isNull()

        val second = policy.busy(first)
        assertThat(second.expiresAt).isEqualTo(clock.after(10.minutes))
        assertThat(second.busyInARow).isEqualTo(2)
    }

    @Test
    fun `a 503 up to 6 hours after the previous 503 entry expired still doubles`() {
        val first = policy.busy(null)
        clock.advance(5.minutes + 6.hours)

        val second = policy.busy(first)
        assertThat(second.expiresAt).isEqualTo(clock.after(10.minutes))
        assertThat(second.busyInARow).isEqualTo(2)
    }

    @Test
    fun `a 503 more than 6 hours after the previous 503 entry expired starts again at 5 minutes`() {
        val first = policy.busy(null)
        clock.advance(5.minutes + 6.hours + 1.seconds)

        val second = policy.busy(first)
        assertThat(second.expiresAt).isEqualTo(clock.after(5.minutes))
        assertThat(second.busyInARow).isEqualTo(1)
    }

    @Test
    fun `a long row of 503s ends after a month without one`() {
        var entry: FailureEntry? = null
        repeat(8) { entry = policy.busy(entry) }
        assertThat(entry!!.busyInARow).isEqualTo(8)

        clock.advance(30.days)
        val next = policy.busy(entry)
        assertThat(next.expiresAt).isEqualTo(clock.after(5.minutes))
        assertThat(next.busyInARow).isEqualTo(1)
    }

    @Test
    fun `a 503 after a 404 starts again at 5 minutes`() {
        val entry = policy.busy(policy.notFound())
        assertThat(entry.expiresAt).isEqualTo(clock.after(5.minutes))
        assertThat(entry.busyInARow).isEqualTo(1)
    }

    @Test
    fun `an entry is live until it expires`() {
        val entry = policy.busy(null)
        assertThat(policy.live(entry)).isEqualTo(entry)
        clock.advance(5.minutes - 1.seconds)
        assertThat(policy.live(entry)).isEqualTo(entry)
        clock.advance(1.seconds)
        assertThat(policy.live(entry)).isNull()
        assertThat(policy.live(null)).isNull()
    }

    @Test
    fun `the cache in memory keeps, replaces and removes entries`() {
        val cache = InMemoryFailureCache()
        val busy = policy.busy(null)
        val missing = policy.notFound()

        cache.put("a", busy)
        assertThat(cache.get("a")).isEqualTo(busy)
        cache.put("a", missing)
        assertThat(cache.get("a")).isEqualTo(missing)
        cache.remove("a")
        assertThat(cache.get("a")).isNull()
        assertThat(cache.get("never")).isNull()
    }

    @Test
    fun `the cache in memory keeps the addresses used last`() {
        val cache = InMemoryFailureCache(maxEntries = 2)
        val entry = policy.notFound()
        cache.put("a", entry)
        cache.put("b", entry)
        cache.get("a")
        cache.put("c", entry)

        assertThat(cache.get("a")).isEqualTo(entry)
        assertThat(cache.get("b")).isNull()
        assertThat(cache.get("c")).isEqualTo(entry)
    }
}
