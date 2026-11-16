package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.Fixture
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.fingerprint.DecodingException
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatusStore
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.release.EditionSource
import io.github.lorddetson.piplayer.identification.release.LookupPlan
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.net.NetworkState
import io.github.lorddetson.piplayer.net.OnlineConsent
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import java.util.Collections
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
 * One run of the identification pipeline (TI §3 "One run") on the shared fakes with virtual time: stage 0, the
 * fingerprint cache and the encoder are fakes, the providers are the HTTP fakes behind the real clients.
 */
class IdentificationPipelineTest {

    @StartStop
    private val acoustIdServer = MockWebServer()

    @StartStop
    private val musicBrainzServer = MockWebServer()

    private val acoustId = FakeAcoustId()
    private val musicBrainz = FakeMusicBrainz().withHarborLights()
    private val gate = TestServices.openGate()
    private val connectivity = TestServices.FakeConnectivity()
    private val statuses = ProviderStatusStore()
    private lateinit var log: RecordingRequestLog
    private val progress: MutableList<PipelineProgress> = Collections.synchronizedList(mutableListOf())

    private val tags = TrackTags(title = "Paper Lanterns", artist = "The Quiet Harbors")
    private val local = FakeLocal(file())
    private val fingerprints = FakeFingerprints()
    private val encoder = FakeEncoder()
    private val store = MemoryIdentificationStore()

    private fun file(
        tags: TrackTags? = this.tags,
        duration: StageDuration = StageDuration.Exact(DURATION_MS),
    ) = LocalFile(
        target = IdentificationTarget.OpenedFile("file:///music/track.mp3", KEY, "track.mp3", 8_600_000),
        fileName = "01 - The Quiet Harbors - Paper Lanterns.mp3",
        tags = tags,
        duration = duration,
        format = "MPEG",
        folder = null,
        uri = "file:///music/track.mp3",
    )

    private fun TestScope.pipeline(key: String = API_KEY): IdentificationPipeline {
        log = RecordingRequestLog { testScheduler.currentTime }
        acoustIdServer.dispatcher = log.wrap(acoustId)
        musicBrainzServer.dispatcher = log.wrap(musicBrainz)
        val services: IdentificationServices = TestServices.services(
            baseUrls = TestServices.baseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/")),
            gate = gate,
            connectivity = connectivity,
            timeSource = testTimeSource,
            statuses = statuses,
            acoustIdApiKey = key,
        )
        return IdentificationPipeline(
            services, local, fingerprints, encoder, store, country = { "GB" }, timeSource = testTimeSource,
        )
    }

    private suspend fun IdentificationPipeline.runOpened() = run(FileRef.Opened("file:///music/track.mp3")) { progress += it }

    private fun acoustIdRequests() = log.entries.filter { it.path.startsWith("/v2/") }

    private fun musicBrainzRequests() = log.entries.filter { it.path.startsWith("/ws/2/") }

    private fun searches() = musicBrainzRequests().filter { it.path.startsWith("/ws/2/recording?") }

    private fun pages() = musicBrainzRequests().filter { it.path.startsWith("/ws/2/release?") }

    private fun PipelineResult.stage(stage: PipelineStage) = stages.single { it.stage == stage }.result

    private fun PipelineResult.found() = outcome as IdentificationOutcome.Found

    private fun assertMusicBrainzSpacing() {
        musicBrainzRequests().map { it.timeMs }.zipWithNext().forEach { (earlier, later) ->
            assertThat(later - earlier).isGreaterThanOrEqualTo(1_100)
        }
    }

    // Stage 3: the fingerprint cache, the encoder and AcoustID.

    @Test
    fun `a cached fingerprint is encoded from its raw items and nothing is decoded`() = runTest {
        val pipeline = pipeline()
        fingerprints.stored[KEY] = CachedFingerprint(RAW)
        acoustId.on(FakeEncoder.encoded(RAW), ID_ONLY)

        val result = pipeline.runOpened()

        assertThat(fingerprints.computes).isZero()
        assertThat(encoder.calls).containsExactly(RAW)
        assertThat(result.fingerprint).isEqualTo(FingerprintSource.CACHED)
        assertThat(acoustIdRequests()).hasSize(1)
        assertThat(acoustIdRequests().single().body).contains("fingerprint=${FakeEncoder.encoded(RAW)}", "duration=214")
        assertThat(result.found().band).isEqualTo(Band.HIGH)
        assertThat(progress).doesNotContain(PipelineProgress.Decoding)
    }

    @Test
    fun `a missing fingerprint is decoded once and the session's string is sent without encoding it again`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, ID_ONLY)

        val result = pipeline.runOpened()

        assertThat(fingerprints.computes).isEqualTo(1)
        assertThat(encoder.calls).isEmpty()
        assertThat(result.fingerprint).isEqualTo(FingerprintSource.COMPUTED)
        assertThat(acoustIdRequests().single().body).contains("fingerprint=$SESSION")
        assertThat(fingerprints.stored[KEY]!!.raw).isEqualTo(RAW)
        assertThat(result.found().band).isEqualTo(Band.HIGH)
        assertThat(progress).contains(PipelineProgress.Decoding)
    }

    @Test
    fun `the AcoustID track ID of the best result is cached for the content key and reported`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, FakeAcoustId.CLOSE_SCORES)

        val result = pipeline.runOpened()

        assertThat(fingerprints.kept).containsExactly(org.assertj.core.api.Assertions.entry(KEY, CLOSE_SCORES_BEST))
        assertThat(result.found().acoustIdTrackId).isEqualTo(CLOSE_SCORES_BEST)
    }

    @Test
    fun `a file that cannot be decoded skips the fingerprint and falls back to text search`() = runTest {
        val pipeline = pipeline()
        fingerprints.failure = DecodingException.UnsupportedFormat("audio/alac")

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Skipped(SkipReason.UNDECODABLE))
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(searches()).isNotEmpty()
        assertThat(result.stage(PipelineStage.TEXT_SEARCH)).isInstanceOf(StageResult.Ran::class.java)
        assertThat(result.found().band).isEqualTo(Band.MEDIUM)
    }

    // Stage 0: the exact duration.

    @Test
    fun `a length left for a walk is walked before the lookup and the walked length is sent`() = runTest {
        val pipeline = pipeline()
        val plan = DurationPlan(null, DurationCheck.WALK)
        local.file = file(duration = StageDuration.Pending(plan))
        local.walked = 201_400
        acoustId.on(SESSION, ID_ONLY)

        val result = pipeline.runOpened()

        assertThat(local.walks).containsExactly(plan)
        assertThat(result.durationMs).isEqualTo(201_400)
        assertThat(result.lengthWalked).isTrue()
        assertThat(result.hint!!.durationMs).isEqualTo(201_400)
        assertThat(acoustIdRequests().single().body).contains("duration=201")
        assertThat(progress.indexOf(PipelineProgress.WalkingLength))
            .isGreaterThanOrEqualTo(0)
            .isLessThan(progress.indexOf(PipelineProgress.Started(PipelineStage.FINGERPRINT)))
    }

    @Test
    fun `a library file's target carries the exact length of stage 0 for Apply, a walked one too`() = runTest {
        val pipeline = pipeline()
        val walk = file(duration = StageDuration.Pending(DurationPlan(null, DurationCheck.WALK)))
        local.file = walk.copy(target = IdentificationTarget.LibraryFile(7, KEY))
        local.walked = 201_400
        acoustId.on(SESSION, ID_ONLY)

        val walked = pipeline.run(FileRef.Library(7))
        local.file = file().copy(target = IdentificationTarget.LibraryFile(7, KEY))
        val stored = pipeline.run(FileRef.Library(7))

        assertThat(walked.file!!.target).isEqualTo(IdentificationTarget.LibraryFile(7, KEY, durationMs = 201_400))
        assertThat(stored.file!!.target).isEqualTo(IdentificationTarget.LibraryFile(7, KEY, durationMs = DURATION_MS))
    }

    @Test
    fun `a FLAC end check that finds no earlier end keeps the header length`() = runTest {
        val pipeline = pipeline()
        local.file = file(duration = StageDuration.Pending(DurationPlan(DURATION_MS, DurationCheck.FLAC_END)))
        local.walked = null
        acoustId.on(SESSION, ID_ONLY)

        val result = pipeline.runOpened()

        assertThat(local.walks).hasSize(1)
        assertThat(result.durationMs).isEqualTo(DURATION_MS)
        assertThat(acoustIdRequests().single().body).contains("duration=214")
    }

    @Test
    fun `a walk that finds no length sends nothing to AcoustID, decodes nothing and still runs the text search`() = runTest {
        val pipeline = pipeline()
        local.file = file(duration = StageDuration.Pending(DurationPlan(null, DurationCheck.WALK)))
        local.walked = null

        val result = pipeline.runOpened()

        assertThat(result.durationMs).isNull()
        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Skipped(SkipReason.NO_DURATION))
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(fingerprints.computes).isZero()
        assertThat(searches()).isNotEmpty()
        assertThat(searches().first().path).doesNotContain("dur")
    }

    @Test
    fun `an unknown length sends nothing to AcoustID and walks nothing`() = runTest {
        val pipeline = pipeline()
        local.file = file(duration = StageDuration.Unknown)

        val result = pipeline.runOpened()

        assertThat(local.walks).isEmpty()
        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Skipped(SkipReason.NO_DURATION))
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(fingerprints.computes).isZero()
        assertThat(result.found().band).isEqualTo(Band.MEDIUM)
    }

    // The stop rule.

    @Test
    fun `a tag recording ID in the high band skips the ISRC, the fingerprint and the text search`() = runTest {
        val pipeline = pipeline()
        local.file = file(tags.copy(recordingMbid = FakeMusicBrainz.HARBOR_RECORDING, isrc = FakeMusicBrainz.KNOWN_ISRC))

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.KNOWN_IDS)).isEqualTo(StageResult.Ran(1))
        assertThat(result.stage(PipelineStage.ISRC)).isEqualTo(StageResult.Skipped(SkipReason.STOPPED_EARLY))
        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Skipped(SkipReason.STOPPED_EARLY))
        assertThat(result.stage(PipelineStage.TEXT_SEARCH)).isEqualTo(StageResult.Skipped(SkipReason.STOPPED_EARLY))
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(fingerprints.computes).isZero()
        assertThat(searches()).isEmpty()
        assertThat(musicBrainzRequests()).noneMatch { it.path.startsWith("/ws/2/isrc/") }
        assertThat(result.found().band).isEqualTo(Band.HIGH)
        assertThat(result.found().choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
    }

    @Test
    fun `an ISRC hit in the high band is looked up and skips the fingerprint`() = runTest {
        val pipeline = pipeline()
        local.file = file(tags.copy(isrc = FakeMusicBrainz.KNOWN_ISRC))

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.ISRC)).isEqualTo(StageResult.Ran(1))
        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Skipped(SkipReason.STOPPED_EARLY))
        assertThat(musicBrainzRequests().first().path).startsWith("/ws/2/isrc/${FakeMusicBrainz.KNOWN_ISRC}")
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(result.found().band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `a high-band fingerprint skips the text search`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, ID_ONLY)

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Ran(1))
        assertThat(result.stage(PipelineStage.TEXT_SEARCH)).isEqualTo(StageResult.Skipped(SkipReason.STOPPED_EARLY))
        assertThat(searches()).isEmpty()
    }

    @Test
    fun `a medium-band fingerprint does not stop the run`() = runTest {
        val pipeline = pipeline()
        // Two recordings at 0.97: no gap, so the medium band.
        acoustId.on(SESSION, FakeAcoustId.LOOKUP)

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isInstanceOf(StageResult.Ran::class.java)
        assertThat(result.stage(PipelineStage.TEXT_SEARCH)).isInstanceOf(StageResult.Ran::class.java)
        assertThat(searches()).isNotEmpty()
        assertThat(result.found().band).isEqualTo(Band.MEDIUM)
    }

    @Test
    fun `stages without the values they need are skipped and send nothing`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, ID_ONLY)

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.KNOWN_IDS)).isEqualTo(StageResult.Skipped(SkipReason.NO_VALUE))
        assertThat(result.stage(PipelineStage.ISRC)).isEqualTo(StageResult.Skipped(SkipReason.NO_VALUE))
        assertThat(musicBrainzRequests()).noneMatch { it.path.startsWith("/ws/2/isrc/") }
    }

    // Release choice.

    @Test
    fun `a tag release MBID whose release contains the recording is the suggested edition`() = runTest {
        val pipeline = pipeline()
        local.file = file(
            tags.copy(recordingMbid = FakeMusicBrainz.HARBOR_RECORDING, releaseMbid = FakeMusicBrainz.HARBOR_RELEASE),
        )

        val result = pipeline.runOpened()

        val suggested = result.found().choice.suggested!!
        assertThat(suggested.source).isEqualTo(EditionSource.TAG)
        assertThat(suggested.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        // The request budget of a file with tag IDs: one recording and one release lookup, no browse.
        assertThat(log.entries.map { it.path.substringBefore('?') }).containsExactly(
            "/ws/2/recording/${FakeMusicBrainz.HARBOR_RECORDING}",
            "/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}",
        )
    }

    // Outcomes that send nothing.

    @Test
    fun `a closed gate ends the run before stage 0 and sends nothing`() = runTest {
        val pipeline = pipeline()
        gate.set(OnlineConsent.NONE)

        val result = pipeline.runOpened()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Disabled)
        assertNothingDone()
    }

    @Test
    fun `a stopped MusicBrainz ends the run before stage 0 and sends nothing`() = runTest {
        val pipeline = pipeline()
        statuses.stop(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED)

        val result = pipeline.runOpened()

        assertThat(result.outcome)
            .isEqualTo(IdentificationOutcome.ProviderStopped(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED))
        assertNothingDone()
    }

    @Test
    fun `no validated network ends the run before stage 0 and sends nothing`() = runTest {
        val pipeline = pipeline()
        connectivity.current = NetworkState.NO_NETWORK

        val result = pipeline.runOpened()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.NoNetwork)
        assertNothingDone()
    }

    @Test
    fun `a captive portal ends the run before stage 0 and sends nothing`() = runTest {
        val pipeline = pipeline()
        connectivity.current = NetworkState.CAPTIVE_PORTAL

        val result = pipeline.runOpened()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.CaptivePortal)
        assertNothingDone()
    }

    private fun assertNothingDone() {
        assertThat(log.size).isZero()
        assertThat(local.reads).isEmpty()
        assertThat(fingerprints.computes).isZero()
        assertThat(progress).isEmpty()
    }

    @Test
    fun `a gate that closes during the run ends it as online lookups off and sends nothing more`() = runTest {
        val pipeline = pipeline()
        // Stage 1 asks MusicBrainz once; the gate closes while the file decodes.
        local.file = file(tags.copy(recordingMbid = UNKNOWN_RECORDING))
        fingerprints.whileComputing = { gate.set(OnlineConsent.NONE) }

        val result = pipeline.runOpened()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Disabled)
        assertThat(log.entries.map { it.path.substringBefore('?') }).containsExactly("/ws/2/recording/$UNKNOWN_RECORDING")
    }

    @Test
    fun `a MusicBrainz that refuses the User-Agent during the run ends it as stopped`() = runTest {
        val pipeline = pipeline()
        local.file = file(tags.copy(recordingMbid = FakeMusicBrainz.HARBOR_RECORDING))
        musicBrainz.enqueue(FakeMusicBrainz.userAgentRejected())

        val result = pipeline.runOpened()

        assertThat(result.outcome)
            .isEqualTo(IdentificationOutcome.ProviderStopped(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED))
        assertThat(log.size).isEqualTo(1)
    }

    @Test
    fun `a build without a key sends nothing to AcoustID, decodes nothing and reports the missing key`() = runTest {
        val pipeline = pipeline(key = "")
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Skipped(SkipReason.NO_KEY))
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(fingerprints.computes).isZero()
        assertThat(searches()).isNotEmpty()
        assertThat(result.outcome).isEqualTo(IdentificationOutcome.NoKey)
    }

    @Test
    fun `a stopped AcoustID is not asked, decodes nothing and is reported when nothing is found`() = runTest {
        val pipeline = pipeline()
        statuses.stop(ProviderId.ACOUSTID, StopReason.INVALID_API_KEY)
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Skipped(SkipReason.PROVIDER_STOPPED))
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(fingerprints.computes).isZero()
        assertThat(result.outcome)
            .isEqualTo(IdentificationOutcome.ProviderStopped(ProviderId.ACOUSTID, StopReason.INVALID_API_KEY))
    }

    @Test
    fun `an AcoustID that refuses the key during the run fails the stage and the text search still runs`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, FakeAcoustId.ERROR_4)

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isInstanceOf(StageResult.Failed::class.java)
        assertThat(result.stage(PipelineStage.TEXT_SEARCH)).isInstanceOf(StageResult.Ran::class.java)
        assertThat(result.found().band).isEqualTo(Band.MEDIUM)
    }

    @Test
    fun `an AcoustID that refuses the key during the run reports it as stopped when nothing is found`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, FakeAcoustId.ERROR_4)
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isInstanceOf(StageResult.Failed::class.java)
        assertThat(result.outcome)
            .isEqualTo(IdentificationOutcome.ProviderStopped(ProviderId.ACOUSTID, StopReason.INVALID_API_KEY))
    }

    @Test
    fun `a stage that failed besides a refused key reports its provider as unavailable first`() = runTest {
        val pipeline = pipeline()
        local.file = file(tags.copy(recordingMbid = FakeMusicBrainz.HARBOR_RECORDING))
        // The recording lookup stays busy through its retries; the searches after it find nothing.
        musicBrainz.enqueue(*Array(4) { FakeMusicBrainz.busy(503) })
        acoustId.on(SESSION, FakeAcoustId.ERROR_4)
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.KNOWN_IDS)).isInstanceOf(StageResult.Failed::class.java)
        assertThat(result.stage(PipelineStage.FINGERPRINT)).isInstanceOf(StageResult.Failed::class.java)
        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Unavailable(ProviderId.MUSICBRAINZ))
    }

    @Test
    fun `an AcoustID that stops after its stage was checked only skips the stage`() = runTest {
        val pipeline = pipeline()
        // Another run's error 4 stops AcoustID while this file decodes.
        fingerprints.whileComputing = { statuses.stop(ProviderId.ACOUSTID, StopReason.INVALID_API_KEY) }

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Skipped(SkipReason.PROVIDER_STOPPED))
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(result.stage(PipelineStage.TEXT_SEARCH)).isInstanceOf(StageResult.Ran::class.java)
        assertThat(result.found().band).isEqualTo(Band.MEDIUM)
    }

    @Test
    fun `a fingerprint cache that cannot be read counts as a miss`() = runTest {
        val pipeline = pipeline()
        fingerprints.cacheFailure = IllegalStateException("database closed")
        acoustId.on(SESSION, ID_ONLY)

        val result = pipeline.runOpened()

        assertThat(fingerprints.computes).isEqualTo(1)
        assertThat(result.fingerprint).isEqualTo(FingerprintSource.COMPUTED)
        assertThat(result.found().band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `a fingerprint computation that fails in its own way fails the stage and the text search still runs`() = runTest {
        val pipeline = pipeline()
        fingerprints.runtimeFailure = IllegalStateException("codec released")

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isInstanceOf(StageResult.Failed::class.java)
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(result.stage(PipelineStage.TEXT_SEARCH)).isInstanceOf(StageResult.Ran::class.java)
    }

    @Test
    fun `a stage 0 that fails in its own way ends the run as unreadable`() = runTest {
        val pipeline = pipeline()
        local.failure = IllegalStateException("binder gone")

        val result = pipeline.runOpened()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Unreadable)
        assertThat(log.size).isZero()
    }

    @Test
    fun `an unreadable file ends the run and sends nothing`() = runTest {
        val pipeline = pipeline()
        local.file = null

        val result = pipeline.runOpened()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Unreadable)
        assertThat(log.size).isZero()
        assertThat(fingerprints.computes).isZero()
    }

    // Retry later (TI §3.2).

    @Test
    fun `an empty AcoustID result marks the file for a retry`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, FakeAcoustId.EMPTY)
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)

        val result = pipeline.runOpened()

        assertThat(result.retryLater).isTrue()
        assertThat(result.found().band).isEqualTo(Band.NO_MATCH)
        assertThat(result.stage(PipelineStage.FINGERPRINT)).isEqualTo(StageResult.Ran(0))
    }

    @Test
    fun `a text candidate of at least 0_50 takes the retry mark off`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, FakeAcoustId.EMPTY)

        val result = pipeline.runOpened()

        assertThat(result.retryLater).isFalse()
        assertThat(result.found().band).isEqualTo(Band.MEDIUM)
    }

    // Failing stages.

    @Test
    fun `a stage that fails is traced and the next one runs`() = runTest {
        val pipeline = pipeline(key = "")
        local.file = file(tags.copy(recordingMbid = FakeMusicBrainz.HARBOR_RECORDING))
        musicBrainz.enqueue(*Array(4) { FakeMusicBrainz.busy(503) })

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.KNOWN_IDS)).isInstanceOf(StageResult.Failed::class.java)
        assertThat(result.stage(PipelineStage.TEXT_SEARCH)).isInstanceOf(StageResult.Ran::class.java)
        assertThat(result.found().band).isEqualTo(Band.MEDIUM)
    }

    @Test
    fun `nothing found after a failed stage reports the provider as unavailable`() = runTest {
        val pipeline = pipeline()
        acoustId.enqueue(*Array(4) { FakeMusicBrainz.busy(503) })
        musicBrainz.onSearch("recording:", FakeMusicBrainz.SEARCH_EMPTY)

        val result = pipeline.runOpened()

        assertThat(result.stage(PipelineStage.FINGERPRINT)).isInstanceOf(StageResult.Failed::class.java)
        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Unavailable(ProviderId.ACOUSTID))
    }

    @Test
    fun `a winner whose releases cannot be fetched reports MusicBrainz as unavailable`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, ID_ONLY)
        // The recording lookup of the ID-only winner goes through; every browse attempt is busy.
        musicBrainzServer.dispatcher = log.wrap(BusyBrowses(musicBrainz))

        val result = pipeline.runOpened()

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Unavailable(ProviderId.MUSICBRAINZ))
    }

    // Request caps (TI §3 request budget).

    @Test
    fun `a high-band fingerprint hit costs at most three requests plus the release pages`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, ID_ONLY)

        val result = pipeline.runOpened()

        assertThat(result.found().choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(pages()).hasSize(2)
        assertThat(log.size - pages().size).isLessThanOrEqualTo(3)
        assertThat(log.entries.map { it.path.substringBefore('?') }).containsExactly(
            "/v2/lookup",
            "/ws/2/recording/${FakeMusicBrainz.HARBOR_RECORDING}",
            "/ws/2/release",
            "/ws/2/release",
            "/ws/2/release/${FakeMusicBrainz.HARBOR_RELEASE}",
        )
        assertMusicBrainzSpacing()
    }

    @Test
    fun `a text-only file costs at most four searches plus one release page and one release lookup`() = runTest {
        val pipeline = pipeline(key = "")
        // Every variant with the artist finds nothing, so all four variants go out; the last one finds the song.
        musicBrainz.onSearch("artist:", FakeMusicBrainz.SEARCH_EMPTY)
        musicBrainz.onBrowse(
            "recording",
            FakeMusicBrainz.HARBOR_RECORDING,
            mapOf(0 to Fixture("musicbrainz/release-browse-harbor.json")),
            status = "official",
        )

        val result = pipeline.runOpened()

        assertThat(result.found().choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(searches()).hasSize(LookupPlan.MAX_TEXT_VARIANTS)
        assertThat(musicBrainzRequests()).hasSize(LookupPlan.MAX_TEXT_VARIANTS + 2)
        assertThat(acoustIdRequests()).isEmpty()
        assertMusicBrainzSpacing()
    }

    // Progress and cancellation.

    @Test
    fun `progress names the stages that run in order and counts the release pages`() = runTest {
        val pipeline = pipeline()
        acoustId.on(SESSION, ID_ONLY)

        pipeline.runOpened()

        assertThat(progress).containsExactly(
            PipelineProgress.Started(PipelineStage.LOCAL_READ),
            PipelineProgress.Started(PipelineStage.FINGERPRINT),
            PipelineProgress.Decoding,
            PipelineProgress.Started(PipelineStage.RELEASE_CHOICE),
            PipelineProgress.ReleasePages(FakeMusicBrainz.HARBOR_RECORDING, 1),
            PipelineProgress.ReleasePages(FakeMusicBrainz.HARBOR_RECORDING, 2),
        )
    }

    @Test
    fun `a cancelled run sends nothing more and gives no outcome`() = runTest {
        val pipeline = pipeline()
        val decoding = CompletableDeferred<Unit>()
        fingerprints.suspendUntil = CompletableDeferred()
        fingerprints.whileComputing = { decoding.complete(Unit) }
        var result: PipelineResult? = null

        val job = launch { result = pipeline.runOpened() }
        decoding.await()
        job.cancel()
        job.join()
        runCurrent()

        assertThat(job.isCancelled).isTrue()
        assertThat(result).isNull()
        assertThat(acoustIdRequests()).isEmpty()
        assertThat(progress.last()).isEqualTo(PipelineProgress.Decoding)
    }

    /** MusicBrainz whose release browses stay busy; everything else as [fake] answers it. */
    private class BusyBrowses(private val fake: FakeMusicBrainz) : mockwebserver3.Dispatcher() {
        override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse =
            if (request.url.encodedPath.endsWith("/release") && request.url.queryParameter("recording") != null) {
                FakeMusicBrainz.busy(503)
            } else {
                fake.dispatch(request)
            }
    }

    private companion object {
        const val API_KEY = "testKey123"
        const val KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DURATION_MS = 214_000L
        const val SESSION = FakeFingerprints.SESSION
        const val UNKNOWN_RECORDING = "99999999-9999-4999-8999-999999999999"
        const val CLOSE_SCORES_BEST = "a1d2e3f4-0516-4273-8495-a6b7c8d9e0f1"
        val RAW = FakeFingerprints.RAW
        val ID_ONLY = Fixture("acoustid/id-only.json")
    }
}
