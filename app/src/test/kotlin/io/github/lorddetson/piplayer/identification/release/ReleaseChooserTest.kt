package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzLookups
import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseList
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.release.LookupPlanTest.Companion.candidate
import io.github.lorddetson.piplayer.identification.release.LookupPlanTest.Companion.match
import io.github.lorddetson.piplayer.identification.release.LookupPlanTest.Companion.ranking
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.RecordingScorer
import io.github.lorddetson.piplayer.identification.score.StageFinding
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.net.GateClosedException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Release choice over the fake MusicBrainz (TI §4.2, §5.1), within the caps of [LookupPlan]. */
class ReleaseChooserTest {

    @StartStop
    private val server = MockWebServer()

    private val fake = FakeMusicBrainz().withHarborLights()
    private val recording = FakeMusicBrainz.HARBOR_RECORDING
    private val hint = TrackHint("Paper Lanterns", "The Quiet Harbors", 214_000)
    private val cache = InMemoryReleaseCache()
    private lateinit var log: RecordingRequestLog

    private fun TestScope.services(): IdentificationServices {
        log = RecordingRequestLog { testScheduler.currentTime }
        server.dispatcher = log.wrap(fake)
        return TestServices.services(
            baseUrls = TestServices.baseUrls(server.url("/v2/"), server.url("/ws/2/")),
            timeSource = testTimeSource,
        )
    }

    private fun TestScope.chooser(folderHint: FolderReleaseHint = NoFolderReleaseHint) =
        ReleaseChooser(services().musicBrainz, cache, folderHint)

    /** The stage 1 ranking of the Harbor Lights recording, as a tag recording ID gives it. */
    private suspend fun tagRanking(services: IdentificationServices): Ranking {
        val found = services.musicBrainz.lookupRecording(recording)!!.toCandidate()
        return RecordingScorer.rank(hint, listOf(StageFinding.TagRecordingId(found)))
    }

    private fun paths() = log.entries.map { it.path.substringBefore("&fmt") }

    private fun browses() = log.entries.filter { it.path.startsWith("/ws/2/release?") }

    @Test
    fun `the ranking runs over every page, so the best release on page 2 is suggested`() = runTest {
        val real = chooser().choose(harborRanking(), ReleaseRequest(durationMs = 214_000, country = "GB"))
        assertThat(real.recordingId).isEqualTo(recording)
        assertThat(real.ranked.getValue(recording).map { it.release.id })
            .containsExactly(FakeMusicBrainz.HARBOR_RELEASE, "88888888-8888-4888-8888-888888888888")
        assertThat(real.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(real.suggested!!.source).isEqualTo(EditionSource.RANKING)
        assertThat(real.suggested!!.details.at).isEqualTo(TrackPosition(1, 3))
        assertThat(real.trace.single { it.recordingId == recording })
            .isEqualTo(BrowseTrace(recording, pages = 2, received = 2, total = 2, capped = false, nonOfficialFallback = false))
    }

    @Test
    fun `a tagged release that contains the recording is kept without browsing`() = runTest {
        val services = services()
        val chooser = ReleaseChooser(services.musicBrainz, cache)
        val ranking = tagRanking(services)
        val choice = chooser.choose(ranking, ReleaseRequest(tagReleaseId = FakeMusicBrainz.HARBOR_RELEASE, trackNumberTag = 11))

        assertThat(choice.suggested!!.source).isEqualTo(EditionSource.TAG)
        assertThat(choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(choice.suggested!!.details.at).isEqualTo(TrackPosition(1, 11))
        assertThat(browses()).isEmpty()
        assertThat(paths()).containsExactly(
            "/ws/2/recording/$recording?inc=artist-credits%2Bisrcs",
            "/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}?inc=recordings%2Bartist-credits%2Blabels%2Brelease-groups%2Bisrcs",
        )
    }

    @Test
    fun `a tagged release without the recording falls back to the ranking`() = runTest {
        val services = services()
        val choice = ReleaseChooser(services.musicBrainz, cache)
            .choose(tagRanking(services), ReleaseRequest(tagReleaseId = FakeMusicBrainz.HARBOR_PROMO_RELEASE))
        assertThat(choice.suggested!!.source).isEqualTo(EditionSource.RANKING)
        assertThat(choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(browses()).hasSize(2)
    }

    @Test
    fun `a tagged release MusicBrainz does not know falls back to the ranking`() = runTest {
        val services = services()
        val choice = ReleaseChooser(services.musicBrainz, cache)
            .choose(tagRanking(services), ReleaseRequest(tagReleaseId = "00000000-0000-4000-8000-000000000000"))
        assertThat(choice.suggested!!.source).isEqualTo(EditionSource.RANKING)
        assertThat(browses()).hasSize(2)
    }

    @Test
    fun `a broken tagged release ID sends nothing and the ranking runs`() = runTest {
        val choice = chooser().choose(harborRanking(), ReleaseRequest(tagReleaseId = "Harbor Lights"))
        assertThat(choice.suggested!!.source).isEqualTo(EditionSource.RANKING)
        assertThat(log.entries.filter { it.path.startsWith("/ws/2/release/") }.map { it.path.substringBefore('?') })
            .containsExactly("/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}")
    }

    @Test
    fun `the folder hint ranks a confirmed release first`() = runTest {
        val live = "88888888-8888-4888-8888-888888888888"
        val folders = mutableListOf<String?>()
        val hint = FolderReleaseHint { folder -> folders += folder; setOf(live) }
        val choice = chooser(hint).choose(harborRanking(), ReleaseRequest(folder = "Music/Harbors"))
        assertThat(choice.ranked.getValue(recording).first().release.id).isEqualTo(live)
        assertThat(folders).containsExactly("Music/Harbors")
    }

    @Test
    fun `a recording without any release gets no suggestion`() = runTest {
        val choice = chooser().choose(ranking(0.97), ReleaseRequest())
        assertThat(choice.suggested).isNull()
        assertThat(choice.trace.single().nonOfficialFallback).isTrue()
        assertThat(log.entries.filter { it.path.startsWith("/ws/2/release/") }).isEmpty()
    }

    @Test
    fun `no match browses and looks up nothing`() = runTest {
        val choice = chooser().choose(ranking(0.40), ReleaseRequest())
        assertThat(choice).isEqualTo(ReleaseChoice.NONE)
        assertThat(log.size).isZero()
    }

    @Test
    fun `the next track of the album takes the release from the cache`() = runTest {
        val chooser = chooser()
        chooser.choose(harborRanking(), ReleaseRequest(trackNumberTag = 3))
        val lookups = log.entries.count { it.path.startsWith("/ws/2/release/") }
        val second = chooser.choose(harborRanking(), ReleaseRequest(trackNumberTag = 11))
        assertThat(log.entries.count { it.path.startsWith("/ws/2/release/") }).isEqualTo(lookups)
        assertThat(second.suggested!!.details.at).isEqualTo(TrackPosition(1, 11))
    }

    @Test
    fun `a tagged release ID in upper case with spaces takes the release from the cache`() = runTest {
        val services = services()
        val chooser = ReleaseChooser(services.musicBrainz, cache)
        chooser.choose(tagRanking(services), ReleaseRequest(tagReleaseId = FakeMusicBrainz.HARBOR_RELEASE))
        val second = chooser.choose(
            tagRanking(services),
            ReleaseRequest(tagReleaseId = " ${FakeMusicBrainz.HARBOR_RELEASE.uppercase()} ", trackNumberTag = 11),
        )
        assertThat(second.suggested!!.source).isEqualTo(EditionSource.TAG)
        assertThat(second.suggested.details.at).isEqualTo(TrackPosition(1, 11))
        assertThat(log.entries.count { it.path.startsWith("/ws/2/release/") }).isEqualTo(1)
    }

    @Test
    fun `a merged tagged release is looked up once for the whole album`() = runTest {
        val merged = "49494949-4949-4949-8949-494949494949"
        fake.on("release/$merged", FakeMusicBrainz.HARBOR_RELEASE_LOOKUP)
        val services = services()
        val chooser = ReleaseChooser(services.musicBrainz, cache)
        repeat(3) { track ->
            val choice = chooser.choose(tagRanking(services), ReleaseRequest(tagReleaseId = merged, trackNumberTag = track + 1))
            assertThat(choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        }
        assertThat(log.entries.filter { it.path.startsWith("/ws/2/release/") }.map { it.path.substringBefore('?') })
            .containsExactly("/ws/2/release/$merged")
        assertThat(chooser.release(FakeMusicBrainz.HARBOR_RELEASE)!!.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(log.entries.count { it.path.startsWith("/ws/2/release/") }).isEqualTo(1)
    }

    @Test
    fun `the release group is browsed only when the edition picker opens`() = runTest {
        val chooser = chooser()
        val request = ReleaseRequest(country = "GB")
        val choice = chooser.choose(harborRanking(), request)
        assertThat(log.entries.filter { it.path.contains("release-group=") }).isEmpty()

        val list = chooser.editions(choice, request)
        assertThat(list.capped).isFalse()
        assertThat(list.totalCount).isEqualTo(3)
        val editions = list.editions
        assertThat(log.entries.filter { it.path.contains("release-group=${FakeMusicBrainz.HARBOR_GROUP}") }).hasSize(1)
        assertThat(editions.map { it.release.id }).containsExactly(
            FakeMusicBrainz.HARBOR_RELEASE,
            FakeMusicBrainz.HARBOR_CD_RELEASE,
            FakeMusicBrainz.HARBOR_PROMO_RELEASE,
        )

        val (suggested, cd, promo) = editions
        assertThat(chooser.open(suggested, choice, request)).isInstanceOf(EditionMapping.SameRecording::class.java)
        val other = chooser.open(cd, choice, request) as EditionMapping.OtherRecording
        assertThat(other.recordingId).isEqualTo("13131313-1313-4313-8313-131313131313")
        assertThat(chooser.open(promo, choice, request)).isEqualTo(EditionMapping.DoesNotContain)
        assertThat(log.entries.filter { it.path.startsWith("/ws/2/release/") }.map { it.path.substringBefore('?') })
            .containsExactly(
                "/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}",
                "/ws/2/release/${FakeMusicBrainz.HARBOR_CD_RELEASE}",
                "/ws/2/release/${FakeMusicBrainz.HARBOR_PROMO_RELEASE}",
            )
    }

    @Test
    fun `a capped browse is ranked as it is and traced`() = runTest {
        val releases = (0 until 1_000).map { ReleaseFixtures.release("r%04d".format(it), date = "${1000 + it}") }
        val lookups = FakeLookups(ReleaseList(releases, totalCount = 1_400, pages = 10, capped = true))
        val choice = ReleaseChooser(lookups, cache).choose(ranking(0.97), ReleaseRequest())
        assertThat(choice.trace.single()).isEqualTo(BrowseTrace("r0", 10, 1_000, 1_400, capped = true, nonOfficialFallback = false))
        assertThat(choice.ranked.getValue("r0").first().release.id).isEqualTo("r0000")
        assertThat(lookups.browsed).containsExactly("r0")
        assertThat(lookups.looked).containsExactly("r0000")
    }

    @Test
    fun `a capped release-group browse makes the picker's list partial`() = runTest {
        val harbor = ReleaseFixtures.lookup("release-harbor-lights.json")
        val details = ReleaseDetailsExtractor.extract(harbor, recording, null, 214_000)!!
        val choice = ReleaseChoice(recording, null, emptyMap(), SuggestedEdition(harbor, details, EditionSource.TAG), emptyList())
        val releases = (0 until 1_000).map { ReleaseFixtures.release("g%04d".format(it)) }
        val lookups = FakeLookups(ReleaseList(releases, totalCount = 1_250, pages = 10, capped = true))

        val list = ReleaseChooser(lookups, cache).editions(choice, ReleaseRequest())
        assertThat(list.capped).isTrue()
        assertThat(list.totalCount).isEqualTo(1_250)
        assertThat(list.editions).hasSize(1_001)
        assertThat(list.editions.first().release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
    }

    @Test
    fun `a failed browse of a runner-up is skipped and traced, the winner's still counts`() = runTest {
        val lookups = FakeLookups(ReleaseList(listOf(ReleaseFixtures.release("a")), 1, 1, false), failing = setOf("r1"))
        val choice = ReleaseChooser(lookups, cache).choose(ranking(0.85, 0.80), ReleaseRequest())
        assertThat(choice.ranked.keys).containsExactly("r0")
        assertThat(choice.trace.single { it.recordingId == "r1" }.failure).isInstanceOf(MusicBrainzException.Unavailable::class.java)
    }

    @Test
    fun `a failed browse of the winner, or a closed gate, ends the choice`() = runTest {
        val unavailable = FakeLookups(ReleaseList(emptyList(), 0, 1, false), failing = setOf("r0"))
        assertThat(runCatching { ReleaseChooser(unavailable, cache).choose(ranking(0.85, 0.80), ReleaseRequest()) }.exceptionOrNull())
            .isInstanceOf(MusicBrainzException.Unavailable::class.java)
        val blocked = FakeLookups(ReleaseList(emptyList(), 0, 1, false), blocked = setOf("r1"))
        assertThat(runCatching { ReleaseChooser(blocked, cache).choose(ranking(0.85, 0.80), ReleaseRequest()) }.exceptionOrNull())
            .isInstanceOf(GateClosedException::class.java)
    }

    @Test
    fun `a winner known by its ID only is looked up once`() = runTest {
        val idOnly = Ranking(listOf(match(candidate(recording, title = "", credit = "", length = null), 0.95)), 0.95, Band.HIGH)
        val choice = chooser().choose(idOnly, ReleaseRequest(durationMs = 214_000))
        assertThat(choice.recording!!.title).isEqualTo("Paper Lanterns")
        assertThat(log.entries.count { it.path.startsWith("/ws/2/recording/") }).isEqualTo(1)
        assertThat(choice.suggested!!.details.title).isEqualTo("Paper Lanterns")
    }

    /** The Harbor Lights recording as the winner of a fingerprint ranking. */
    private fun harborRanking(): Ranking =
        Ranking(listOf(match(candidate(recording, "Paper Lanterns", "The Quiet Harbors feat. Ada Vale", 214_000), 0.97)), 0.97, Band.HIGH)

    /** Answers every browse with [list]; looks up made-up releases that contain the recording "r0". */
    private class FakeLookups(
        private val list: ReleaseList,
        private val failing: Set<String> = emptySet(),
        private val blocked: Set<String> = emptySet(),
    ) : MusicBrainzLookups {
        val browsed = mutableListOf<String>()
        val looked = mutableListOf<String>()

        override suspend fun lookupRecording(mbid: String): RecordingDto? = null

        override suspend fun lookupRelease(mbid: String): ReleaseDto? {
            looked += mbid
            return null
        }

        override suspend fun releasesOfRecording(recordingId: String, maxPages: Int): ReleaseList {
            if (recordingId in blocked) throw GateClosedException()
            if (recordingId in failing) throw MusicBrainzException.Unavailable(4, null)
            browsed += recordingId
            return list
        }

        override suspend fun releasesOfReleaseGroup(groupId: String, maxPages: Int): ReleaseList = list
    }
}
