package io.github.lorddetson.piplayer.ui.identification

import android.content.Context
import androidx.core.net.toUri
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.caa.CaaImage
import io.github.lorddetson.piplayer.identification.caa.CaaReleaseIndex
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.musicbrainz.RefLookup
import io.github.lorddetson.piplayer.identification.pipeline.AppPipeline
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.release.Edition
import io.github.lorddetson.piplayer.identification.release.EditionList
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.text.MusicBrainzRef
import io.github.lorddetson.piplayer.identification.text.RecordingQuery
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.library.details.TrackDetailsSources
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The review's backend in the app (TI §6, review screens A and B): the app's pipeline and services, track details for the
 * values shown now, and the review's decisions ([ReviewDecisions]) over the library and the saved covers. Writes (Apply
 * with its cover, Keep as-is, Skip, Undo and the disclosure's answers) run in a scope of their own, so that leaving the
 * screen never cuts one short. [batchUuid] is the batch whose card opened the review (TI §8): every decision the screen
 * makes then carries it, so the batch's cards find the track decided; null for a single-file review.
 */
class AppReviewBackend(context: Context, private val batchUuid: String? = null) : ReviewBackend {

    private val app = context.applicationContext
    private val services by lazy { IdentificationServices.of(app) }
    private val network by lazy { ReviewNetwork(services) }
    private val pipeline by lazy { AppPipeline.of(app) }
    private val gate get() = (app as PiPlayerApplication).onlineGate

    override suspend fun identify(file: FileRef, onProgress: (PipelineProgress) -> Unit): PipelineResult =
        // Off the main thread: the services build their HTTP client on first use, and the run reads and waits.
        withContext(Dispatchers.IO) { pipeline.run(file, onProgress = onProgress) }

    override suspend fun current(file: FileRef): TrackDetails? = withContext(Dispatchers.IO) {
        when (file) {
            is FileRef.Library -> if (Library.mayRead(app)) TrackDetailsSources.library(app).read(file.fileId) else null
            is FileRef.Opened -> TrackDetailsSources.opened(app).read(file.uri)
        }
    }

    override suspend fun protectedFields(): Set<ProtectedField> = gate.settings.protectedFields.first()

    override suspend fun cover(details: ReleaseDetails): CaaResult<CoverImage> = withContext(Dispatchers.IO) { network.cover(details) }

    override suspend fun edition(match: RecordingMatch, result: PipelineResult): SuggestedEdition? =
        withContext(Dispatchers.IO) { network.edition(match, result) }

    override suspend fun editions(suggested: SuggestedEdition, result: PipelineResult): EditionList =
        withContext(Dispatchers.IO) { network.editions(suggested, result) }

    override suspend fun openEdition(edition: Edition, suggested: SuggestedEdition, result: PipelineResult): SuggestedEdition? =
        withContext(Dispatchers.IO) { network.openEdition(edition, suggested, result) }

    override suspend fun search(query: RecordingQuery): List<TextCandidate> = withContext(Dispatchers.IO) { network.search(query) }

    override suspend fun lookup(ref: MusicBrainzRef): RefLookup = withContext(Dispatchers.IO) { network.lookup(ref) }

    override suspend fun releaseImages(releaseId: String): CaaResult<CaaReleaseIndex> =
        withContext(Dispatchers.IO) { network.releaseImages(releaseId) }

    override suspend fun image(image: CaaImage, px: Int?): CaaResult<CoverImage> = withContext(Dispatchers.IO) { network.image(image, px) }

    override suspend fun embeddedImages(uri: String): List<EmbeddedImage>? = withContext(Dispatchers.IO) { EmbeddedImages.read(app, uri.toUri()) }

    override suspend fun canEdit(target: IdentificationTarget): Boolean = ReviewDecisions.of(app).canEdit(target)

    override suspend fun applyWithEdit(request: ApplyRequest?, cover: CoverImage?, edit: ReviewEdit): Applied? =
        write { ReviewDecisions.of(app).applyWithEdit(request, cover, edit, batchUuid) }

    override suspend fun apply(request: ApplyRequest, cover: CoverImage?): Long? =
        write { ReviewDecisions.of(app).apply(request, cover, batchUuid) }

    override suspend fun canKeepOrSkip(target: IdentificationTarget.LibraryFile): Boolean = ReviewDecisions.of(app).canKeepOrSkip(target)

    override suspend fun keepAsIs(target: IdentificationTarget.LibraryFile): Long? = write { ReviewDecisions.of(app).keepAsIs(target, batchUuid) }

    override suspend fun skip(target: IdentificationTarget.LibraryFile): Long? = write { ReviewDecisions.of(app).skip(target, batchUuid) }

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
