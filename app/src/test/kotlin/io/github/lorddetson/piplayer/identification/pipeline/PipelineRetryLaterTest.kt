package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.Fixture
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.caa.TestClock
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.library.db.LookupJobEntity
import io.github.lorddetson.piplayer.library.values.TrackTags
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * "Retry later" for a single file (TI §3.2, M3 part), under an injected clock: the lookup job an empty AcoustID result
 * stores, the next Identify before and after its time, and the text candidates that cancel it.
 */
class PipelineRetryLaterTest {

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

    private suspend fun IdentificationPipeline.identify() = run(FileRef.Opened("file:///music/track.mp3"))

    private fun acoustIdRequests() = log.entries.count { it.path.startsWith("/v2/") }

    private fun PipelineResult.fingerprintStage() = stages.single { it.stage == PipelineStage.FINGERPRINT }.result

    /** AcoustID answers [fixture] for the file, whether its fingerprint was just computed or read from the cache. */
    private fun acoustIdAnswers(fixture: Fixture) {
        acoustId.on(FakeFingerprints.SESSION, fixture)
        acoustId.on(FakeEncoder.encoded(FakeFingerprints.RAW), fixture)
    }

    /** Nothing in the text search reaches 0.50 and AcoustID finds nothing: the file waits for its retry. */
    private fun nothingFound() {
        acoustIdAnswers(FakeAcoustId.EMPTY)
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)
    }

    private fun waiting(at: java.time.Instant) =
        LookupJobEntity(KEY, null, "lookup", "retry_later", 1, at.toEpochMilli(), "acoustid_empty")

    @Test
    fun `an empty AcoustID result stores a job whose retry is an hour later and reports that time`() = runTest {
        val pipeline = pipeline()
        nothingFound()

        val result = pipeline.identify()

        val due = clock.after(1.hours)
        assertThat(result.retryLater).isTrue()
        assertThat(result.retryAt).isEqualTo(due)
        assertThat(store.jobs).containsOnlyKeys(KEY)
        assertThat(store.jobs[KEY]).isEqualTo(
            LookupJobEntity(KEY, batchUuid = null, "lookup", "retry_later", 1, due.toEpochMilli(), "acoustid_empty"),
        )
    }

    @Test
    fun `an Identify before the retry time sends nothing to AcoustID, decodes nothing and still searches by text`() = runTest {
        val pipeline = pipeline()
        nothingFound()
        pipeline.identify()
        val job = store.jobs.getValue(KEY)
        val computes = fingerprints.computes
        clock.advance(59.minutes + 59.seconds)

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(1)
        assertThat(fingerprints.computes).isEqualTo(computes)
        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Skipped(SkipReason.RETRY_PENDING))
        assertThat(result.stages.single { it.stage == PipelineStage.TEXT_SEARCH }.result).isInstanceOf(StageResult.Ran::class.java)
        // The review still states when the retry is due.
        assertThat(result.retryAt).isEqualTo(java.time.Instant.ofEpochMilli(job.nextAttemptAt!!))
        assertThat(store.jobs[KEY]).isEqualTo(job)
    }

    @Test
    fun `the first Identify at the retry time asks AcoustID once, and results there remove the job`() = runTest {
        val pipeline = pipeline()
        nothingFound()
        pipeline.identify()
        clock.advance(1.hours)
        acoustIdAnswers(FakeAcoustId.ID_ONLY)

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(2)
        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Ran(1))
        assertThat((result.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.HIGH)
        assertThat(result.retryLater).isFalse()
        assertThat(result.retryAt).isNull()
        assertThat(store.jobs).isEmpty()
    }

    @Test
    fun `a second empty result makes the job final, and later runs skip the fingerprint`() = runTest {
        val pipeline = pipeline()
        nothingFound()
        pipeline.identify()
        clock.advance(2.hours)

        val retried = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(2)
        assertThat(retried.fingerprintStage()).isEqualTo(StageResult.Ran(0))
        assertThat(retried.retryLater).isFalse()
        assertThat(retried.retryAt).isNull()
        assertThat(store.jobs[KEY]).isEqualTo(
            LookupJobEntity(KEY, batchUuid = null, "lookup", "final", 2, nextAttemptAt = null, "acoustid_empty"),
        )

        clock.advance(48.hours)
        val later = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(2)
        assertThat(later.fingerprintStage()).isEqualTo(StageResult.Skipped(SkipReason.EMPTY_RESULT_FINAL))
        assertThat(later.retryAt).isNull()
        assertThat(store.jobs[KEY]!!.status).isEqualTo("final")
    }

    @Test
    fun `a text candidate of at least 0_50 keeps the first empty result from storing a job`() = runTest {
        val pipeline = pipeline()
        acoustIdAnswers(FakeAcoustId.EMPTY)

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(1)
        assertThat((result.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.MEDIUM)
        assertThat(result.retryLater).isFalse()
        assertThat(result.retryAt).isNull()
        assertThat(store.jobs).isEmpty()
    }

    @Test
    fun `a text candidate of at least 0_50 in a later run removes a waiting job`() = runTest {
        val pipeline = pipeline()
        store.jobs[KEY] = waiting(clock.after(30.minutes))

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isZero()
        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Skipped(SkipReason.RETRY_PENDING))
        assertThat((result.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.MEDIUM)
        assertThat(result.retryAt).isNull()
        assertThat(store.jobs).isEmpty()
    }

    @Test
    fun `a text candidate of at least 0_50 leaves a final job`() = runTest {
        val pipeline = pipeline()
        val final = LookupJobEntity(KEY, null, "lookup", "final", 2, null, "acoustid_empty")
        store.jobs[KEY] = final

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isZero()
        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Skipped(SkipReason.EMPTY_RESULT_FINAL))
        assertThat(store.jobs[KEY]).isEqualTo(final)
    }

    @Test
    fun `a retry whose lookup gets no answer leaves the job as it was`() = runTest {
        val pipeline = pipeline()
        val due = waiting(clock.after((-5).minutes))
        store.jobs[KEY] = due
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)
        // AcoustID stays busy through every retry.
        acoustId.enqueue(*Array(4) { FakeMusicBrainz.busy(503) })

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(4)
        assertThat(result.fingerprintStage()).isInstanceOf(StageResult.Failed::class.java)
        assertThat(store.jobs[KEY]).isEqualTo(due)
        // The retry was due and the next Identify asks again: no time to wait for is reported.
        assertThat(result.retryLater).isFalse()
        assertThat(result.retryAt).isNull()
    }

    @Test
    fun `a run whose fingerprint stops early leaves a waiting job`() = runTest {
        val pipeline = pipeline()
        val job = waiting(clock.after(10.minutes))
        store.jobs[KEY] = job
        local.file = local.file!!.copy(tags = local.file!!.tags!!.copy(recordingMbid = FakeMusicBrainz.HARBOR_RECORDING))

        val result = pipeline.identify()

        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Skipped(SkipReason.STOPPED_EARLY))
        assertThat(store.jobs[KEY]).isEqualTo(job)
        // A confirmed match does not report a retry it never waited for.
        assertThat((result.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.HIGH)
        assertThat(result.retryLater).isFalse()
        assertThat(result.retryAt).isNull()
    }

    @Test
    fun `a job that cannot be read counts as none`() = runTest {
        val pipeline = pipeline()
        store.jobs[KEY] = waiting(clock.after(30.minutes))
        store.readFailure = IllegalStateException("database closed")
        acoustIdAnswers(FakeAcoustId.ID_ONLY)

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(1)
        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Ran(1))
        // Results remove the job that is still in the table, although it could not be read.
        assertThat(store.jobs).isEmpty()
    }

    @Test
    fun `an empty result after a job that cannot be read leaves a final job final`() = runTest {
        val pipeline = pipeline()
        val final = LookupJobEntity(KEY, null, "lookup", "final", 2, null, "acoustid_empty")
        store.jobs[KEY] = final
        store.readFailure = IllegalStateException("database closed")
        nothingFound()

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(1)
        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Ran(0))
        assertThat(store.jobs[KEY]).isEqualTo(final)
        assertThat(result.retryAt).isNull()
    }

    @Test
    fun `a text candidate after a job that cannot be read leaves a final job final`() = runTest {
        val pipeline = pipeline()
        val final = LookupJobEntity(KEY, null, "lookup", "final", 2, null, "acoustid_empty")
        store.jobs[KEY] = final
        store.readFailure = IllegalStateException("database closed")
        acoustIdAnswers(FakeAcoustId.EMPTY)

        val result = pipeline.identify()

        assertThat((result.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.MEDIUM)
        assertThat(store.jobs[KEY]).isEqualTo(final)
    }

    @Test
    fun `a retry time exactly an hour ahead waits`() = runTest {
        val pipeline = pipeline()
        val job = waiting(clock.after(1.hours))
        store.jobs[KEY] = job
        nothingFound()

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isZero()
        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Skipped(SkipReason.RETRY_PENDING))
        assertThat(result.retryAt).isEqualTo(clock.after(1.hours))
        assertThat(store.jobs[KEY]).isEqualTo(job)
    }

    @Test
    fun `a retry time more than an hour ahead, stored while the clock ran ahead, counts as due`() = runTest {
        val pipeline = pipeline()
        nothingFound()
        pipeline.identify()
        // The clock was a day ahead at the first run and has been corrected since.
        clock.advance((-24).hours)

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(2)
        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Ran(0))
        assertThat(store.jobs[KEY]!!.status).isEqualTo("final")
        assertThat(result.retryAt).isNull()
    }

    @Test
    fun `a retry time a millisecond more than an hour ahead counts as due`() = runTest {
        val pipeline = pipeline()
        store.jobs[KEY] = waiting(clock.after(1.hours).plusMillis(1))
        nothingFound()

        val result = pipeline.identify()

        assertThat(acoustIdRequests()).isEqualTo(1)
        assertThat(result.fingerprintStage()).isEqualTo(StageResult.Ran(0))
    }

    private companion object {
        const val KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
