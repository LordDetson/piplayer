package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.caa.TestClock
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.values.TrackTags
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The batch UUID of a run (TI §8, §10.1): a run that belongs to a batch writes the batch's UUID into its lookup job,
 * a single-file run writes none. The job itself and its "retry later" states stay as for a single file (TI §3.2).
 */
class PipelineBatchUuidTest {

    @StartStop
    private val acoustIdServer = MockWebServer()

    @StartStop
    private val musicBrainzServer = MockWebServer()

    private val acoustId = FakeAcoustId()
    private val musicBrainz = FakeMusicBrainz().withHarborLights()
    private val clock = TestClock()
    private val store = MemoryIdentificationStore()
    private val fingerprints = FakeFingerprints()
    private val local = FakeLocal(
        LocalFile(
            target = IdentificationTarget.OpenedFile("file:///music/track.mp3", KEY, "track.mp3", 8_600_000),
            fileName = "01 - The Quiet Harbors - Paper Lanterns.mp3",
            tags = TrackTags(title = "Paper Lanterns", artist = "The Quiet Harbors"),
            duration = StageDuration.Exact(214_000),
            format = "MPEG",
            folder = null,
            uri = "file:///music/track.mp3",
        ),
    )
    private lateinit var log: RecordingRequestLog

    private fun TestScope.pipeline(): IdentificationPipeline {
        log = RecordingRequestLog { testScheduler.currentTime }
        acoustIdServer.dispatcher = log.wrap(acoustId)
        musicBrainzServer.dispatcher = log.wrap(musicBrainz)
        val services = TestServices.services(
            baseUrls = TestServices.baseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/")),
            timeSource = testTimeSource,
            clock = clock,
            acoustIdApiKey = "testKey123",
        )
        return IdentificationPipeline(services, local, fingerprints, FakeEncoder(), store, { "GB" }, testTimeSource)
    }

    /** Nothing in the text search reaches 0.50 and AcoustID finds nothing: the run stores its lookup job. */
    private fun nothingFound() {
        acoustId.on(FakeFingerprints.SESSION, FakeAcoustId.EMPTY)
        acoustId.on(FakeEncoder.encoded(FakeFingerprints.RAW), FakeAcoustId.EMPTY)
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)
    }

    @Test
    fun `a run of a batch writes the batch's UUID into its lookup job`() = runTest {
        val pipeline = pipeline()
        nothingFound()

        pipeline.run(FileRef.Opened("file:///music/track.mp3"), batchUuid = BATCH)

        assertThat(store.jobs[KEY]?.batchUuid).isEqualTo(BATCH)
        // The single-file retry values stay the single-file run's (TI §3.2).
        assertThat(store.jobs[KEY]?.phase).isEqualTo("lookup")
        assertThat(store.jobs[KEY]?.status).isEqualTo("retry_later")
        assertThat(store.jobs[KEY]?.lastError).isEqualTo("acoustid_empty")
    }

    @Test
    fun `a single-file run writes no batch UUID`() = runTest {
        val pipeline = pipeline()
        nothingFound()

        pipeline.run(FileRef.Opened("file:///music/track.mp3"))

        assertThat(store.jobs[KEY]?.batchUuid).isNull()
    }

    @Test
    fun `a batch run that finds results removes the lookup job as a single-file run does`() = runTest {
        val pipeline = pipeline()
        acoustId.on(FakeFingerprints.SESSION, FakeAcoustId.ID_ONLY)
        acoustId.on(FakeEncoder.encoded(FakeFingerprints.RAW), FakeAcoustId.ID_ONLY)

        pipeline.run(FileRef.Opened("file:///music/track.mp3"), batchUuid = BATCH)

        assertThat(store.jobs).isEmpty()
    }

    private companion object {
        const val KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val BATCH = "42e2e0c3-0000-4000-8000-000000000016"
    }
}