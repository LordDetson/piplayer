package io.github.lorddetson.piplayer.ui.identification

import android.content.Context
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.identification.caa.CoverUrls
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.pipeline.AppPipeline
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.release.ReleaseRequest
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.Bands
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.library.details.TrackDetailsSources
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The review's backend in the app (TI §6, review screen A): the app's pipeline and services, track details for the
 * values shown now, and the review's decisions ([ReviewDecisions]) over the library and the saved covers. Writes (Apply
 * with its cover, Keep as-is, Skip, Undo and the disclosure's answers) run in a scope of their own, so that leaving the
 * screen never cuts one short.
 */
class AppReviewBackend(context: Context) : ReviewBackend {

    private val app = context.applicationContext
    private val services by lazy { IdentificationServices.of(app) }
    private val pipeline by lazy { AppPipeline.of(app) }
    private val gate get() = (app as PiPlayerApplication).onlineGate

    override suspend fun identify(file: FileRef, onProgress: (PipelineProgress) -> Unit): PipelineResult =
        // Off the main thread: the services build their HTTP client on first use, and the run reads and waits.
        withContext(Dispatchers.IO) { pipeline.run(file, onProgress) }

    override suspend fun current(file: FileRef): TrackDetails? = withContext(Dispatchers.IO) {
        when (file) {
            is FileRef.Library -> if (Library.mayRead(app)) TrackDetailsSources.library(app).read(file.fileId) else null
            is FileRef.Opened -> TrackDetailsSources.opened(app).read(file.uri)
        }
    }

    override suspend fun protectedFields(): Set<ProtectedField> = gate.settings.protectedFields.first()

    override suspend fun cover(details: ReleaseDetails): CaaResult<CoverImage> = withContext(Dispatchers.IO) {
        val sources = CoverUrls.sources(details.releaseId, details.releaseGroupId, details.coverArt)
        if (sources.isEmpty()) CaaResult.NotFound else services.coverArtArchive.cover(sources, CoverSize.EMBED)
    }

    override suspend fun edition(match: RecordingMatch, result: PipelineResult): SuggestedEdition? = withContext(Dispatchers.IO) {
        val tags = result.file?.tags
        val request = ReleaseRequest(
            albumTag = tags?.album,
            trackTotalTag = tags?.trackTotal,
            trackNumberTag = result.hint?.trackNumber,
            durationMs = result.durationMs,
            tagReleaseId = tags?.releaseMbid,
            folder = result.file?.folder,
            country = Locale.getDefault().country.ifEmpty { null },
        )
        // A recording the run browsed already has its releases ranked: no second browse (TI §4.2, §5.1).
        val choice = (result.outcome as? IdentificationOutcome.Found)?.choice
        if (choice != null && match.recordingId in choice.ranked) {
            return@withContext services.releases.suggestionFor(choice, match.recordingId, request)
        }
        // Otherwise the candidate alone, as release choice ranks a winner, with the run's own file values.
        val ranking = Ranking(listOf(match), match.score, Bands.of(match.score, null))
        services.releases.choose(ranking, request).suggested
    }

    override suspend fun apply(request: ApplyRequest, cover: CoverImage?): Long? = write { ReviewDecisions.of(app).apply(request, cover) }

    override suspend fun canKeepOrSkip(target: IdentificationTarget.LibraryFile): Boolean = ReviewDecisions.of(app).canKeepOrSkip(target)

    override suspend fun keepAsIs(target: IdentificationTarget.LibraryFile): Long? = write { ReviewDecisions.of(app).keepAsIs(target) }

    override suspend fun skip(target: IdentificationTarget.LibraryFile): Long? = write { ReviewDecisions.of(app).skip(target) }

    override suspend fun undo(decisionId: Long): Boolean = write { ReviewDecisions.of(app).undo(decisionId) }

    override suspend fun enable() = write { gate.enable() }

    override suspend fun notNow() = write { gate.notNow() }

    override fun now(): Instant = services.clock.instant()

    /** Runs [block] in the writes' scope and waits for it; a caller that goes leaves it running. */
    private suspend fun <T> write(block: suspend () -> T): T = writes.async { block() }.await()

    private companion object {
        val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
