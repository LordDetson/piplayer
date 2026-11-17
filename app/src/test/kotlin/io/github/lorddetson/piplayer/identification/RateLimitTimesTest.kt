package io.github.lorddetson.piplayer.identification

import androidx.datastore.preferences.core.longPreferencesKey
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.settings.MemoryDataStore
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The rate limiters' last-request times in the `device_state` file (TI §8, §10): one key per provider, across processes. */
@OptIn(ExperimentalCoroutinesApi::class)
class RateLimitTimesTest {

    /** A base beyond the explored virtual time, so wall times stay positive and in step with it. */
    private val wallBase = 10_000L

    private fun TestScope.wallClock() = { wallBase + testScheduler.currentTime }

    @Test
    fun `every provider keeps its own last request time`() = runTest {
        val file = MemoryDataStore()
        val times = RateLimitTimes(file)

        // AcoustID sent one request in a process that has since died.
        RateLimiter(1_000.milliseconds, testTimeSource, wallClock(), times.forProvider(ProviderId.ACOUSTID)).acquire()

        // MusicBrainz has its own history: nothing was stored for it.
        assertThat(times.forProvider(ProviderId.MUSICBRAINZ).restore()).isNull()
        assertThat(file.data.first()[longPreferencesKey("rate_limit_acoustid_last_request")]).isEqualTo(wallBase)
    }

    @Test
    fun `a provider is held back across processes by its own history only`() = runTest {
        val file = MemoryDataStore()
        val times = RateLimitTimes(file)
        RateLimiter(1_000.milliseconds, testTimeSource, wallClock(), times.forProvider(ProviderId.ACOUSTID)).acquire()
        advanceTimeBy(100)

        // The same provider in a new process waits out the rest of the interval.
        RateLimiter(1_000.milliseconds, testTimeSource, wallClock(), times.forProvider(ProviderId.ACOUSTID)).acquire()
        assertThat(testScheduler.currentTime).isEqualTo(1_000)

        // A different provider has no history of its own and goes at once.
        RateLimiter(1_000.milliseconds, testTimeSource, wallClock(), times.forProvider(ProviderId.MUSICBRAINZ)).acquire()
        assertThat(testScheduler.currentTime).isEqualTo(1_000)
    }

    @Test
    fun `a fresh file holds nothing back for any provider`() = runTest {
        val times = RateLimitTimes(MemoryDataStore())
        assertThat(times.forProvider(ProviderId.ACOUSTID).restore()).isNull()
        assertThat(times.forProvider(ProviderId.MUSICBRAINZ).restore()).isNull()
        assertThat(times.forProvider(ProviderId.COVER_ART_ARCHIVE).restore()).isNull()
    }

    @Test
    fun `one key per provider, under the names of TI 10`() = runTest {
        val file = MemoryDataStore()
        val times = RateLimitTimes(file)
        RateLimiter(1_000.milliseconds, testTimeSource, wallClock(), times.forProvider(ProviderId.COVER_ART_ARCHIVE)).acquire()

        assertThat(file.data.first().asMap().keys.map { it.name })
            .containsExactly("rate_limit_cover_art_archive_last_request")
        assertThat(RateLimitTimes.keyNames()).containsExactly(
            "rate_limit_acoustid_last_request",
            "rate_limit_musicbrainz_last_request",
            "rate_limit_cover_art_archive_last_request",
        )
    }
}