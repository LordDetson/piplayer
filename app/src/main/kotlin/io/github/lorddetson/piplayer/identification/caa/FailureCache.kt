package io.github.lorddetson.piplayer.identification.caa

import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** Why an image address failed. */
enum class FailureKind {
    /** 404: the image does not exist. */
    NOT_FOUND,

    /** 503: the service was busy. */
    BUSY,
}

/**
 * A failed image address (TI §5.2): live until [expiresAt]. [busyInARow] counts the 503s of the address in a row, so
 * the next one doubles even after this entry has expired; it is 0 for a 404.
 */
data class FailureEntry(val kind: FailureKind, val failedAt: Instant, val expiresAt: Instant, val busyInARow: Int) {
    fun isLive(now: Instant): Boolean = now < expiresAt
}

/** How long a failure keeps an address from being asked again (TI §5.2); pure, with the time from [clock]. */
class FailureTtlPolicy(private val clock: Clock) {

    /** A 404 now. */
    fun notFound(): FailureEntry = entry(FailureKind.NOT_FOUND, NOT_FOUND_TTL, busyInARow = 0)

    /** A 503 now, after [previous] (the address's entry, live or not, or null). */
    fun busy(previous: FailureEntry?): FailureEntry {
        val inARow = if (previous?.kind == FailureKind.BUSY) previous.busyInARow + 1 else 1
        // 5 min * 2^7 is already past the cap, so the shift never overflows.
        val ttl = minOf(MAX_BUSY_TTL, FIRST_BUSY_TTL * (1 shl minOf(inARow - 1, 7)))
        return entry(FailureKind.BUSY, ttl, inARow)
    }

    /** [entry] while it is live, otherwise null. */
    fun live(entry: FailureEntry?): FailureEntry? = entry?.takeIf { it.isLive(clock.instant()) }

    private fun entry(kind: FailureKind, ttl: Duration, busyInARow: Int): FailureEntry {
        val now = clock.instant()
        return FailureEntry(kind, now, now.plus(ttl.toJavaDuration()), busyInARow)
    }

    companion object {
        val NOT_FOUND_TTL: Duration = 7.days
        val FIRST_BUSY_TTL: Duration = 5.minutes
        val MAX_BUSY_TTL: Duration = 6.hours
    }
}

/**
 * The failed image addresses of Cover Art Archive (TI §5.2), so lists do not ask for them on every scroll. Expired
 * entries may be kept: [FailureTtlPolicy.busy] reads them. In memory in M3 ([InMemoryFailureCache]); `api_cache` in
 * the library database from M3 step 9 on.
 */
interface FailureCache {
    fun get(key: String): FailureEntry?

    fun put(key: String, entry: FailureEntry)

    fun remove(key: String)
}

/** A [FailureCache] in memory, keeping the [maxEntries] addresses used last. */
class InMemoryFailureCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) : FailureCache {

    /** In access order, so the eldest entry is the one used least recently. */
    private val entries = object : LinkedHashMap<String, FailureEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FailureEntry>): Boolean = size > maxEntries
    }

    init {
        require(maxEntries > 0) { "A failure cache keeps at least one entry: $maxEntries" }
    }

    override fun get(key: String): FailureEntry? = synchronized(entries) { entries[key] }

    override fun put(key: String, entry: FailureEntry) {
        synchronized(entries) { entries[key] = entry }
    }

    override fun remove(key: String) {
        synchronized(entries) { entries.remove(key) }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 4_096
    }
}
