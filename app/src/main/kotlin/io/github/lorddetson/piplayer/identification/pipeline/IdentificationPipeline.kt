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
import io.github.lorddetson.piplayer.identification.score.RecordingScorer
import io.github.lorddetson.piplayer.identification.score.StageFinding
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.RecordingSearch
import io.github.lorddetson.piplayer.identification.text.TextSearch
import io.github.lorddetson.piplayer.identification.text.TrackHints
import io.github.lorddetson.piplayer.library.tagpass.StoredDuration
import io.github.lorddetson.piplayer.net.SendBlockedException
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * One identification of one file (TI §3 "One run"): the pre-checks, stage 0 with the length walked on demand, the known
 * IDs, the ISRC, the fingerprint and the text search, stopping at the first stage that reaches the high band, then
 * release choice and the release details. Network calls go through [services]; [local] reads the file, [fingerprints]
 * caches and computes its fingerprint, and [encoder] turns cached raw items into AcoustID's string. [country] is the
 * user's country for the release ranking (TI §4.2).
 *
 * What a run finds stays in its [PipelineResult]; nothing is stored here but the fingerprint and its AcoustID track ID.
 */
class IdentificationPipeline(
    private val services: IdentificationServices,
    private val local: LocalStage,
    /** The fingerprint cache of stage 3; readable so that a test can check which cache a pipeline was built over. */
    internal val fingerprints: FingerprintCache,
    private val encoder: FingerprintEncoder,
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

    /** The state of one run. */
    private inner class Run(private val onProgress: (PipelineProgress) -> Unit) {
        private val stages = mutableListOf<StageReport>()
        private val findings = mutableListOf<StageFinding>()
        private var file: LocalFile? = null
        private var hint: TrackHint? = null
        private var durationMs: Long? = null
        private var lengthWalked = false
        private var fingerprint: FingerprintSource? = null
        private var acoustIdTrackId: String? = null
        private var retryLater = false

        /** The first provider whose stage failed. */
        private var failed: ProviderId? = null

        /** Why AcoustID refused to answer, when it stopped for the process. */
        private var acoustIdStop: StopReason? = null

        /** A stage reached the high band, so the stages after it are skipped. */
        private var confident = false

        suspend fun run(ref: FileRef): PipelineResult = try {
            val read = localRead(ref) ?: return result(IdentificationOutcome.Unreadable)
            knownIds(read)
            isrc(read)
            fingerprint(read)
            textSearch()
            finish(read)
        } catch (e: Blocked) {
            result(e.outcome)
        }

        // Stage 0.

        private suspend fun localRead(ref: FileRef): LocalFile? {
            onProgress(PipelineProgress.Started(PipelineStage.LOCAL_READ))
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
                stages += StageReport(PipelineStage.LOCAL_READ, StageResult.Skipped(SkipReason.NO_VALUE), start.elapsedNow())
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
            stages += StageReport(PipelineStage.LOCAL_READ, StageResult.Ran(0), start.elapsedNow())
            return read
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
            val reason = when {
                confident -> SkipReason.STOPPED_EARLY
                stage !is FingerprintStage.Available -> SkipReason.NO_KEY
                stop != null -> SkipReason.PROVIDER_STOPPED
                duration == null -> SkipReason.NO_DURATION
                else -> null
            }
            if (stop != null) acoustIdStop = stop
            if (reason != null) {
                stages += StageReport(PipelineStage.FINGERPRINT, StageResult.Skipped(reason), ZERO)
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
                stages += StageReport(PipelineStage.FINGERPRINT, StageResult.Skipped(SkipReason.UNDECODABLE), start.elapsedNow())
                return
            } catch (e: IOException) {
                stages += StageReport(PipelineStage.FINGERPRINT, StageResult.Failed(e), start.elapsedNow())
                return
            }
            if (encoded.isEmpty()) {
                stages += StageReport(
                    PipelineStage.FINGERPRINT,
                    StageResult.Skipped(SkipReason.EMPTY_FINGERPRINT),
                    start.elapsedNow(),
                )
                return
            }
            val response = try {
                stage.acoustId.lookup(encoded, duration!!)
            } catch (e: ProviderStoppedException) {
                if (e.provider != ProviderId.ACOUSTID) throw Blocked(IdentificationOutcome.ProviderStopped(e.provider, e.reason))
                // AcoustID stopped after the stage was checked, by another run's error 4: its stop only skips the stage.
                acoustIdStop = e.reason
                stages += StageReport(
                    PipelineStage.FINGERPRINT,
                    StageResult.Skipped(SkipReason.PROVIDER_STOPPED),
                    start.elapsedNow(),
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
                stages += StageReport(PipelineStage.FINGERPRINT, StageResult.Failed(e), start.elapsedNow())
                return
            }
            response.results.maxByOrNull { it.score }?.id?.let { best ->
                acoustIdTrackId = best
                keep(key, best)
            }
            // An empty result may be a search timeout on the server: tried again later (TI §3.2).
            retryLater = response.results.isEmpty()
            val found = StageFinding.Fingerprint(AcoustIdCandidates.from(response, duration))
            add(found)
            stages += StageReport(PipelineStage.FINGERPRINT, StageResult.Ran(found.count()), start.elapsedNow())
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
                if (search.candidates.any { it.score.total >= TextSearch.STOP_SCORE }) retryLater = false
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
                choice = try {
                    services.releases.choose(ranking, request) { recording, pages ->
                        onProgress(PipelineProgress.ReleasePages(recording, pages))
                    }
                } catch (e: IOException) {
                    blockedOrNull(e)
                    stages += StageReport(PipelineStage.RELEASE_CHOICE, StageResult.Failed(e), start.elapsedNow())
                    // The recording cannot be applied without its release (TI §3 "One run").
                    return result(IdentificationOutcome.Unavailable(ProviderId.MUSICBRAINZ))
                }
                stages += StageReport(PipelineStage.RELEASE_CHOICE, StageResult.Ran(choice.ranked.size), start.elapsedNow())
            }
            val outcome = when {
                ranking.band != Band.NO_MATCH -> IdentificationOutcome.Found(ranking, choice, acoustIdTrackId)
                failed != null -> IdentificationOutcome.Unavailable(failed!!)
                acoustIdStop != null -> IdentificationOutcome.ProviderStopped(ProviderId.ACOUSTID, acoustIdStop!!)
                else -> IdentificationOutcome.finished(ranking, choice, acoustIdTrackId, services.fingerprintStage)
            }
            return result(outcome)
        }

        // Shared parts.

        /** Notes [stage] as skipped, by the stop rule or because the file lacks its value ([noValue]); whether it was. */
        private fun skipped(stage: PipelineStage, noValue: Boolean): Boolean {
            val reason = when {
                confident -> SkipReason.STOPPED_EARLY
                noValue -> SkipReason.NO_VALUE
                else -> return false
            }
            stages += StageReport(stage, StageResult.Skipped(reason), ZERO)
            return true
        }

        /**
         * Runs a stage that asks [provider]: its findings join the run, and a failure is traced and lets the next stage
         * run, unless the call may not be sent at all.
         */
        private suspend fun stage(stage: PipelineStage, provider: ProviderId, block: suspend () -> List<StageFinding>) {
            onProgress(PipelineProgress.Started(stage))
            val start = timeSource.markNow()
            val found = try {
                block()
            } catch (e: IOException) {
                blockedOrNull(e)
                failed = failed ?: provider
                stages += StageReport(stage, StageResult.Failed(e), start.elapsedNow())
                return
            }
            found.forEach(::add)
            stages += StageReport(stage, StageResult.Ran(found.sumOf { it.count() }), start.elapsedNow())
        }

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

        private fun result(outcome: IdentificationOutcome) = PipelineResult(
            outcome = outcome,
            file = file,
            hint = hint,
            durationMs = durationMs,
            lengthWalked = lengthWalked,
            stages = stages.toList(),
            fingerprint = fingerprint,
            retryLater = retryLater,
        )
    }

    private companion object {
        val ZERO = kotlin.time.Duration.ZERO
    }
}
