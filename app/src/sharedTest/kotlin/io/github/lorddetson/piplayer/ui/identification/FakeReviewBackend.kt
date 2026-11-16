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
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred

/**
 * A review backend over fixed pipeline results (TI §6, review screen A): each run takes the next of [results] (the last
 * one stays), after reporting [progress] and waiting for [runGate] when one is set. What the review asked is recorded
 * in [calls], in order; nothing is sent anywhere.
 */
class FakeReviewBackend(
    vararg results: PipelineResult,
    var details: TrackDetails? = ReviewFixtures.currentDetails(),
    var protected: Set<ProtectedField> = emptySet(),
    var coverResult: CaaResult<CoverImage> = CaaResult.Found(ReviewFixtures.cover()),
) : ReviewBackend {

    private val queue = ArrayDeque(results.toList())

    val calls: MutableList<String> = CopyOnWriteArrayList()

    /** The requests Apply stored, with the cover it saved. */
    val applied: MutableList<Pair<ApplyRequest, CoverImage?>> = CopyOnWriteArrayList()

    var progress: List<PipelineProgress> = emptyList()

    /** When set, a run waits for it after its progress; so does a cover fetch for [coverGate]. */
    @Volatile
    var runGate: CompletableDeferred<Unit>? = null

    @Volatile
    var coverGate: CompletableDeferred<Unit>? = null

    /** When set, Apply, Keep as-is, Skip and Undo wait for [writeGate], Enable for [enableGate], before they answer. */
    @Volatile
    var writeGate: CompletableDeferred<Unit>? = null

    @Volatile
    var enableGate: CompletableDeferred<Unit>? = null

    /** When set, Not now waits for it before it answers. */
    @Volatile
    var notNowGate: CompletableDeferred<Unit>? = null

    /** Whether an identity holds the key of a library target, which Keep as-is and Skip need. */
    @Volatile
    var identityHeld: Boolean = true

    /** What [now] answers; the tests move it to tell the times of a run, a load and an Apply apart. */
    @Volatile
    var clock: Instant = NOW

    /** When set, another candidate's edition waits for it. */
    @Volatile
    var editionGate: CompletableDeferred<Unit>? = null

    /** The edition of each other candidate by recording ID; a recording in [editionFailures] fails with an IOException. */
    val editions: MutableMap<String, SuggestedEdition?> = mutableMapOf()
    val editionFailures: MutableSet<String> = mutableSetOf()

    /** The decision IDs Apply, Keep as-is and Skip answer (null: nothing written), and Undo's answer. */
    var applyAnswer: Long? = 101
    var keepAnswer: Long? = 102
    var skipAnswer: Long? = 103
    var undoAnswer: Boolean = true

    override suspend fun identify(file: FileRef, onProgress: (PipelineProgress) -> Unit): PipelineResult {
        calls += "identify $file"
        progress.forEach(onProgress)
        runGate?.await()
        return synchronized(queue) { if (queue.size > 1) queue.removeFirst() else queue.first() }
    }

    override suspend fun current(file: FileRef): TrackDetails? {
        calls += "current $file"
        return details
    }

    override suspend fun protectedFields(): Set<ProtectedField> = protected

    override suspend fun cover(details: ReleaseDetails): CaaResult<CoverImage> {
        calls += "cover ${details.releaseId}"
        coverGate?.await()
        return coverResult
    }

    override suspend fun edition(match: RecordingMatch, result: PipelineResult): SuggestedEdition? {
        calls += "edition ${match.recordingId}"
        editionGate?.await()
        if (match.recordingId in editionFailures) throw IOException("MusicBrainz unavailable")
        return editions[match.recordingId]
    }

    override suspend fun apply(request: ApplyRequest, cover: CoverImage?): Long? {
        calls += "apply"
        writeGate?.await()
        applied += request to cover
        return applyAnswer
    }

    // A local read, as protectedFields is: not among the calls.
    override suspend fun canKeepOrSkip(target: IdentificationTarget.LibraryFile): Boolean = identityHeld

    override suspend fun keepAsIs(target: IdentificationTarget.LibraryFile): Long? {
        calls += "keep ${target.fileId}"
        writeGate?.await()
        return keepAnswer
    }

    override suspend fun skip(target: IdentificationTarget.LibraryFile): Long? {
        calls += "skip ${target.fileId}"
        writeGate?.await()
        return skipAnswer
    }

    override suspend fun undo(decisionId: Long): Boolean {
        calls += "undo $decisionId"
        writeGate?.await()
        return undoAnswer
    }

    override suspend fun enable() {
        calls += "enable"
        enableGate?.await()
    }

    override suspend fun notNow() {
        calls += "not now"
        notNowGate?.await()
    }

    override fun now(): Instant = clock

    /** The group list of each suggested release ID; a release in [editionListFailures] fails with an IOException. */
    val editionLists: MutableMap<String, EditionList> = mutableMapOf()
    val editionListFailures: MutableSet<String> = mutableSetOf()

    /** What choosing each edition gives, by release ID (null: does not contain the song); others fail. */
    val openedEditions: MutableMap<String, SuggestedEdition?> = mutableMapOf()

    /** When set, opening an edition waits for it. */
    @Volatile
    var openGate: CompletableDeferred<Unit>? = null

    /** What manual search answers: the candidates of a query, the lookup of a reference; [searchFails] fails both. */
    var searchAnswer: List<TextCandidate> = emptyList()
    var lookupAnswer: RefLookup = RefLookup.NotFound

    @Volatile
    var searchFails: Boolean = false

    /** The release index the cover picker gets, each image's thumbnail and full image by image ID, and the embedded ones. */
    var indexAnswer: CaaResult<CaaReleaseIndex> = CaaResult.NotFound
    val thumbnails: MutableMap<String, CoverImage> = mutableMapOf()
    val images: MutableMap<String, CoverImage> = mutableMapOf()
    var embedded: List<EmbeddedImage> = emptyList()

    /** The requests Apply with the user's values stored, with the identification's cover and the edit. */
    val appliedWithEdit: MutableList<Triple<ApplyRequest?, CoverImage?, ReviewEdit>> = CopyOnWriteArrayList()

    /** The edit decision's ID Apply with the user's values answers; null: the edit was not stored. */
    var editAnswer: Long? = 104

    override suspend fun editions(suggested: SuggestedEdition, result: PipelineResult): EditionList {
        calls += "editions ${suggested.release.id}"
        if (suggested.release.id in editionListFailures) throw IOException("MusicBrainz unavailable")
        return editionLists[suggested.release.id] ?: EditionList.NONE
    }

    override suspend fun openEdition(edition: Edition, suggested: SuggestedEdition, result: PipelineResult): SuggestedEdition? {
        calls += "open ${edition.release.id}"
        openGate?.await()
        if (!openedEditions.containsKey(edition.release.id)) throw IOException("MusicBrainz unavailable")
        return openedEditions[edition.release.id]
    }

    override suspend fun search(query: RecordingQuery): List<TextCandidate> {
        calls += "search ${query.query}"
        if (searchFails) throw IOException("MusicBrainz unavailable")
        return searchAnswer
    }

    override suspend fun lookup(ref: MusicBrainzRef): RefLookup {
        calls += "lookup $ref"
        if (searchFails) throw IOException("MusicBrainz unavailable")
        return lookupAnswer
    }

    /** When set, a release index waits for it. */
    @Volatile
    var indexGate: CompletableDeferred<Unit>? = null

    override suspend fun releaseImages(releaseId: String): CaaResult<CaaReleaseIndex> {
        calls += "index $releaseId"
        indexGate?.await()
        return indexAnswer
    }

    /** When set, an image of the index waits for it. */
    @Volatile
    var imageGate: CompletableDeferred<Unit>? = null

    override suspend fun image(image: CaaImage, px: Int?): CaaResult<CoverImage> {
        calls += "image ${image.id} $px"
        imageGate?.await()
        val found = if (px == THUMBNAIL_PX) thumbnails[image.id] else images[image.id]
        return found?.let { CaaResult.Found(it) } ?: CaaResult.NotFound
    }

    /** When set, the file's pictures cannot be read now. */
    @Volatile
    var embeddedFails: Boolean = false

    override suspend fun embeddedImages(uri: String): List<EmbeddedImage>? {
        calls += "embedded $uri"
        return if (embeddedFails) null else embedded
    }

    override suspend fun canEdit(target: IdentificationTarget): Boolean = identityHeld

    override suspend fun applyWithEdit(request: ApplyRequest?, cover: CoverImage?, edit: ReviewEdit): Applied? {
        calls += "apply with edit"
        writeGate?.await()
        appliedWithEdit += Triple(request, cover, edit)
        return if (request == null) {
            editAnswer?.let { Applied(it) }
        } else {
            applyAnswer?.let { Applied(it, editAnswer, editFailed = editAnswer == null) }
        }
    }

    companion object {
        /** The thumbnail size the cover picker shows. */
        const val THUMBNAIL_PX = 250

        val NOW: Instant = Instant.parse("2026-11-16T10:00:00Z")
    }
}
