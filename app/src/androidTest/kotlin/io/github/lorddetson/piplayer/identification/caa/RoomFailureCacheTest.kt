package io.github.lorddetson.piplayer.identification.caa

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.db.ApiCacheEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The failure entries of Cover Art Archive in `api_cache` (TI §5.2, §10.1; §11.2 M3 "Library database version 13"). */
@RunWith(AndroidJUnit4::class)
class RoomFailureCacheTest {

    private val database = LibraryDatabase.open(TestFiles.targetContext, file = null)
    private val clock = MovableClock(Instant.parse("2026-11-15T10:00:00Z"))
    private val policy = FailureTtlPolicy(clock)
    private val cache = RoomFailureCache(LibraryAccess.of(database))

    @After
    fun close() = database.close()

    private fun keys() = runBlocking { database.identificationCache().apiKeys() }

    @Test
    fun theCacheKeepsReplacesAndRemovesEntries() {
        val busy = policy.busy(null)
        val missing = policy.notFound()

        cache.put("a", busy)
        assertThat(cache.get("a")).isEqualTo(busy)
        cache.put("a", missing)
        assertThat(cache.get("a")).isEqualTo(missing)
        cache.remove("a")
        assertThat(cache.get("a")).isNull()
        assertThat(cache.get("never")).isNull()
        cache.remove("never")
    }

    @Test
    fun anEntryKeepsItsKindItsTimesAndItsBusyCount() {
        val third = policy.busy(policy.busy(policy.busy(null)))
        assertThat(third.busyInARow).isEqualTo(3)
        cache.put("https://coverartarchive.org/release/x/front-500", third)

        val stored = cache.get("https://coverartarchive.org/release/x/front-500")!!
        assertThat(stored).isEqualTo(third)
        assertThat(stored.kind).isEqualTo(FailureKind.BUSY)
        assertThat(stored.expiresAt).isEqualTo(third.failedAt.plus(20.minutes.toJavaDuration()))
        val row = runBlocking { database.identificationCache().api("https://coverartarchive.org/release/x/front-500") }!!
        assertThat(row.status).isEqualTo(503)
        assertThat(row.body).isNull()
        assertThat(row.ttl).isEqualTo(20.minutes.inWholeMilliseconds)
        assertThat(row.busyInARow).isEqualTo(3)

        cache.put("missing", policy.notFound())
        val notFound = runBlocking { database.identificationCache().api("missing") }!!
        assertThat(notFound.status).isEqualTo(404)
        assertThat(notFound.busyInARow).isZero()
    }

    @Test
    fun theTtlRulesHoldThroughTheDatabase() {
        val key = "front"
        cache.put(key, policy.busy(cache.get(key)))
        assertThat(policy.live(cache.get(key))).isNotNull()

        // Expired exactly 6 hours ago: the row goes on, and the next 503 doubles.
        clock.advance(5.minutes + 6.hours)
        assertThat(policy.live(cache.get(key))).isNull()
        val second = policy.busy(cache.get(key))
        assertThat(second.busyInARow).isEqualTo(2)
        assertThat(second.expiresAt).isEqualTo(second.failedAt.plus(10.minutes.toJavaDuration()))
        cache.put(key, second)

        // A longer quiet time starts the row again at 5 minutes.
        clock.advance(10.minutes + 6.hours + 1.milliseconds)
        val third = policy.busy(cache.get(key))
        assertThat(third.busyInARow).isEqualTo(1)
        assertThat(third.expiresAt).isEqualTo(third.failedAt.plus(5.minutes.toJavaDuration()))

        // A 404 replaces a 503 entry, and lives 7 days.
        cache.put(key, policy.notFound())
        assertThat(cache.get(key)!!.kind).isEqualTo(FailureKind.NOT_FOUND)
        clock.advance(FailureTtlPolicy.NOT_FOUND_TTL - 1.milliseconds)
        assertThat(policy.live(cache.get(key))).isNotNull()
        clock.advance(1.milliseconds)
        assertThat(policy.live(cache.get(key))).isNull()
    }

    @Test
    fun anExpiredEntryStaysSixHoursForTheBusyRowAndTheNextWriteDropsItThen() {
        cache.put("old", policy.busy(null))
        cache.put("missing", policy.notFound())

        clock.advance(5.minutes + 6.hours)
        cache.put("other", policy.busy(null))
        assertThat(keys()).containsExactly("missing", "old", "other")

        clock.advance(1.milliseconds)
        assertThat(keys()).containsExactly("missing", "old", "other")
        cache.put("other", policy.busy(cache.get("other")))
        assertThat(keys()).containsExactly("missing", "other")
        assertThat(cache.get("old")).isNull()
    }

    @Test
    fun aRowThatIsNoFailureIsNoEntry() {
        runBlocking {
            database.identificationCache().saveApi(ApiCacheEntity("answer", 200, byteArrayOf(1), clock.millis(), 60_000, 0))
        }
        assertThat(cache.get("answer")).isNull()
    }

    /** A clock a test moves by hand. */
    private class MovableClock(private var now: Instant) : Clock() {
        fun advance(by: Duration) {
            now = now.plus(by.toJavaDuration())
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }
}
