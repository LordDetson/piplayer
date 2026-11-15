package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.Fixture
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdCandidates
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.RecordingScorer
import io.github.lorddetson.piplayer.identification.score.StageFinding
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.RecordingSearch
import io.github.lorddetson.piplayer.identification.text.TextSearch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The request budget of TI §3 on the fake services with virtual time: what one file costs from its first request to
 * the suggested edition, and that MusicBrainz requests stay at least 1.1 s apart.
 */
class LookupBudgetTest {

    @StartStop
    private val acoustIdServer = MockWebServer()

    @StartStop
    private val musicBrainzServer = MockWebServer()

    private val acoustId = FakeAcoustId()
    private val musicBrainz = FakeMusicBrainz().withHarborLights()
    private val fingerprint = "AQAAT0mUaEkSRZEGAA"
    private val hint = TrackHint("Paper Lanterns", "The Quiet Harbors", 214_000)
    private lateinit var log: RecordingRequestLog

    private fun TestScope.services(): IdentificationServices {
        log = RecordingRequestLog { testScheduler.currentTime }
        acoustIdServer.dispatcher = log.wrap(acoustId)
        musicBrainzServer.dispatcher = log.wrap(musicBrainz)
        return TestServices.services(
            baseUrls = TestServices.baseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/")),
            timeSource = testTimeSource,
        )
    }

    private fun musicBrainzRequests() = log.entries.filter { it.path.startsWith("/ws/2/") }

    private fun pages() = musicBrainzRequests().filter { it.path.startsWith("/ws/2/release?") }

    private fun assertSpacing() {
        musicBrainzRequests().map { it.timeMs }.zipWithNext().forEach { (earlier, later) ->
            assertThat(later - earlier).isGreaterThanOrEqualTo(1_100)
        }
    }

    @Test
    fun `a high-band fingerprint hit costs at most three requests plus the release pages`() = runTest {
        val services = services()
        acoustId.on(fingerprint, Fixture("acoustid/id-only.json"))

        val answer = services.acoustId("testKey123").lookup(fingerprint, hint.durationMs!!)
        val candidates = AcoustIdCandidates.from(answer, hint.durationMs)
        val ranking = RecordingScorer.rank(hint, listOf(StageFinding.Fingerprint(candidates)))
        assertThat(ranking.band).isEqualTo(Band.HIGH)
        val choice = ReleaseChooser(services.musicBrainz, InMemoryReleaseCache()).choose(ranking, ReleaseRequest(durationMs = 214_000))

        assertThat(choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(pages()).hasSize(2)
        assertThat(log.size - pages().size).isLessThanOrEqualTo(3)
        assertThat(log.entries.map { it.path.substringBefore('?') }).containsExactly(
            "/v2/lookup",
            "/ws/2/recording/${FakeMusicBrainz.HARBOR_RECORDING}",
            "/ws/2/release",
            "/ws/2/release",
            "/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}",
        )
        assertSpacing()
    }

    @Test
    fun `a text-only file costs at most four searches plus one release page and one release lookup`() = runTest {
        val services = services()
        // Every variant with the artist finds nothing, so all four variants go out; the last one finds the song.
        musicBrainz.onSearch("artist:", FakeMusicBrainz.SEARCH_EMPTY)
        musicBrainz.onBrowse(
            "recording",
            FakeMusicBrainz.HARBOR_RECORDING,
            mapOf(0 to Fixture("musicbrainz/release-browse-harbor.json")),
            status = "official",
        )

        val search = TextSearch(RecordingSearch { services.musicBrainz.search(it) }).search(hint)
        val ranking = RecordingScorer.rank(hint, listOf(StageFinding.TextSearch(search.candidates.map { it.candidate })))
        val choice = ReleaseChooser(services.musicBrainz, InMemoryReleaseCache()).choose(ranking, ReleaseRequest(durationMs = 214_000))

        assertThat(choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        val searches = musicBrainzRequests().filter { it.path.startsWith("/ws/2/recording?") }
        assertThat(searches).hasSize(LookupPlan.MAX_TEXT_VARIANTS)
        assertThat(musicBrainzRequests()).hasSize(LookupPlan.MAX_TEXT_VARIANTS + 2)
        assertThat(log.entries).noneMatch { it.path.startsWith("/v2/") }
        assertSpacing()
    }

    @Test
    fun `a file with recording and release IDs in its tags costs two requests`() = runTest {
        val services = services()
        val recording = services.musicBrainz.lookupRecording(FakeMusicBrainz.HARBOR_RECORDING)!!.toCandidate()
        val ranking = RecordingScorer.rank(hint, listOf(StageFinding.TagRecordingId(recording)))
        val choice = ReleaseChooser(services.musicBrainz, InMemoryReleaseCache())
            .choose(ranking, ReleaseRequest(tagReleaseId = FakeMusicBrainz.HARBOR_RELEASE, durationMs = 214_000))

        assertThat(choice.suggested!!.source).isEqualTo(EditionSource.TAG)
        assertThat(log.entries.map { it.path.substringBefore('?') }).containsExactly(
            "/ws/2/recording/${FakeMusicBrainz.HARBOR_RECORDING}",
            "/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}",
        )
        assertSpacing()
    }
}
