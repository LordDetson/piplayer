package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.caa.CaaImage
import io.github.lorddetson.piplayer.identification.caa.CaaReleaseIndex
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.musicbrainz.RefLookup
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
import io.github.lorddetson.piplayer.library.details.PictureSize
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.time.Instant

/**
 * What the review (TI §6, review screen A) asks of the app: the run, the values shown now, the proposed cover, another
 * candidate's edition and the decisions. Every network request goes through the app's services, whose checks (the
 * online gate, a provider's stop, the network) come before anything is sent; the review never asks them itself.
 */
interface ReviewBackend {

    /** One run of the pipeline for [file] (TI §3 "One run"); cancelling the caller ends it. */
    suspend fun identify(file: FileRef, onProgress: (PipelineProgress) -> Unit): PipelineResult

    /** What track details show of [file] now (docs/library.md §18); null when it cannot be read. */
    suspend fun current(file: FileRef): TrackDetails?

    /** The fields identification leaves alone now (TI §6, §10). */
    suspend fun protectedFields(): Set<ProtectedField>

    /** The front image of [details]' release at the embedding size, with the fallbacks of TI §5.2. */
    suspend fun cover(details: ReleaseDetails): CaaResult<CoverImage>

    /**
     * The suggested edition of another candidate [match] of [result] (TI §4.2, §5.1), with the run's file values; null
     * when no release contains it. Throws an IOException when a request fails.
     */
    suspend fun edition(match: RecordingMatch, result: PipelineResult): SuggestedEdition?

    /**
     * Apply (TI §7.1): saves [cover] first when it is given (TI §5.2: as part of Apply), then stores [request]. The
     * decision's ID, or null when nothing was written.
     */
    suspend fun apply(request: ApplyRequest, cover: CoverImage?): Long?

    /**
     * Whether Keep as-is and Skip can be stored for [target]: an identity holds its key (TI §6, §10.2). They make no
     * identity, as Apply does, so a file the library has not read and attached yet has neither.
     */
    suspend fun canKeepOrSkip(target: IdentificationTarget.LibraryFile): Boolean

    /** Keep as-is for the track of [target] (TI §1); the decision's ID, or null when nothing was written. */
    suspend fun keepAsIs(target: IdentificationTarget.LibraryFile): Long?

    /** Skip for the track of [target] with the library's stamp of its file (TI §1); the decision's ID, or null. */
    suspend fun skip(target: IdentificationTarget.LibraryFile): Long?

    /** Undoes the decision [decisionId] (TI §7.1); whether it was undone now. */
    suspend fun undo(decisionId: Long): Boolean

    /** The disclosure's Enable through the app's gate (TI §9); the gate is open when it returns, unless the write failed. */
    suspend fun enable()

    /** The disclosure's Not now through the app's gate. */
    suspend fun notNow()

    /** Now: when the run's or a candidate's release reached the review, the provenance's fetch time (TI §6, §7.1). */
    fun now(): Instant

    /**
     * The edition picker's list (TI §4.2 step 6) of the release group of [suggested], the suggested edition of a candidate
     * of [result]. Throws an IOException when a request fails or may not be sent.
     */
    suspend fun editions(suggested: SuggestedEdition, result: PipelineResult): EditionList

    /**
     * Choosing [edition] in the picker of [suggested] (TI §4.2 step 6): the edition with the file mapped onto it, the
     * song's own recording or another one at its place; null when it does not contain the song. Throws an IOException.
     */
    suspend fun openEdition(edition: Edition, suggested: SuggestedEdition, result: PipelineResult): SuggestedEdition?

    /** Manual search's free text (TI §3.3): one manual query. Throws an IOException. */
    suspend fun search(query: RecordingQuery): List<TextCandidate>

    /** Manual search's pasted link or ID (TI §3.3): a recording or release lookup. Throws an IOException. */
    suspend fun lookup(ref: MusicBrainzRef): RefLookup

    /** The cover picker's images of [releaseId] (TI §5.2): its Cover Art Archive index. Throws an IOException. */
    suspend fun releaseImages(releaseId: String): CaaResult<CaaReleaseIndex>

    /** An image of a release index at [px] (its original for null), with the fallbacks of TI §5.2. Throws an IOException. */
    suspend fun image(image: CaaImage, px: Int?): CaaResult<CoverImage>

    /** The pictures embedded in the file at [uri], as the player's extractors read them (TI §6, review screen B). */
    suspend fun embeddedImages(uri: String): List<EmbeddedImage>

    /**
     * Whether an edit decision can be stored for [target]: an identity holds its key (TI §7.1, §10.2). An edit makes no
     * identity, as Apply does.
     */
    suspend fun canEdit(target: IdentificationTarget): Boolean

    /**
     * Apply with the user's values (TI §6, review screen B): stores [request] (with [cover], as [apply] does) when there is
     * one, then [edit] as an edit decision by the identity of the key. Null when nothing was written.
     */
    suspend fun applyWithEdit(request: ApplyRequest?, cover: CoverImage?, edit: ReviewEdit): Applied?
}

/** A picture embedded in the file (TI §6, review screen B), with its [size] from its header when it says it. */
class EmbeddedImage(val bytes: ByteArray, val size: PictureSize?) {
    override fun toString(): String = "EmbeddedImage($size, ${bytes.size} bytes)"
}

/**
 * The edit decision Apply stores after its identification (TI §6, review screen B): for [target], the parts of the
 * user's [values] (TI §10.2) and the [cover] the picker chose, saved for [releaseId].
 */
data class ReviewEdit(
    val target: IdentificationTarget,
    val values: Map<DecidedField, String?>,
    val cover: ProposedCover.Chosen?,
    val releaseId: String?,
)

/**
 * What Apply with the user's values stored: [decisionId] is the identification's, or the edit's when it was stored
 * alone; [editId] the edit's after an identification, null when there was none or it [editFailed].
 */
data class Applied(val decisionId: Long, val editId: Long? = null, val editFailed: Boolean = false)
