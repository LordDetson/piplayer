package io.github.lorddetson.piplayer.identification.musicbrainz

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.Fixture
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.provider.ProviderAccess
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatus
import io.github.lorddetson.piplayer.identification.provider.ProviderStatusStore
import io.github.lorddetson.piplayer.identification.provider.ProviderStoppedException
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.score.HintField
import io.github.lorddetson.piplayer.identification.score.RecordingScorer
import io.github.lorddetson.piplayer.identification.score.StageFinding
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.MusicBrainzRef
import io.github.lorddetson.piplayer.identification.text.QueryVariant
import io.github.lorddetson.piplayer.identification.text.RecordingQuery
import io.github.lorddetson.piplayer.identification.text.TextQueryBuilder
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.OnlineConsent
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.UserAgent
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The lookup and browse endpoints of TI §3 rows 1-2, §4.2 and §5.1 against MockWebServer. */
class MusicBrainzLookupsTest {

    @StartStop
    private val server = MockWebServer()

    private val fake = FakeMusicBrainz()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val live = "cc7867d1-34a5-4bdd-a9b5-faff27ce77fb"
    private val liveRelease = "157cb7bb-217f-49d4-a305-64e49df5a0a7"
    private val studio = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
    private val liveGroup = "c88dba71-c542-3424-b746-05f6c8f3e060"
    private val unknown = "00000000-0000-4000-8000-000000000000"

    private fun serve(dispatcher: Dispatcher = fake) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return dispatcher.dispatch(request)
            }
        }
    }

    private fun TestScope.client(
        gate: InMemoryOnlineGate = TestServices.openGate(),
        statuses: ProviderStatusStore = ProviderStatusStore(),
    ) = MusicBrainzClient(
        http = HttpClients.create(UserAgent.of("0.1.0"), gate),
        limiter = RateLimiter(10.milliseconds, testTimeSource),
        retryPolicy = RetryPolicy(maxRetries = 3, random = Random(1)),
        access = ProviderAccess(ProviderId.MUSICBRAINZ, gate, TestServices.FakeConnectivity(), statuses),
        baseUrl = server.url("/ws/2/"),
    )

    private fun fixture(name: String) = Fixture("musicbrainz/$name")

    // Stage 1: recording lookup.

    @Test
    fun `a recording is looked up by its MBID with its credit and ISRCs`() = runTest {
        serve(fake.on("recording/$live", fixture("recording-lookup.json")))
        val recording = client().lookupRecording(live)

        val request = requests.single()
        assertThat(request.url.encodedPath).isEqualTo("/ws/2/recording/$live")
        assertThat(request.url.queryParameter("inc")).isEqualTo("artist-credits+isrcs")
        assertThat(request.url.queryParameter("fmt")).isEqualTo("json")
        assertThat(recording!!.title).isEqualTo("Bohemian Rhapsody")
        assertThat(recording.length).isEqualTo(280_893)
        assertThat(recording.isrcs).containsExactly("GBCEE8600016")
        assertThat(recording.creditString()).isEqualTo("Queen")
    }

    @Test
    fun `an unknown recording MBID is a 404, which gives null at once`() = runTest {
        serve()
        assertThat(client().lookupRecording(unknown)).isNull()
        assertThat(requests).hasSize(1)
        assertThat(testScheduler.currentTime).isZero()
    }

    @Test
    fun `an ID from a broken tag that is no MBID sends nothing, and an upper-case one is sent in lower case`() = runTest {
        serve(fake.withHarborLights())
        val client = client()
        assertThat(client.lookupRecording("not-an-mbid")).isNull()
        assertThat(client.lookupRelease("${FakeMusicBrainz.HARBOR_RELEASE}x")).isNull()
        assertThat(client.lookupRelease("")).isNull()
        assertThat(requests).isEmpty()

        assertThat(client.lookupRelease(" ${FakeMusicBrainz.HARBOR_RELEASE.uppercase()} ")!!.id)
            .isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(requests.single().url.encodedPath).isEqualTo("/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}")
    }

    // Stage 2: ISRC.

    @Test
    fun `an ISRC is looked up in its plain form`() = runTest {
        serve(fake.on("isrc/GBCEE8600016", fixture("isrc-gbcee8600016.json")))
        val recordings = client().lookupIsrc(" gb-cee-86-00016 ")

        assertThat(requests.single().url.encodedPath).isEqualTo("/ws/2/isrc/GBCEE8600016")
        assertThat(requests.single().url.queryParameter("inc")).isEqualTo("artists")
        assertThat(requests.single().url.queryParameter("fmt")).isEqualTo("json")
        assertThat(recordings.map { it.id }).containsExactly(live)
        assertThat(recordings.single().length).isEqualTo(280_893)
    }

    @Test
    fun `an ISRC answer carries the credits, so an artist that disagrees with the tags shows in the reason`() = runTest {
        serve()
        val recordings = client().lookupIsrc(FakeMusicBrainz.KNOWN_ISRC)
        assertThat(requests.single().url.queryParameter("inc")).isEqualTo("artists")
        assertThat(recordings.single().creditString()).isEqualTo("The Quiet Harbors")

        val hint = TrackHint("Paper Lanterns", "Someone Else Entirely", 214_000)
        val best = RecordingScorer.rank(hint, listOf(StageFinding.Isrc(recordings.map { it.toCandidate() }))).best!!
        assertThat(best.score).isEqualTo(RecordingScorer.ISRC_SCORE)
        assertThat(best.reason.disagreement).containsExactly(HintField.ARTIST)
    }

    @Test
    fun `an unknown ISRC gives no recordings, and text that is no ISRC sends nothing`() = runTest {
        serve()
        val client = client()
        assertThat(client.lookupIsrc("ZZZZZ0000000")).isEmpty()
        assertThat(requests).hasSize(1)
        assertThat(client.lookupIsrc("not an isrc")).isEmpty()
        assertThat(client.lookupIsrc("GBCEE86000")).isEmpty()
        assertThat(requests).hasSize(1)
    }

    // Stage 4: text search.

    @Test
    fun `a manual query goes out with dismax and its limit, an automatic one without dismax`() = runTest {
        serve()
        val client = client()
        val candidates = client.search(TextQueryBuilder.manual("bohemian rhapsody queen")!!)
        client.search(RecordingQuery(QueryVariant.TITLE_ARTIST, "recording:(paper) AND artist:(harbors)", 25))

        val (manual, automatic) = requests
        assertThat(manual.url.queryParameter("query")).isEqualTo("bohemian rhapsody queen")
        assertThat(manual.url.queryParameter("dismax")).isEqualTo("true")
        assertThat(manual.url.queryParameter("limit")).isEqualTo("50")
        assertThat(automatic.url.queryParameter("dismax")).isNull()
        assertThat(automatic.url.queryParameter("limit")).isEqualTo("25")
        assertThat(candidates.map { it.recordingId })
            .containsExactly(FakeMusicBrainz.HARBOR_RECORDING, "12121212-1212-4212-8212-121212121212")
    }

    // Stage 6: release lookup.

    @Test
    fun `a release is looked up with the includes of TI 5_1, joined by an encoded plus`() = runTest {
        serve(fake.on("release/$liveRelease", fixture("release-lookup.json")))
        val release = client().lookupRelease(liveRelease)!!

        val request = requests.single()
        assertThat(request.url.encodedPath).isEqualTo("/ws/2/release/$liveRelease")
        assertThat(request.url.queryParameter("inc")).isEqualTo("recordings+artist-credits+labels+release-groups+isrcs")
        // The server takes a literal '+' and %2B alike (checked on 15.11); OkHttp sends %2B.
        assertThat(request.target).contains("inc=recordings%2Bartist-credits%2Blabels%2Brelease-groups%2Bisrcs")
        assertThat(request.url.queryParameter("fmt")).isEqualTo("json")

        assertThat(release.barcode).isEqualTo("4988031330043")
        assertThat(release.labelInfo.map { it.label?.name to it.catalogNumber }).containsExactly("Virgin" to "UICY-15820")
        assertThat(release.releaseEvents.single().area!!.isoCodes).containsExactly("JP")
        assertThat(release.releaseGroup!!.firstReleaseDate).isEqualTo("1986-12-01")
        assertThat(release.coverArtArchive).isEqualTo(CoverArtArchiveDto(count = 0, front = false))
        val medium = release.media.single()
        assertThat(medium.title).isEmpty()
        assertThat(medium.tracks).hasSize(15)
        val track = medium.tracks[8]
        assertThat(track.position).isEqualTo(9)
        assertThat(track.recording!!.id).isEqualTo(live)
        assertThat(track.recording!!.isrcs).containsExactly("GBCEE8600016")
        assertThat(track.artistCredit.single().artist!!.sortName).isEqualTo("Queen")
    }

    @Test
    fun `an unknown release is a 404, which gives null`() = runTest {
        serve()
        assertThat(client().lookupRelease(unknown)).isNull()
        assertThat(requests).hasSize(1)
    }

    // Stage 5: release browse.

    @Test
    fun `a recording's releases are browsed official, with release groups, media and labels, 100 at a time`() = runTest {
        serve(fake.onBrowse("recording", live, mapOf(0 to fixture("release-browse-live.json")), status = "official"))
        val page = client().browseReleasesOfRecording(live, offset = 0, officialOnly = true)

        val request = requests.single()
        assertThat(request.url.encodedPath).isEqualTo("/ws/2/release")
        assertThat(request.url.queryParameter("recording")).isEqualTo(live)
        assertThat(request.url.queryParameter("status")).isEqualTo("official")
        assertThat(request.url.queryParameter("inc")).isEqualTo("release-groups+media+labels")
        assertThat(request.target).contains("inc=release-groups%2Bmedia%2Blabels")
        assertThat(request.url.queryParameter("limit")).isEqualTo("100")
        assertThat(request.url.queryParameter("offset")).isEqualTo("0")
        assertThat(request.url.queryParameter("fmt")).isEqualTo("json")
        assertThat(page.releaseCount).isEqualTo(12)
        assertThat(page.releases).hasSize(12)
        assertThat(page.releases.first().releaseGroup!!.secondaryTypes).containsExactly("Live")
    }

    @Test
    fun `a release group's releases are browsed in every status, with media and labels`() = runTest {
        serve(fake.onBrowse("release-group", liveGroup, mapOf(0 to fixture("release-group-browse.json"))))
        val page = client().browseReleasesOfReleaseGroup(liveGroup, offset = 0)

        val request = requests.single()
        assertThat(request.url.queryParameter("release-group")).isEqualTo(liveGroup)
        assertThat(request.url.queryParameter("status")).isNull()
        assertThat(request.url.queryParameter("inc")).isEqualTo("media+labels")
        assertThat(request.target).contains("inc=media%2Blabels")
        assertThat(request.url.queryParameter("limit")).isEqualTo("100")
        assertThat(page.releases).hasSize(12)
    }

    @Test
    fun `pages follow the number of releases actually returned`() = runTest {
        serve(fake.onBrowse("recording", studio, pagingPair(), status = "official"))
        val list = client().releasesOfRecording(studio)

        // The first page brought 8 of 14 although the limit is 100, so the next offset is 8.
        assertThat(requests.map { it.url.queryParameter("offset") }).containsExactly("0", "8")
        assertThat(list.releases).hasSize(14)
        assertThat(list.releases.map { it.id }).doesNotHaveDuplicates()
        assertThat(list.totalCount).isEqualTo(14)
        assertThat(list.pages).isEqualTo(2)
        assertThat(list.capped).isFalse()
        assertThat(list.nonOfficialFallback).isFalse()
    }

    @Test
    fun `an empty page ends the walk before the reported count`() = runTest {
        serve(fake.onBrowse("recording", studio, mapOf(0 to pagingPair().getValue(0)), status = "official"))
        val list = client().releasesOfRecording(studio)
        assertThat(requests).hasSize(2)
        assertThat(list.releases).hasSize(8)
        assertThat(list.capped).isFalse()
    }

    @Test
    fun `the walk stops at 10 pages and says that it was capped`() = runTest {
        serve(OneReleasePerPage(count = 50))
        val list = client().releasesOfRecording(studio)
        assertThat(requests).hasSize(MusicBrainzClient.MAX_PAGES)
        assertThat(list.releases).hasSize(10)
        assertThat(list.totalCount).isEqualTo(50)
        assertThat(list.capped).isTrue()
    }

    @Test
    fun `a walk that ends on the last page within the cap is not capped`() = runTest {
        serve(OneReleasePerPage(count = 10))
        val list = client().releasesOfRecording(studio)
        assertThat(requests).hasSize(10)
        assertThat(list.capped).isFalse()
    }

    @Test
    fun `without an official release the browse is repeated without a status`() = runTest {
        serve(fake.onBrowse("recording", studio, mapOf(0 to fixture("release-browse-unofficial.json"))))
        val list = client().releasesOfRecording(studio)

        assertThat(requests.map { it.url.queryParameter("status") }).containsExactly("official", null)
        assertThat(list.nonOfficialFallback).isTrue()
        assertThat(list.releases.single().status).isEqualTo("Bootleg")
        assertThat(list.pages).isEqualTo(2)
    }

    @Test
    fun `a recording with official releases never asks for the others`() = runTest {
        serve(fake.onBrowse("recording", live, mapOf(0 to fixture("release-browse-live.json")), status = "official"))
        val list = client().releasesOfRecording(live)
        assertThat(requests).hasSize(1)
        assertThat(list.releases).hasSize(12)
        assertThat(list.nonOfficialFallback).isFalse()
    }

    @Test
    fun `a release group's releases list the official ones first`() = runTest {
        serve(fake.withHarborLights())
        val list = client().releasesOfReleaseGroup(FakeMusicBrainz.HARBOR_GROUP)
        assertThat(list.releases.map { it.status }).containsExactly("Official", "Official", "Promotion")
        assertThat(requests.single().url.queryParameter("status")).isNull()
    }

    @Test
    fun `a release group's non-official releases follow the official ones whatever the server's order`() = runTest {
        serve(fake.onBrowse("release-group", liveGroup, mapOf(0 to fixture("release-group-promo-first.json"))))
        val list = client().releasesOfReleaseGroup(liveGroup)
        assertThat(list.releases.map { it.status }).containsExactly("Official", "Official", "Promotion", null)
        assertThat(list.releases.map { it.id.takeLast(2) }).containsExactly("03", "04", "01", "02")
    }

    // Pasted links and IDs (TI §3.3).

    @Test
    fun `a pasted recording or release link is looked up as what it names`() = runTest {
        serve(fake.withHarborLights())
        val client = client()
        val recording = client.lookup(MusicBrainzRef.Recording(FakeMusicBrainz.HARBOR_RECORDING))
        val release = client.lookup(MusicBrainzRef.Release(FakeMusicBrainz.HARBOR_RELEASE))
        assertThat((recording as RefLookup.Recording).recording.title).isEqualTo("Paper Lanterns")
        assertThat((release as RefLookup.Release).release.title).isEqualTo("Harbor Lights")
        assertThat(client.lookup(MusicBrainzRef.Release(unknown))).isEqualTo(RefLookup.NotFound)
        assertThat(requests.map { it.url.encodedPath }).containsExactly(
            "/ws/2/recording/${FakeMusicBrainz.HARBOR_RECORDING}",
            "/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}",
            "/ws/2/release/$unknown",
        )
    }

    @Test
    fun `a bare MBID is tried as a recording, then as a release`() = runTest {
        serve(fake.withHarborLights())
        val client = client()
        val asRecording = client.lookup(MusicBrainzRef.AnyEntity(FakeMusicBrainz.HARBOR_RECORDING))
        assertThat(asRecording).isInstanceOf(RefLookup.Recording::class.java)
        assertThat(requests).hasSize(1)

        val asRelease = client.lookup(MusicBrainzRef.AnyEntity(FakeMusicBrainz.HARBOR_RELEASE))
        assertThat((asRelease as RefLookup.Release).release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(requests.drop(1).map { it.url.encodedPath }).containsExactly(
            "/ws/2/recording/${FakeMusicBrainz.HARBOR_RELEASE}",
            "/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}",
        )

        assertThat(client.lookup(MusicBrainzRef.AnyEntity(unknown))).isEqualTo(RefLookup.NotFound)
    }

    @Test
    fun `an unsupported link is never looked up`() = runTest {
        serve()
        val error = runCatching { client().lookup(MusicBrainzRef.Unsupported("artist")) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(requests).isEmpty()
    }

    // The gate and the sticky stop (TI §8, §9).

    @Test
    fun `nothing is looked up or browsed while online lookups are off`() = runTest {
        serve(fake.withHarborLights())
        val client = client(gate = InMemoryOnlineGate())
        val calls = listOf<suspend () -> Any?>(
            { client.lookupRecording(FakeMusicBrainz.HARBOR_RECORDING) },
            { client.lookupIsrc(FakeMusicBrainz.KNOWN_ISRC) },
            { client.lookupRelease(FakeMusicBrainz.HARBOR_RELEASE) },
            { client.releasesOfRecording(FakeMusicBrainz.HARBOR_RECORDING) },
            { client.releasesOfReleaseGroup(FakeMusicBrainz.HARBOR_GROUP) },
            { client.lookup(MusicBrainzRef.AnyEntity(FakeMusicBrainz.HARBOR_RELEASE)) },
        )
        for (call in calls) {
            assertThat(runCatching { call() }.exceptionOrNull()).isInstanceOf(GateClosedException::class.java)
        }
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a stopped MusicBrainz sends no lookup and no browse`() = runTest {
        serve(fake.withHarborLights())
        val statuses = ProviderStatusStore().apply { stop(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED) }
        val client = client(statuses = statuses)
        assertThat(runCatching { client.lookupRelease(FakeMusicBrainz.HARBOR_RELEASE) }.exceptionOrNull())
            .isInstanceOf(ProviderStoppedException::class.java)
        assertThat(runCatching { client.releasesOfRecording(FakeMusicBrainz.HARBOR_RECORDING) }.exceptionOrNull())
            .isInstanceOf(ProviderStoppedException::class.java)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a User-Agent refusal on a later page stops MusicBrainz and ends the walk`() = runTest {
        val statuses = ProviderStatusStore()
        fake.onBrowse("recording", studio, pagingPair(), status = "official")
        val client = client(statuses = statuses)
        val firstPageOnly = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.queryParameter("offset") == "0") fake.dispatch(request) else FakeMusicBrainz.userAgentRejected()
        }
        serve(firstPageOnly)

        val error = runCatching { client.releasesOfRecording(studio) }.exceptionOrNull()
        assertThat(error).isInstanceOf(MusicBrainzException.UserAgentRejected::class.java)
        assertThat(statuses.current(ProviderId.MUSICBRAINZ)).isEqualTo(ProviderStatus.Stopped(StopReason.USER_AGENT_REJECTED))
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `closing the gate between pages ends the walk without another request`() = runTest {
        val gate = TestServices.openGate()
        val closing = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                gate.set(OnlineConsent.NONE)
                return fake.dispatch(request)
            }
        }
        fake.onBrowse("recording", studio, pagingPair(), status = "official")
        serve(closing)

        val error = runCatching { client(gate = gate).releasesOfRecording(studio) }.exceptionOrNull()
        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(requests).hasSize(1)
    }

    private fun pagingPair() = mapOf(
        0 to fixture("release-browse-paging-1.json"),
        8 to fixture("release-browse-paging-2.json"),
    )

    /** A browse that returns one made-up release per page, whatever the limit, out of [count]. */
    private class OneReleasePerPage(private val count: Int) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val offset = request.url.queryParameter("offset")!!.toInt()
            val releases = if (offset < count) {
                """[{"id": "00000000-0000-4000-8000-${offset.toString().padStart(12, '0')}", "title": "Release $offset"}]"""
            } else {
                "[]"
            }
            return MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"release-count": $count, "release-offset": $offset, "releases": $releases}""")
                .build()
        }
    }
}
