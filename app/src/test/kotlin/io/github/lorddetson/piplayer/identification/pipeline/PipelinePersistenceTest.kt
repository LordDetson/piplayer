package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.Fixture
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.caa.TestClock
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.release.ReleaseChoice
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.Tier
import io.github.lorddetson.piplayer.library.db.IdentificationTraceEntity
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.net.OnlineConsent
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a run stores by its content key (TI §3 "One run", §10.1): the candidates, the trace of the last 3 runs with what
 * the HTTP layer saw, and nothing for a run that a blocked call, a cancellation or an unreadable file ends.
 */
class PipelinePersistenceTest {

    @StartStop
    private val acoustIdServer = MockWebServer()

    @StartStop
    private val musicBrainzServer = MockWebServer()

    private val acoustId = FakeAcoustId()
    private val musicBrainz = FakeMusicBrainz().withHarborLights()
    private val gate = TestServices.openGate()
    private val clock = TestClock()
    private val store = MemoryIdentificationStore()
    private val fingerprints = FakeFingerprints()
    private val tags = TrackTags(title = "Paper Lanterns", artist = "The Quiet Harbors")
    private val local = FakeLocal(
        LocalFile(
            target = IdentificationTarget.OpenedFile("file:///music/track.mp3", KEY, "track.mp3", 8_600_000),
            fileName = "01 - The Quiet Harbors - Paper Lanterns.mp3",
            tags = tags,
            duration = StageDuration.Exact(214_000),
            format = "MPEG",
            folder = null,
            uri = "file:///music/track.mp3",
        ),
    )
    private lateinit var log: RecordingRequestLog

    private fun TestScope.pipeline(key: String = "testKey123"): IdentificationPipeline {
        log = RecordingRequestLog { testScheduler.currentTime }
        acoustIdServer.dispatcher = log.wrap(acoustId)
        musicBrainzServer.dispatcher = log.wrap(musicBrainz)
        val services = TestServices.services(
            baseUrls = TestServices.baseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/")),
            gate = gate,
            timeSource = testTimeSource,
            clock = clock,
            acoustIdApiKey = key,
        )
        return IdentificationPipeline(services, local, fingerprints, FakeEncoder(), store, { "GB" }, testTimeSource)
    }

    private suspend fun IdentificationPipeline.identify() = run(FileRef.Opened("file:///music/track.mp3"))

    private fun List<IdentificationTraceEntity>.stage(name: String) = single { it.stage == name }

    /** AcoustID answers [fixture] for the file, whether its fingerprint was just computed or read from the cache. */
    private fun acoustIdAnswers(fixture: Fixture) {
        acoustId.on(FakeFingerprints.SESSION, fixture)
        acoustId.on(FakeEncoder.encoded(FakeFingerprints.RAW), fixture)
    }

    // Candidates.

    @Test
    fun `a run stores its ranked candidates best first, with the suggested edition, and they read back as ranked`() = runTest {
        val pipeline = pipeline()
        // Two recordings at 0.97: both stay in the ranking.
        acoustIdAnswers(FakeAcoustId.LOOKUP)

        val result = pipeline.identify()

        val found = result.outcome as IdentificationOutcome.Found
        val rows = store.candidatesOf(KEY)
        assertThat(rows).isNotEmpty()
        assertThat(rows.map { it.position }).isEqualTo(rows.indices.toList())
        assertThat(rows.map { it.recordingMbid }).isEqualTo(found.ranking.matches.map { it.recordingId }.take(rows.size))
        assertThat(rows.map { it.score }).isEqualTo(found.ranking.matches.map { it.score }.take(rows.size))
        assertThat(rows.first().source).isEqualTo(found.ranking.best!!.reason.source.name.lowercase())
        assertThat(rows.size).isGreaterThan(1)
        assertThat(StoredCandidates.matches(rows)).isEqualTo(found.ranking.matches.take(rows.size))
    }

    @Test
    fun `the best candidate carries the suggested edition`() = runTest {
        val pipeline = pipeline()
        acoustIdAnswers(FakeAcoustId.ID_ONLY)

        val result = pipeline.identify()

        assertThat((result.outcome as IdentificationOutcome.Found).choice.suggested!!.release.id)
            .isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        val best = store.candidatesOf(KEY).first()
        assertThat(best.recordingMbid).isEqualTo(FakeMusicBrainz.HARBOR_RECORDING)
        assertThat(best.releaseMbid).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(best.source).isEqualTo("fingerprint")
    }

    @Test
    fun `a later run replaces the candidates of the run before`() = runTest {
        val pipeline = pipeline()
        acoustIdAnswers(FakeAcoustId.LOOKUP)
        pipeline.identify()
        assertThat(store.candidatesOf(KEY)).isNotEmpty()
        acoustIdAnswers(FakeAcoustId.EMPTY)
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)

        pipeline.identify()

        assertThat(store.candidatesOf(KEY)).isEmpty()
    }

    @Test
    fun `at most ten candidates are stored, and only the best carries the suggested edition when no other was browsed`() {
        val matches = (0 until 12).map { i -> match("${i.toString().padStart(2, '0')}000000-0000-4000-8000-000000000000", 0.9 - i * 0.01) }
        val ranking = Ranking(matches, runnerUpGap = 0.01, band = Band.MEDIUM)

        val rows = StoredCandidates.rows(KEY, ranking, ReleaseChoice.NONE)

        assertThat(rows).hasSize(StoredCandidates.LIMIT)
        assertThat(rows.map { it.recordingMbid }).isEqualTo(matches.take(10).map { it.recordingId })
        assertThat(rows.map { it.releaseMbid }).containsOnlyNulls()
        assertThat(rows.map { it.contentKey }).containsOnly(KEY)
        assertThat(StoredCandidates.matches(rows)).isEqualTo(matches.take(10))
    }

    // The trace.

    @Test
    fun `a run traces every stage it reached and its outcome, with the provider, the HTTP status and the requests`() = runTest {
        val pipeline = pipeline()
        acoustIdAnswers(FakeAcoustId.ID_ONLY)

        pipeline.identify()

        val trace = store.traceOf(KEY)
        assertThat(trace.map { it.stage }).containsExactly(
            "local_read", "known_ids", "isrc", "fingerprint", "text_search", "release_choice", "outcome",
        )
        assertThat(trace.map { it.position }).isEqualTo(trace.indices.toList())
        assertThat(trace.map { it.runId }.distinct()).hasSize(1)
        assertThat(trace.map { it.startedAt }).containsOnly(clock.now.toEpochMilli())
        assertThat(trace.stage("local_read").provider).isNull()
        assertThat(trace.stage("known_ids").provider).isEqualTo("musicbrainz")
        assertThat(trace.stage("known_ids").note).isEqualTo("skipped=no_value")
        assertThat(trace.stage("known_ids").httpStatus).isNull()
        val fingerprint = trace.stage("fingerprint")
        assertThat(fingerprint.provider).isEqualTo("acoustid")
        assertThat(fingerprint.httpStatus).isEqualTo(200)
        assertThat(fingerprint.resultCount).isEqualTo(1)
        assertThat(fingerprint.bestScore).isNotNull().isGreaterThanOrEqualTo(0.90)
        assertThat(fingerprint.elapsedMs).isNotNull()
        assertThat(fingerprint.note).startsWith("requests=1 http_ms=")
        assertThat(trace.stage("text_search").note).isEqualTo("skipped=stopped_early")
        val releases = trace.stage("release_choice")
        assertThat(releases.provider).isEqualTo("musicbrainz")
        assertThat(releases.httpStatus).isEqualTo(200)
        // The ID-only winner's lookup, two browse pages and the release lookup.
        assertThat(releases.note).startsWith("requests=4 http_ms=")
        val outcome = trace.stage("outcome")
        assertThat(outcome.note).isEqualTo("found=high")
        assertThat(outcome.resultCount).isEqualTo(1)
        assertThat(outcome.bestScore).isEqualTo(fingerprint.bestScore)
    }

    @Test
    fun `a failed stage is traced with the busy status of its last answer and its error type`() = runTest {
        val pipeline = pipeline(key = "")
        local.file = local.file!!.copy(tags = tags.copy(recordingMbid = FakeMusicBrainz.HARBOR_RECORDING))
        musicBrainz.enqueue(*Array(4) { FakeMusicBrainz.busy(503) })

        pipeline.identify()

        val knownIds = store.traceOf(KEY).stage("known_ids")
        assertThat(knownIds.httpStatus).isEqualTo(503)
        assertThat(knownIds.resultCount).isNull()
        assertThat(knownIds.note).startsWith("failed=").contains("requests=4")
        assertThat(store.traceOf(KEY).stage("fingerprint").note).isEqualTo("skipped=no_key")
    }

    @Test
    fun `the trace holds no file names, tag values, addresses or error messages`() = runTest {
        val pipeline = pipeline()
        local.file = local.file!!.copy(tags = tags.copy(recordingMbid = FakeMusicBrainz.HARBOR_RECORDING))
        musicBrainz.enqueue(*Array(4) { FakeMusicBrainz.busy(503) })
        acoustIdAnswers(FakeAcoustId.EMPTY)

        pipeline.identify()

        val text = store.traceOf(KEY).joinToString("\n") { listOf(it.stage, it.provider, it.note).joinToString(" ") }
        assertThat(text).isNotBlank()
        for (hidden in listOf("Paper", "Lanterns", "Harbors", "track.mp3", "/music/", "http:", "localhost", "503 ")) {
            assertThat(text).describedAs(hidden).doesNotContainIgnoringCase(hidden)
        }
    }

    @Test
    fun `the trace keeps the last three runs of a content key in the order they were written`() = runTest {
        val pipeline = pipeline()
        acoustIdAnswers(FakeAcoustId.ID_ONLY)
        val runs = mutableListOf<String>()
        repeat(4) { run ->
            // The fourth run's clock was set back: the order of writing still decides.
            if (run == 3) clock.advance((-90).minutes) else clock.advance(10.minutes)
            pipeline.identify()
            runs += store.traceOf(KEY).last().runId
        }

        assertThat(runs.distinct()).hasSize(4)
        assertThat(store.runsOf(KEY)).containsExactly(runs[1], runs[2], runs[3])
    }

    @Test
    fun `the trace of one content key leaves another key's runs alone`() = runTest {
        val pipeline = pipeline()
        acoustIdAnswers(FakeAcoustId.ID_ONLY)
        val other = local.file!!
        local.file = other.copy(target = IdentificationTarget.OpenedFile("file:///music/b.mp3", OTHER_KEY, "b.mp3", 1))
        pipeline.identify()
        local.file = other
        repeat(3) { pipeline.identify() }

        assertThat(store.runsOf(OTHER_KEY)).hasSize(1)
        assertThat(store.runsOf(KEY)).hasSize(3)
    }

    // Runs that store nothing.

    @Test
    fun `a run that a blocked call ends stores nothing and keeps the rows of the run before`() = runTest {
        val pipeline = pipeline()
        acoustIdAnswers(FakeAcoustId.LOOKUP)
        pipeline.identify()
        val candidates = store.candidatesOf(KEY)
        val trace = store.traceOf(KEY)
        fingerprints.whileComputing = { gate.set(OnlineConsent.NONE) }
        fingerprints.stored.clear()

        val result = pipeline.identify()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Disabled)
        assertThat(store.candidatesOf(KEY)).isEqualTo(candidates)
        assertThat(store.traceOf(KEY)).isEqualTo(trace)
        assertThat(store.writes).isEqualTo(1)
    }

    @Test
    fun `a cancelled run stores nothing`() = runTest {
        val pipeline = pipeline()
        val decoding = CompletableDeferred<Unit>()
        fingerprints.suspendUntil = CompletableDeferred()
        fingerprints.whileComputing = { decoding.complete(Unit) }

        val job = launch { pipeline.identify() }
        decoding.await()
        job.cancel()
        job.join()
        runCurrent()

        assertThat(store.writes).isZero()
    }

    @Test
    fun `an unreadable file stores nothing`() = runTest {
        val pipeline = pipeline()
        local.file = null

        val result = pipeline.identify()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Unreadable)
        assertThat(store.writes).isZero()
    }

    @Test
    fun `rows that cannot be written leave the outcome as it was`() = runTest {
        val pipeline = pipeline()
        acoustIdAnswers(FakeAcoustId.EMPTY)
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)
        store.writeFailure = IllegalStateException("disk full")

        val result = pipeline.identify()

        assertThat((result.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.NO_MATCH)
        // The time is reported even though it could not be kept.
        assertThat(result.retryAt).isEqualTo(clock.after(60.minutes))
        assertThat(store.writes).isZero()
    }

    @Test
    fun `a run without an AcoustID key and with nothing found stores its trace and no candidates`() = runTest {
        val pipeline = pipeline(key = "")
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)

        val result = pipeline.identify()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.NoKey)
        assertThat(store.candidatesOf(KEY)).isEmpty()
        assertThat(store.traceOf(KEY).stage("outcome").note).isEqualTo("no_key")
        assertThat(store.traceOf(KEY).stage("text_search").httpStatus).isEqualTo(200)
        assertThat(store.jobs).isEmpty()
    }

    private fun match(recordingId: String, score: Double) = RecordingMatch(
        TextCandidate(recordingId, "Title $score", "Artist", listOf("Artist"), 200_000),
        score,
        Reason(Source.TEXT_SEARCH, Tier.SIMILARITY, setOf(Source.TEXT_SEARCH), null, 1_000, 0.9, 0.8, emptySet()),
    )

    private companion object {
        const val KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val OTHER_KEY = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    }
}
