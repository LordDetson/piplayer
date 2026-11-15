package io.github.lorddetson.piplayer.identification.release

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.fakes.Fixtures
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzJson
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.ReleaseCacheEntity
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The release cache in the library database (TI §5.1, §10.1; §11.2 M3 "Library database version 13"). */
@RunWith(AndroidJUnit4::class)
class RoomReleaseCacheTest {

    private val context = TestFiles.targetContext
    private val clock = MovableClock(Instant.parse("2026-11-15T10:00:00Z"))
    private var database = LibraryDatabase.open(context, file = null)
    private val file = File(context.cacheDir, "release-cache-test.db")

    @After
    fun close() {
        database.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    private fun cache() = RoomReleaseCache(LibraryAccess.of(database), clock)

    private val harbor: ReleaseDto = MusicBrainzJson.decodeFromString(Fixtures.text("musicbrainz/release-harbor-lights.json"))

    private fun release(id: String) = harbor.copy(id = id)

    @Test
    fun aReleaseIsKeptByItsMbidWithEveryValue() = runBlocking<Unit> {
        val cache = cache()
        assertThat(cache.get(harbor.id)).isNull()
        cache.put(harbor)
        assertThat(cache.get(harbor.id)).isEqualTo(harbor)
    }

    @Test
    fun aReleaseAnsweredForAnotherIdIsKeptUnderBoth() = runBlocking<Unit> {
        val cache = cache()
        val merged = release("11111111-2222-3333-4444-555555555555")
        cache.put(merged, requestedId = "66666666-7777-8888-9999-000000000000")
        assertThat(cache.get("66666666-7777-8888-9999-000000000000")).isEqualTo(merged)
        assertThat(cache.get(merged.id)).isEqualTo(merged)
        assertThat(database.identificationCache().releaseIds())
            .containsExactly(merged.id, "66666666-7777-8888-9999-000000000000")
    }

    @Test
    fun aReleaseSurvivesReopeningTheDatabase() = runBlocking<Unit> {
        database.close()
        SQLiteDatabase.deleteDatabase(file)
        database = LibraryDatabase.open(context, file)
        cache().put(harbor)
        database.close()

        database = LibraryDatabase.open(context, file)
        assertThat(cache().get(harbor.id)).isEqualTo(harbor)
    }

    @Test
    fun aRowOlderThan30DaysIsMissingAndTheNextWriteDropsIt() = runBlocking<Unit> {
        val cache = cache()
        val old = release("11111111-2222-3333-4444-555555555555")
        cache.put(old)

        clock.advance(30.days)
        assertThat(cache.get(old.id)).isEqualTo(old)
        clock.advance(1.milliseconds)
        assertThat(cache.get(old.id)).isNull()
        // A read drops nothing.
        assertThat(database.identificationCache().releaseIds()).containsExactly(old.id)

        val fresh = release("66666666-7777-8888-9999-000000000000")
        cache.put(fresh)
        assertThat(database.identificationCache().releaseIds()).containsExactly(fresh.id)
        assertThat(cache.get(fresh.id)).isEqualTo(fresh)
    }

    @Test
    fun aLookupAgainRenewsTheRow() = runBlocking<Unit> {
        val cache = cache()
        cache.put(harbor)
        clock.advance(20.days)
        cache.put(harbor)
        clock.advance(20.days)
        assertThat(cache.get(harbor.id)).isEqualTo(harbor)
    }

    @Test
    fun aRowThatIsNoReleaseIsAMiss() = runBlocking<Unit> {
        database.identificationCache().saveReleases(
            listOf(ReleaseCacheEntity("11111111-2222-3333-4444-555555555555", "<html>not a release</html>", clock.millis())),
        )
        assertThat(cache().get("11111111-2222-3333-4444-555555555555")).isNull()
    }

    /** A clock a test moves by hand. */
    private class MovableClock(private var now: Instant) : Clock() {
        fun advance(by: Duration) {
            now = now.plus(by.toJavaDuration())
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }
}
