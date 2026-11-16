package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.FingerprintStage
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.pipeline.CachedFingerprint
import io.github.lorddetson.piplayer.identification.pipeline.ComputedFingerprint
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.FingerprintCache
import io.github.lorddetson.piplayer.identification.pipeline.IdentificationPipeline
import io.github.lorddetson.piplayer.identification.pipeline.IdentificationStore
import io.github.lorddetson.piplayer.identification.pipeline.LocalFile
import io.github.lorddetson.piplayer.identification.pipeline.LocalStage
import io.github.lorddetson.piplayer.identification.pipeline.PipelineStage
import io.github.lorddetson.piplayer.identification.pipeline.SkipReason
import io.github.lorddetson.piplayer.identification.pipeline.StageDuration
import io.github.lorddetson.piplayer.identification.pipeline.StageResult
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The spike as SpikeServices builds it: the app's pipeline over the spike's own services behind [SpikeGate], here against
 * the shared fakes instead of the real hosts, with stage 0 and the fingerprint cache as fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpikeServicesWiringTest {

    @StartStop
    private val acoustIdServer = MockWebServer()

    @StartStop
    private val musicBrainzServer = MockWebServer()

    private val uri = "content://test/01 - The Quiet Harbors - Paper Lanterns.mp3"

    private val local = object : LocalStage {
        override suspend fun read(file: FileRef) = LocalFile(
            target = IdentificationTarget.OpenedFile(uri, CONTENT_KEY, "01 - The Quiet Harbors - Paper Lanterns.mp3", 8_600_000),
            fileName = "01 - The Quiet Harbors - Paper Lanterns.mp3",
            tags = TrackTags(title = "Paper Lanterns", artist = "The Quiet Harbors"),
            duration = StageDuration.Exact(214_000),
            format = "MPEG",
            folder = null,
            uri = uri,
        )

        override suspend fun walk(file: LocalFile, plan: DurationPlan): Long? = null
    }

    private val fingerprints = object : FingerprintCache {
        override suspend fun cached(contentKey: String): CachedFingerprint? = null
        override suspend fun compute(file: LocalFile) = ComputedFingerprint(IntArray(948), "AQAAencoded")
        override suspend fun keepAcoustIdTrack(contentKey: String, trackId: String) = Unit
    }

    private fun TestScope.services(log: RecordingRequestLog, key: String): IdentificationServices {
        acoustIdServer.dispatcher = log.wrap(FakeAcoustId())
        musicBrainzServer.dispatcher = log.wrap(FakeMusicBrainz())
        return SpikeServices.create(
            userAgent = TestServices.USER_AGENT,
            connectivity = TestServices.FakeConnectivity(),
            acoustIdApiKey = key,
            baseUrls = TestServices.baseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/")),
            timeSource = testTimeSource,
        )
    }

    private fun TestScope.pipeline(services: IdentificationServices) =
        IdentificationPipeline(
            services, local, fingerprints, { error("nothing is cached") }, IdentificationStore.NONE, timeSource = testTimeSource,
        )

    private fun RecordingRequestLog.toServer(server: MockWebServer) = to("${server.hostName}:${server.port}")

    @Test
    fun `a spike run opens the gate for the pipeline, which reaches AcoustID and MusicBrainz through the services`() = runTest {
        val log = RecordingRequestLog { testScheduler.currentTime }
        val services = services(log, KEY)
        assertThat(services.fingerprintStage).isInstanceOf(FingerprintStage.Available::class.java)

        val result = SpikeServices.run(uri, pipeline(services))

        assertThat(result.outcome).isInstanceOf(IdentificationOutcome.Found::class.java)
        assertThat(log.toServer(acoustIdServer)).hasSize(1)
        assertThat(log.toServer(musicBrainzServer)).isNotEmpty()
        assertThat(log.entries.map { it.userAgent }).containsOnly(TestServices.USER_AGENT)
        assertThat(SpikeGate.isOpen()).isFalse()
    }

    @Test
    fun `the spike skips AcoustID when its services have no key`() = runTest {
        val log = RecordingRequestLog { testScheduler.currentTime }
        val services = services(log, key = "")
        assertThat(services.fingerprintStage).isInstanceOf(FingerprintStage.Unavailable::class.java)

        val result = SpikeServices.run(uri, pipeline(services))

        assertThat(result.stages.single { it.stage == PipelineStage.FINGERPRINT }.result)
            .isEqualTo(StageResult.Skipped(SkipReason.NO_KEY))
        assertThat(log.toServer(acoustIdServer)).isEmpty()
        assertThat(log.toServer(musicBrainzServer)).isNotEmpty()
    }

    @Test
    fun `without a spike run the gate is closed and nothing is sent`() = runTest {
        val log = RecordingRequestLog { testScheduler.currentTime }

        val result = pipeline(services(log, KEY)).run(FileRef.Opened(uri))

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Disabled)
        assertThat(log.size).isZero()
    }

    @Test
    fun `the spike gate opens while a handle is held and closes when the last one is closed`() {
        assertThat(SpikeGate.isOpen()).isFalse()
        val first = SpikeGate.open()
        val second = SpikeGate.open()
        first.close()
        first.close()
        assertThat(SpikeGate.isOpen()).isTrue()
        second.close()
        assertThat(SpikeGate.isOpen()).isFalse()
    }

    private companion object {
        const val KEY = "testKey123"
        const val CONTENT_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
