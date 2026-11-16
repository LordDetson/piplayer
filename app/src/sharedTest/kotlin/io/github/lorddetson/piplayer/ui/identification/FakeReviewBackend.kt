package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
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
        if (match.recordingId in editionFailures) throw IOException("MusicBrainz unavailable")
        return editions[match.recordingId]
    }

    override suspend fun apply(request: ApplyRequest, cover: CoverImage?): Long? {
        calls += "apply"
        applied += request to cover
        return applyAnswer
    }

    override suspend fun keepAsIs(target: IdentificationTarget.LibraryFile): Long? {
        calls += "keep ${target.fileId}"
        return keepAnswer
    }

    override suspend fun skip(target: IdentificationTarget.LibraryFile): Long? {
        calls += "skip ${target.fileId}"
        return skipAnswer
    }

    override suspend fun undo(decisionId: Long): Boolean {
        calls += "undo $decisionId"
        return undoAnswer
    }

    override suspend fun enable() {
        calls += "enable"
    }

    override suspend fun notNow() {
        calls += "not now"
    }

    override fun now(): Instant = NOW

    companion object {
        val NOW: Instant = Instant.parse("2026-11-16T10:00:00Z")
    }
}
