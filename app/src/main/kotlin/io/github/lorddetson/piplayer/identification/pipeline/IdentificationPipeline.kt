package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.identification.FingerprintStage
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdCandidates
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdException
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.fingerprint.DecodingException
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatus
import io.github.lorddetson.piplayer.identification.provider.ProviderStoppedException
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.release.ReleaseChoice
import io.github.lorddetson.piplayer.identification.release.ReleaseRequest
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.RecordingScorer
import io.github.lorddetson.piplayer.identification.score.StageFinding
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.RecordingSearch
import io.github.lorddetson.piplayer.identification.text.TextSearch
import io.github.lorddetson.piplayer.identification.text.TrackHints
import io.github.lorddetson.piplayer.library.db.LookupJobEntity
import io.github.lorddetson.piplayer.library.tagpass.StoredDuration
import io.github.lorddetson.piplayer.net.HttpTrace
import io.github.lorddetson.piplayer.net.SendBlockedException
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import kotlinx.coroutines.withContext

/**
 * One identification of one file (TI §3 "One run"): the pre-checks, stage 0 with the length walked on demand, the known
 * IDs, the ISRC, the fingerprint and the text search, stopping at the first stage that reaches the high band, then
 * release choice and the release details. Network calls go through [services]; [local] reads the file, [fingerprints]
 * caches and computes its fingerprint, and [encoder] turns cached raw items into AcoustID's string. [country] is the
 * user's country for the release ranking (TI §4.2).
 *
 * A run that goes through its stages stores its candidates, its trace and the change to its lookup job in [store], by
 * the file's content key (TI §10.1); the times there come from the services' clock.
 */
class IdentificationPipeline(
    private val services: IdentificationServices,
    private val local: LocalStage,
    /** The fingerprint cache of stage 3; readable so that a test can check which cache a pipeline was built over. */
    internal val fingerprints: FingerprintCache,
    private val encoder: FingerprintEncoder,
    /** Where a run keeps its candidates, trace and lookup job (TI §10.1); readable so that a test can check it. */
    internal val store: IdentificationStore,
    private val country: () -> String? = { Locale.getDefault().country.ifEmpty { null } },
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /**
     * Runs the stages for [file], reporting through [onProgress] on the caller's coroutine. Cancelling the caller ends
     * the run at its next request or decoder buffer, with no result.
     */
    suspend fun run(file: FileRef, onProgress: (PipelineProgress) -> Unit = {}): PipelineResult {
        services.awaitGateRead()
        // What every MusicBrainz attempt checks, in its order: the gate, MusicBrainz's stop, the network (TI §8).
        try {
            services.access(ProviderId.MUSICBRAINZ).check()
        } catch (e: SendBlockedException) {
            return PipelineResult(IdentificationOutcome.blocked(e) ?: throw e)
        }
        return Run(onProgress).run(file)
    }

    /** A call that may not send ended the run with [outcome]. */
    private class Blocked(val outcome: IdentificationOutcome) : Exception()

    /** What AcoustID answered in this run's stage 3. */
    private enum class AcoustIdAnswer { NONE, RESULTS, EMPTY }

    /** What a run does to its lookup job (TI §3.2). */
    private sealed interface JobChange {
        data object Keep : JobChange

        data object Drop : JobChange

        data class Save(val job: LookupJobEntity) : JobChange
    }

    /** The state of one run. */
    private inner class Run(private val onProgress: (PipelineProgress) -> Unit) {
        private val runMark = timeSource.markNow()
        private val runStartedAt = services.clock.millis()
        private val stages = mutableListOf<StageReport>()
        private val findings = mutableListOf<StageFinding>()
        private var file: LocalFile? = null
        private var hint: TrackHint? = null
        private var durationMs: Long? = null
        private var lengthWalked = false
        private var fingerprint: FingerprintSource? = null
        private var acoustIdTrackId: String? = null

        /** The lookup job of the file as stage 0's key found it; null for none or one that could not be read. */
        private var job: LookupJobEntity? = null
        private var answer = AcoustIdAnswer.NONE

        /** When AcoustID's empty answer came, by the services' clock. */
        private var emptyAt = 0L

        /** A text candidate of at least 0.50 cancels the retry of an empty AcoustID result (TI §3.2). */
        private var textCancels = false

        /** The first provider whose stage failed. */
        private var failed: ProviderId? = null

        /** Why AcoustID refused to answer, when it stopped for the process. */
        private var acoustIdStop: StopReason? = null

        /** A stage reached the high band, so the stages after it are skipped. */
        private var confident = false

        suspend fun run(ref: FileRef): PipelineResult = try {
            val read = localRead(ref) ?: return result(IdentificationOutcome.Unreadable)
            job = readJob(read.target.contentKey)
            knownIds(read)
            isrc(read)
            fingerprint(read)
            textSearch()
            finish(read)
        } catch (e: Blocked) {
            // What the earlier stages found is dropped, and nothing is stored (TI §3 "One run").
            result(e.outcome)
        }

        // Stage 0.

        private suspend fun localRead(ref: FileRef): LocalFile? {
            onProgress(PipelineProgress.Started(PipelineStage.LOCAL_READ))
            val startedAt = services.clock.millis()
            val start = timeSource.markNow()
            val read = try {
                local.read(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A file gone, a reader that failed in its own way: no content key either way.
                null
            }
            if (read == null) {
                // No content key: nothing to look up by or to keep a result under.
                stages += StageReport(PipelineStage.LOCAL_READ, StageResult.Skipped(SkipReason.NO_VALUE), start.elapsedNow(), startedAt)
                return null
            }
            file = read
            durationMs = when (val duration = read.duration) {
                is StageDuration.Exact -> duration.ms
                is StageDuration.Pending -> {
                    onProgress(PipelineProgress.WalkingLength)
                    lengthWalked = true
                    val walked = try {
                        local.walk(read, duration.plan)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    // The FLAC end check keeps the header value unless the last frame ends sooner (TI §3.1).
                    StoredDuration.afterWalk(duration.plan, walked).ms
                }
                StageDuration.Unknown -> null
            }?.takeIf { it > 0 }
            hint = TrackHints.assemble(read.tags, read.fileName, durationMs)
            stages += StageReport(PipelineStage.LOCAL_READ, StageResult.Ran(0), start.elapsedNow(), startedAt)
            return read
        }

        /** The file's lookup job; one that cannot be read counts as none (TI §3 "One run"). */
        private suspend fun readJob(contentKey: String): LookupJobEntity? = try {
            store.job(contentKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        // Stages 1 and 2.

        private suspend fun knownIds(read: LocalFile) {
            val mbid = read.tags?.recordingMbid?.let(MusicBrainzClient::plainMbid)
            if (skipped(PipelineStage.KNOWN_IDS, mbid == null)) return
            stage(PipelineStage.KNOWN_IDS, ProviderId.MUSICBRAINZ) {
                val recording = services.musicBrainz.lookupRecording(mbid!!)
                listOfNotNull(recording?.let { StageFinding.TagRecordingId(it.toCandidate()) })
            }
        }

        private suspend fun isrc(read: LocalFile) {
            val isrc = read.tags?.isrc?.let(MusicBrainzClient::plainIsrc)
            if (skipped(PipelineStage.ISRC, isrc == null)) return
            stage(PipelineStage.ISRC, ProviderId.MUSICBRAINZ) {
                listOf(StageFinding.Isrc(services.musicBrainz.lookupIsrc(isrc!!).map { it.toCandidate() }))
            }
        }

        // Stage 3.

        private suspend fun fingerprint(read: LocalFile) {
            val stage = services.fingerprintStage
            val stop = (services.status(ProviderId.ACOUSTID).value as? ProviderStatus.Stopped)?.reason
            val duration = durationMs
            val startedAt = services.clock.millis()
            val reason = when {
                confident -> SkipReason.STOPPED_EARLY
                stage !is FingerprintStage.Available -> SkipReason.NO_KEY
                stop != null -> SkipReason.PROVIDER_STOPPED
                job?.status == STATUS_RETRY_LATER && (job?.nextAttemptAt ?: 0) > startedAt -> SkipReason.RETRY_PENDING
                job?.status == STATUS_FINAL -> SkipReason.EMPTY_RESULT_FINAL
                duration == null -> SkipReason.NO_DURATION
                else -> null
            }
            if (stop != null) acoustIdStop = stop
            if (reason != null) {
                stages += StageReport(PipelineStage.FINGERPRINT, StageResult.Skipped(reason), ZERO, startedAt)
                return
            }
            stage as FingerprintStage.Available
            onProgress(PipelineProgress.Started(PipelineStage.FINGERPRINT))
            val start = timeSource.markNow()
            val key = read.target.contentKey
            val encoded = try {
                encoded(read)
            } catch (e: DecodingException) {
                // ALAC without a decoder, a pipe past the copy's bounds or one whose writer failed: text search follows.
                stages += StageReport(PipelineStage.FINGERPRINT, StageResult.Skipped(SkipReason.UNDECODABLE), start.elapsedNow(), startedAt)
                return
            } catch (e: IOException) {
                stages += StageReport(PipelineStage.FINGERPRINT, StageResult.Failed(e), start.elapsedNow(), startedAt)
                return
            }
            if (encoded.isEmpty()) {
                stages += StageReport(
                    PipelineStage.FINGERPRINT,
                    StageResult.Skipped(SkipReason.EMPTY_FINGERPRINT),
                    start.elapsedNow(),
                    startedAt,
                )
                return
            }
            val http = HttpTrace()
            val response = try {
                withContext(http) { stage.acoustId.lookup(encoded, duration!!) }
            } catch (e: ProviderStoppedException) {
                if (e.provider != ProviderId.ACOUSTID) throw Blocked(IdentificationOutcome.ProviderStopped(e.provider, e.reason))
                // AcoustID stopped after the stage was checked, by another run's error 4: its stop only skips the stage.
                acoustIdStop = e.reason
                stages += StageReport(
                    PipelineStage.FINGERPRINT,
                    StageResult.Skipped(SkipReason.PROVIDER_STOPPED),
                    start.elapsedNow(),
                    startedAt,
                    http.summary(),
                )
                return
            } catch (e: IOException) {
                blockedOrNull(e)
                // A refused key stops AcoustID for the process (TI §3.2); anything else is the service failing.
                if (e is AcoustIdException.InvalidApiKey) {
                    acoustIdStop = StopReason.INVALID_API_KEY
                } else {
                    failed = failed ?: ProviderId.ACOUSTID
                }
                stages += StageReport(PipelineStage.FINGERPRINT, StageResult.Failed(e), start.elapsedNow(), startedAt, http.summary())
                return
            }
            response.results.maxByOrNull { it.score }?.id?.let { best ->
                acoustIdTrackId = best
                keep(key, best)
            }
            // An empty result may be a search timeout on the server: tried again later (TI §3.2).
            if (response.results.isEmpty()) {
                answer = AcoustIdAnswer.EMPTY
                emptyAt = services.clock.millis()
            } else {
                answer = AcoustIdAnswer.RESULTS
            }
            val found = StageFinding.Fingerprint(AcoustIdCandidates.from(response, duration))
            add(found)
            stages += StageReport(
                PipelineStage.FINGERPRINT,
                StageResult.Ran(found.count()),
                start.elapsedNow(),
                startedAt,
                http.summary(),
                bestScore(listOf(found)),
            )
        }

        /** The cached fingerprint encoded from its raw items, or the string of the session that computes it now. */
        private suspend fun encoded(read: LocalFile): String {
            val cached = try {
                fingerprints.cached(read.target.contentKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // A cache that cannot be read now is a miss: the file is decoded again.
                null
            }
            if (cached != null) {
                fingerprint = FingerprintSource.CACHED
                return try {
                    encoder.encode(cached.raw)
                } catch (e: RuntimeException) {
                    throw IOException("The cached fingerprint could not be encoded", e)
                }
            }
            onProgress(PipelineProgress.Decoding)
            val computed = try {
                fingerprints.compute(read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                throw IOException("The fingerprint could not be computed", e)
            }
            fingerprint = FingerprintSource.COMPUTED
            return computed.encoded
        }

        /** Keeps the AcoustID track ID with the cached fingerprint; a failed write costs this run nothing. */
        private suspend fun keep(contentKey: String, trackId: String) {
            try {
                fingerprints.keepAcoustIdTrack(contentKey, trackId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Kept in memory for this run; the next lookup caches it again.
            }
        }

        // Stage 4.

        private suspend fun textSearch() {
            val hint = hint!!
            if (skipped(PipelineStage.TEXT_SEARCH, hint.title == null)) return
            stage(PipelineStage.TEXT_SEARCH, ProviderId.MUSICBRAINZ) {
                val search = TextSearch(RecordingSearch { services.musicBrainz.search(it) }).search(hint)
                // A text candidate of at least 0.50 cancels the retry of an empty AcoustID result (TI §3.2).
                if (search.candidates.any { it.score.total >= TextSearch.STOP_SCORE }) textCancels = true
                // A later variant that failed may have hidden the answer.
                if (search.failure != null) failed = failed ?: ProviderId.MUSICBRAINZ
                listOf(StageFinding.TextSearch(search.candidates.map { it.candidate }))
            }
        }

        // Release choice, release details and the outcome.

        private suspend fun finish(read: LocalFile): PipelineResult {
            val hint = hint!!
            val ranking = RecordingScorer.rank(hint, findings)
            var choice = ReleaseChoice.NONE
            if (ranking.band != Band.NO_MATCH) {
                onProgress(PipelineProgress.Started(PipelineStage.RELEASE_CHOICE))
                val startedAt = services.clock.millis()
                val start = timeSource.markNow()
                val request = ReleaseRequest(
                    albumTag = read.tags?.album,
                    trackTotalTag = read.tags?.trackTotal,
                    trackNumberTag = hint.trackNumber,
                    durationMs = durationMs,
                    tagReleaseId = read.tags?.releaseMbid,
                    folder = read.folder,
                    country = country(),
                )
                val http = HttpTrace()
                choice = try {
                    withContext(http) {
                        services.releases.choose(ranking, request) { recording, pages ->
                            onProgress(PipelineProgress.ReleasePages(recording, pages))
                        }
                    }
                } catch (e: IOException) {
                    blockedOrNull(e)
                    stages += StageReport(PipelineStage.RELEASE_CHOICE, StageResult.Failed(e), start.elapsedNow(), startedAt, http.summary())
                    // The recording cannot be applied without its release (TI §3 "One run").
                    return stored(IdentificationOutcome.Unavailable(ProviderId.MUSICBRAINZ), ranking, ReleaseChoice.NONE)
                }
                stages += StageReport(
                    PipelineStage.RELEASE_CHOICE,
                    StageResult.Ran(choice.ranked.size),
                    start.elapsedNow(),
                    startedAt,
                    http.summary(),
                    browses = choice.trace,
                )
            }
            val outcome = when {
                ranking.band != Band.NO_MATCH -> IdentificationOutcome.Found(ranking, choice, acoustIdTrackId)
                failed != null -> IdentificationOutcome.Unavailable(failed!!)
                acoustIdStop != null -> IdentificationOutcome.ProviderStopped(ProviderId.ACOUSTID, acoustIdStop!!)
                else -> IdentificationOutcome.finished(ranking, choice, acoustIdTrackId, services.fingerprintStage)
            }
            return stored(outcome, ranking, choice)
        }

        // Storing the run (TI §10.1).

        /**
         * The result of a run that went through its stages, after its candidates, its trace and the change to its lookup
         * job are stored in one transaction; rows that cannot be written cost the result nothing.
         */
        private suspend fun stored(outcome: IdentificationOutcome, ranking: Ranking, choice: ReleaseChoice): PipelineResult {
            val change = jobChange()
            val waiting = when (change) {
                is JobChange.Save -> change.job
                JobChange.Drop -> null
                JobChange.Keep -> job
            }?.takeIf { it.status == STATUS_RETRY_LATER }?.nextAttemptAt?.let(Instant::ofEpochMilli)
            val result = result(outcome, waiting)
            val key = file!!.target.contentKey
            val trace = RunTrace.rows(key, UUID.randomUUID().toString(), runStartedAt, result, ranking, runMark.elapsedNow())
            try {
                store.write {
                    replaceCandidates(key, StoredCandidates.rows(key, ranking, choice))
                    addTrace(trace)
                    val runs = tracedRuns(key)
                    if (runs.size > KEPT_RUNS) dropTracedRuns(key, runs.dropLast(KEPT_RUNS))
                    when (change) {
                        is JobChange.Save -> saveJob(change.job)
                        JobChange.Drop -> dropJob(key)
                        JobChange.Keep -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The outcome stands; the next run stores its own rows (TI §3 "One run").
            }
            return result
        }

        /** What this run does to the file's lookup job (TI §3.2, M3 part). */
        private fun jobChange(): JobChange {
            val job = job
            return when (answer) {
                AcoustIdAnswer.EMPTY -> when {
                    textCancels -> if (job != null) JobChange.Drop else JobChange.Keep
                    else -> {
                        val attempts = (job?.attempts ?: 0) + 1
                        val final = attempts >= EMPTY_ANSWERS
                        JobChange.Save(
                            LookupJobEntity(
                                contentKey = file!!.target.contentKey,
                                batchUuid = null,
                                phase = PHASE_LOOKUP,
                                status = if (final) STATUS_FINAL else STATUS_RETRY_LATER,
                                attempts = attempts,
                                nextAttemptAt = if (final) null else emptyAt + RETRY_DELAY.inWholeMilliseconds,
                                lastError = ERROR_EMPTY,
                            ),
                        )
                    }
                }
                AcoustIdAnswer.RESULTS -> if (job != null) JobChange.Drop else JobChange.Keep
                AcoustIdAnswer.NONE ->
                    if (textCancels && job?.status == STATUS_RETRY_LATER) JobChange.Drop else JobChange.Keep
            }
        }

        // Shared parts.

        /** Notes [stage] as skipped, by the stop rule or because the file lacks its value ([noValue]); whether it was. */
        private fun skipped(stage: PipelineStage, noValue: Boolean): Boolean {
            val reason = when {
                confident -> SkipReason.STOPPED_EARLY
                noValue -> SkipReason.NO_VALUE
                else -> return false
            }
            stages += StageReport(stage, StageResult.Skipped(reason), ZERO, services.clock.millis())
            return true
        }

        /**
         * Runs a stage that asks [provider]: its findings join the run, and a failure is traced and lets the next stage
         * run, unless the call may not be sent at all. What the HTTP layer sees of its requests goes into its report.
         */
        private suspend fun stage(stage: PipelineStage, provider: ProviderId, block: suspend () -> List<StageFinding>) {
            onProgress(PipelineProgress.Started(stage))
            val startedAt = services.clock.millis()
            val start = timeSource.markNow()
            val http = HttpTrace()
            val found = try {
                withContext(http) { block() }
            } catch (e: IOException) {
                blockedOrNull(e)
                failed = failed ?: provider
                stages += StageReport(stage, StageResult.Failed(e), start.elapsedNow(), startedAt, http.summary())
                return
            }
            found.forEach(::add)
            stages += StageReport(
                stage,
                StageResult.Ran(found.sumOf { it.count() }),
                start.elapsedNow(),
                startedAt,
                http.summary(),
                bestScore(found),
            )
        }

        /** The best score of what one stage found by itself (TI §4.1); null when it found nothing. */
        private fun bestScore(found: List<StageFinding>): Double? = RecordingScorer.rank(hint!!, found).best?.score

        /** Adds [finding] and applies the stop rule: everything found so far in the high band skips the later stages. */
        private fun add(finding: StageFinding) {
            findings += finding
            confident = RecordingScorer.rank(hint!!, findings).band == Band.HIGH
        }

        /** Ends the run when [error] says that nothing may be sent (TI §8) or that MusicBrainz refused the User-Agent. */
        private fun blockedOrNull(error: IOException) {
            IdentificationOutcome.blocked(error)?.let { throw Blocked(it) }
            if (error is MusicBrainzException.UserAgentRejected) {
                throw Blocked(IdentificationOutcome.ProviderStopped(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED))
            }
        }

        private fun StageFinding.count(): Int = when (this) {
            is StageFinding.TagRecordingId -> 1
            is StageFinding.Isrc -> recordings.size
            is StageFinding.Fingerprint -> candidates.count { it.recordingId != null }
            is StageFinding.TextSearch -> candidates.size
        }

        private fun result(outcome: IdentificationOutcome, retryAt: Instant? = null) = PipelineResult(
            outcome = outcome,
            file = file,
            hint = hint,
            durationMs = durationMs,
            lengthWalked = lengthWalked,
            stages = stages.toList(),
            fingerprint = fingerprint,
            retryLater = retryAt != null,
            retryAt = retryAt,
        )
    }

    companion object {

        /** Runs whose trace a content key keeps (TI §10.1). */
        const val KEPT_RUNS = 3

        /** How long an empty AcoustID result waits for its retry (TI §3.2). */
        val RETRY_DELAY = 1.hours

        /** Empty AcoustID answers after which the empty result is final for a single file (TI §3.2, M3). */
        const val EMPTY_ANSWERS = 2

        /** The values of `lookup_job` that a single-file run writes (TI §10.1). */
        const val PHASE_LOOKUP = "lookup"
        const val STATUS_RETRY_LATER = "retry_later"
        const val STATUS_FINAL = "final"
        const val ERROR_EMPTY = "acoustid_empty"

        private val ZERO = kotlin.time.Duration.ZERO
    }
}
