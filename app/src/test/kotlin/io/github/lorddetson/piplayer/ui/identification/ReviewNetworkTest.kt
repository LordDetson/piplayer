package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.fakes.FakeCaa
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.musicbrainz.RefLookup
import io.github.lorddetson.piplayer.identification.release.EditionSource
import io.github.lorddetson.piplayer.identification.text.MusicBrainzRef
import io.github.lorddetson.piplayer.identification.text.TextQueryBuilder
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.net.SendBlockedException
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.found
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.result
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The review's requests of review screen B (TI §6, step 20) through the services' real clients on the fake MusicBrainz
 * and Cover Art Archive: what each panel asks, and that nothing is sent while online lookups are off.
 */
class ReviewNetworkTest {

    @StartStop
    private val musicBrainz = MockWebServer()

    @StartStop
    private val caa = MockWebServer()

    @StartStop
    private val archive = MockWebServer()

    private lateinit var log: RecordingRequestLog

    private fun TestScope.network(gate: OnlineGate = TestServices.openGate()): ReviewNetwork {
        log = RecordingRequestLog { testScheduler.currentTime }
        musicBrainz.dispatcher = log.wrap(FakeMusicBrainz().withHarborLights())
        val fakeCaa = FakeCaa { archive.url("/") }.withIndex(FakeCaa.INDEX_RELEASE)
        caa.dispatcher = log.wrap(fakeCaa)
        archive.dispatcher = log.wrap(fakeCaa.archive)
        val services: IdentificationServices = TestServices.services(
            baseUrls = TestServices.baseUrls(musicBrainz.url("/v2/"), musicBrainz.url("/ws/2/"), caa.url("/")),
            gate = gate,
            timeSource = testTimeSource,
        )
        return ReviewNetwork(services) { "GB" }
    }

    private val run = result(found())

    @Test
    fun `the picker lists the group of the suggested edition, which comes first`() = runTest {
        val list = network().editions(ReviewFixtures.harborEdition, run)

        assertThat(list.editions.map { it.release.id }).containsExactly(
            FakeMusicBrainz.HARBOR_RELEASE,
            FakeMusicBrainz.HARBOR_CD_RELEASE,
            FakeMusicBrainz.HARBOR_PROMO_RELEASE,
        )
        assertThat(list.editions.first().suggested).isTrue()
        assertThat(list.totalCount).isEqualTo(3)
        assertThat(log.entries.filter { it.path.contains("release-group=${FakeMusicBrainz.HARBOR_GROUP}") }).hasSize(1)
    }

    @Test
    fun `a chosen edition maps the file onto the song's own recording, another one at its place, or none`() = runTest {
        val network = network()
        val (suggested, cd, promo) = network.editions(ReviewFixtures.harborEdition, run).editions

        assertThat(network.openEdition(suggested, ReviewFixtures.harborEdition, run)!!.details)
            .isEqualTo(ReviewFixtures.harborEdition.details)
        val mapped = network.openEdition(cd, ReviewFixtures.harborEdition, run)!!
        assertThat(mapped.release.id).isEqualTo(FakeMusicBrainz.HARBOR_CD_RELEASE)
        assertThat(mapped.details.recordingId).isEqualTo(ReviewFixtures.CD_RECORDING)
        assertThat(mapped.details.title).isEqualTo("Paper Lanterns")
        assertThat(mapped.details.at.position).isEqualTo(3)
        assertThat(mapped.source).isEqualTo(EditionSource.PICKED)
        assertThat(network.openEdition(promo, ReviewFixtures.harborEdition, run)).isNull()
    }

    @Test
    fun `manual search sends one dismax query and a pasted ID one lookup`() = runTest {
        val network = network()

        val found = network.search(TextQueryBuilder.manual("paper lanterns")!!)
        val lookup = network.lookup(MusicBrainzRef.AnyEntity(FakeMusicBrainz.HARBOR_RECORDING))

        assertThat(found.map { it.recordingId }).contains(FakeMusicBrainz.HARBOR_RECORDING)
        assertThat((lookup as RefLookup.Recording).recording.id).isEqualTo(FakeMusicBrainz.HARBOR_RECORDING)
        val search = log.entries.single { it.path.startsWith("/ws/2/recording?") }
        assertThat(search.path).contains("dismax=true").contains("limit=50")
        assertThat(log.entries.filter { it.path.startsWith("/ws/2/recording/") }).hasSize(1)
    }

    @Test
    fun `a manual result that scores low still gets an edition by release choice`() = runTest {
        val low = ReviewFixtures.match(score = 0.2)

        val edition = network().edition(low, result(found(ReviewFixtures.match(score = 0.2))))

        assertThat(edition!!.details.recordingId).isEqualTo(FakeMusicBrainz.HARBOR_RECORDING)
    }

    @Test
    fun `the cover picker gets the release index and its images`() = runTest {
        val network = network()

        val index = (network.releaseImages(FakeCaa.INDEX_RELEASE) as CaaResult.Found).value
        val front = index.images.first()
        val thumbnail = network.image(front, 250) as CaaResult.Found
        val full = network.image(front, 1200) as CaaResult.Found

        assertThat(index.images).isNotEmpty()
        assertThat(thumbnail.value.size!!.width).isEqualTo(FakeCaa.FRONT_250.width)
        assertThat(full.value.size!!.width).isEqualTo(FakeCaa.FRONT_1200.width)
        assertThat(full.value.source).isNull()
    }

    @Test
    fun `with online lookups off the picker, the search, the lookup, the index and its images send nothing`() = runTest {
        val open = network()
        val index = (open.releaseImages(FakeCaa.INDEX_RELEASE) as CaaResult.Found).value
        val sentBefore = musicBrainz.requestCount + caa.requestCount + archive.requestCount

        val network = network(InMemoryOnlineGate())
        val editions = ReviewFixtures.editionList().editions
        val calls = listOf<suspend () -> Any?>(
            { network.editions(ReviewFixtures.harborEdition, run) },
            { network.openEdition(editions[1], ReviewFixtures.harborEdition, run) },
            { network.search(TextQueryBuilder.manual("paper lanterns")!!) },
            { network.lookup(MusicBrainzRef.Release(FakeMusicBrainz.HARBOR_CD_RELEASE)) },
            { network.releaseImages(FakeCaa.INDEX_RELEASE) },
            { network.image(index.images.first(), 250) },
            { network.edition(ReviewFixtures.match(), run) },
        )
        for (call in calls) {
            assertThatThrownBy { kotlinx.coroutines.runBlocking { call() } }.isInstanceOf(SendBlockedException::class.java)
        }

        assertThat(musicBrainz.requestCount + caa.requestCount + archive.requestCount).isEqualTo(sentBefore)
    }
}
