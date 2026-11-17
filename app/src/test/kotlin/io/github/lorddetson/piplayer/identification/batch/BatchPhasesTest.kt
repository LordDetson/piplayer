package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.pipeline.CachedFingerprint
import io.github.lorddetson.piplayer.identification.pipeline.FakeFingerprints
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.LocalFile
import io.github.lorddetson.piplayer.identification.pipeline.LocalStage
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.pipeline.StageDuration
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two phases of one album group (TI §8), without the WorkManager machinery: how the group is resolved from the
 * batch's state, that phase (a) caches the fingerprints it lacks and phase (b) runs the pipeline for every file of
 * the group, and what each finished phase decides next, the review of the last group included. The batch row, its
 * files and stage 0 are fakes; the fingerprint cache is the pipeline's test fake.
 */
class BatchPhasesTest {

    private val batch = BatchEntity(UUID, createdAt = 1, source = BatchEntity.SOURCE_FOLDER, status = BatchEntity.STATUS_PROCESSING)

    private fun file(fileId: Long, releaseMbid: String? = null, albumKey: String? = null, title: String? = null) =
        BatchFile(fileId, releaseMbid, albumKey, title)

    /** A read of [fileId] with its own content key. */
    private fun readOf(fileId: Long): LocalFile = LocalFile(
        target = IdentificationTarget.LibraryFile(fileId, keyOf(fileId)),
        fileName = "track.mp3",
        tags = null,
        duration = StageDuration.Exact(8_600_000),
        format = "MPEG",
        folder = null,
        uri = "file:///music/track.mp3",
    )

    /** The batch's state as a fake: [batch] is the row, [files] the files in their order; writes are recorded. */
    private class FakeBatchState(var batch: BatchEntity?, val files: MutableList<BatchFile> = mutableListOf()) : BatchState {
        val statuses = mutableListOf<String>()

        override suspend fun batch(uuid: String): BatchEntity? = if (uuid == UUID) batch else null

        override suspend fun files(uuid: String): List<BatchFile> = if (uuid == UUID) files else emptyList()

        override suspend fun setStatus(uuid: String, status: String) {
            statuses += status
            batch = batch?.copy(status = status)
        }
    }

    /** Stage 0 as a fake that reads each file of the batch by its ID: what [reads] gives it, null for unreadable. */
    private class PerFileLocal(private val reads: Map<Long, LocalFile?>) : LocalStage {
        val readRefs = mutableListOf<FileRef>()

        override suspend fun read(file: FileRef): LocalFile? {
            readRefs += file
            return reads[(file as FileRef.Library).fileId]
        }

        override suspend fun walk(file: LocalFile, plan: DurationPlan): Long? = null
    }

    /** The preparation of a group's high band as a fake: records what it applies, and what it leaves to the cards. */
    private class RecordingPreparation : BatchPreparation {
        val applied = mutableListOf<String>()
        val requests = mutableListOf<ApplyRequest>()

        override suspend fun fields(): Set<ProtectedField> = emptySet()

        override fun now(): Instant = Instant.parse("2026-11-17T09:00:00Z")

        override suspend fun apply(request: ApplyRequest, batchUuid: String): Long? {
            requests += request
            applied += batchUuid
            return requests.size.toLong()
        }
    }

    // Phase (a): local reads and fingerprints.

    @Test
    fun `phase a computes the fingerprints the group lacks and keeps the cached ones`() = runTest {
        val state = FakeBatchState(batch, mutableListOf(file(1, albumKey = "a", title = "An Album"), file(2, albumKey = "a", title = "An Album")))
        val local = PerFileLocal(mapOf(1L to readOf(1), 2L to readOf(2)))
        val fingerprints = FakeFingerprints()
        fingerprints.stored[keyOf(1)] = CachedFingerprint(FakeFingerprints.RAW)

        val step = scanGroup(UUID, groupIndex = 0, state = state, local = local, fingerprints = fingerprints)

        assertThat(step).isEqualTo(BatchStep.LookupSame)
        assertThat(local.readRefs).containsExactly(FileRef.Library(1), FileRef.Library(2))
        assertThat(fingerprints.computes).isEqualTo(1)
        assertThat(fingerprints.stored.keys).containsExactlyInAnyOrder(keyOf(1), keyOf(2))
    }

    @Test
    fun `phase a skips a file stage 0 cannot read`() = runTest {
        val state = FakeBatchState(batch, mutableListOf(file(1, albumKey = "a"), file(2, albumKey = "a")))
        val local = PerFileLocal(mapOf(1L to readOf(1), 2L to null))
        val fingerprints = FakeFingerprints()

        val step = scanGroup(UUID, groupIndex = 0, state = state, local = local, fingerprints = fingerprints)

        assertThat(step).isEqualTo(BatchStep.LookupSame)
        assertThat(local.readRefs).containsExactly(FileRef.Library(1), FileRef.Library(2))
        assertThat(fingerprints.computes).isEqualTo(1)
    }

    @Test
    fun `phase a skips a file that cannot be fingerprinted and still finishes the group`() = runTest {
        val state = FakeBatchState(batch, mutableListOf(file(1, albumKey = "a"), file(2, albumKey = "a"), file(3, albumKey = "a")))
        val local = PerFileLocal(mapOf(1L to readOf(1), 2L to readOf(2), 3L to readOf(3)))
        val fingerprints = FakeFingerprints()
        // The first file decodes and caches; from the second on the decoder fails, as for an unsupported format.
        var attempts = 0
        fingerprints.whileComputing = { if (++attempts == 2) fingerprints.failure = IOException("No decoder") }
        val progress = mutableListOf<Pair<Int, Int>>()

        val step = scanGroup(UUID, groupIndex = 0, state = state, local = local, fingerprints = fingerprints) { done, total ->
            progress += done to total
        }

        assertThat(step).isEqualTo(BatchStep.LookupSame)
        assertThat(fingerprints.stored.keys).containsExactly(keyOf(1))
        assertThat(progress).containsExactly(1 to 3, 2 to 3, 3 to 3)
    }

    @Test
    fun `phase a reports each finished file`() = runTest {
        val state = FakeBatchState(batch, mutableListOf(file(1, albumKey = "a"), file(2, albumKey = "a")))
        val local = PerFileLocal(mapOf(1L to readOf(1), 2L to readOf(2)))
        val progress = mutableListOf<Pair<Int, Int>>()

        scanGroup(UUID, groupIndex = 0, state = state, local = local, fingerprints = FakeFingerprints()) { done, total ->
            progress += done to total
        }

        assertThat(progress).containsExactly(1 to 2, 2 to 2)
    }

    @Test
    fun `a queued batch moves to processing and runs`() = runTest {
        val state = FakeBatchState(batch.copy(status = BatchEntity.STATUS_QUEUED), mutableListOf(file(1)))
        val local = PerFileLocal(mapOf(1L to readOf(1)))

        val step = scanGroup(UUID, groupIndex = 0, state = state, local = local, fingerprints = FakeFingerprints())

        assertThat(step).isEqualTo(BatchStep.LookupSame)
        assertThat(state.statuses).containsExactly(BatchEntity.STATUS_PROCESSING)
        assertThat(state.batch?.status).isEqualTo(BatchEntity.STATUS_PROCESSING)
    }

    // Phase (b): one pipeline run per file, then the next group or review.

    @Test
    fun `phase b runs the pipeline for every file of the group with the batch's UUID`() = runTest {
        val state = FakeBatchState(
            batch,
            mutableListOf(file(1, albumKey = "a", title = "An Album"), file(2, albumKey = "a", title = "An Album"), file(3, albumKey = "b", title = "Another")),
        )
        val runs = mutableListOf<Pair<FileRef, String>>()

        val step = lookupGroup(
            UUID,
            groupIndex = 0,
            state = state,
            run = { ref, batchUuid ->
                runs += ref to batchUuid
                PipelineResult(IdentificationOutcome.Unreadable)
            },
        )

        assertThat(step).isEqualTo(BatchStep.ScanNext(1))
        assertThat(runs).containsExactly(FileRef.Library(1) to UUID, FileRef.Library(2) to UUID)
    }

    @Test
    fun `phase b prepares the group's high band with the batch's UUID and leaves the rest to the cards`() = runTest {
        val state = FakeBatchState(batch, mutableListOf(file(1, albumKey = "a", title = "An Album"), file(2, albumKey = "a", title = "An Album")))
        val high = ReviewFixtures.result(ReviewFixtures.found(ReviewFixtures.match()))
        val medium = ReviewFixtures.result(ReviewFixtures.found(ReviewFixtures.match(score = 0.70)))
        val preparation = RecordingPreparation()

        val step = lookupGroup(
            UUID,
            groupIndex = 0,
            state = state,
            run = { ref, _ -> if ((ref as FileRef.Library).fileId == 1L) high else medium },
            prepare = preparation,
        )

        assertThat(step).isEqualTo(BatchStep.Review)
        assertThat(preparation.applied).containsExactly(UUID)
        assertThat(preparation.requests.map { it.target }).containsExactly(ReviewFixtures.libraryTarget)
    }

    @Test
    fun `after the last group phase b moves the batch to review and enqueues nothing`() = runTest {
        val state = FakeBatchState(batch, mutableListOf(file(1), file(2)))

        val step = lookupGroup(
            UUID,
            groupIndex = 1,
            state = state,
            run = { _, _ -> PipelineResult(IdentificationOutcome.Unreadable) },
        )

        assertThat(step).isEqualTo(BatchStep.Review)
        assertThat(state.statuses).containsExactly(BatchEntity.STATUS_REVIEW)
        assertThat(state.batch?.status).isEqualTo(BatchEntity.STATUS_REVIEW)
    }

    // The shared resolution: gone, cancelled or empty batches and stale groups.

    @Test
    fun `a batch whose row is gone ends both phases with nothing`() = runTest {
        val state = FakeBatchState(null, mutableListOf(file(1)))
        val local = PerFileLocal(mapOf(1L to readOf(1)))

        assertThat(scanGroup(UUID, 0, state, local, FakeFingerprints())).isEqualTo(BatchStep.Idle)
        assertThat(lookupGroup(UUID, 0, state, run = { _, _ -> PipelineResult(IdentificationOutcome.Unreadable) }))
            .isEqualTo(BatchStep.Idle)
        assertThat(local.readRefs).isEmpty()
        assertThat(state.statuses).isEmpty()
    }

    @Test
    fun `a cancelled or reviewed batch ends both phases with nothing`() = runTest {
        val local = PerFileLocal(mapOf(1L to readOf(1)))
        for (status in listOf(BatchEntity.STATUS_CANCELLED, BatchEntity.STATUS_REVIEW, BatchEntity.STATUS_DONE)) {
            val state = FakeBatchState(batch.copy(status = status), mutableListOf(file(1)))
            assertThat(scanGroup(UUID, 0, state, local, FakeFingerprints())).isEqualTo(BatchStep.Idle)
            assertThat(lookupGroup(UUID, 0, state, run = { _, _ -> PipelineResult(IdentificationOutcome.Unreadable) }))
                .isEqualTo(BatchStep.Idle)
        }
        assertThat(local.readRefs).isEmpty()
    }

    @Test
    fun `a batch without files moves to review without any work`() = runTest {
        val state = FakeBatchState(batch, mutableListOf())

        assertThat(scanGroup(UUID, 0, state, PerFileLocal(emptyMap()), FakeFingerprints())).isEqualTo(BatchStep.Review)
        assertThat(state.statuses).containsExactly(BatchEntity.STATUS_REVIEW)
    }

    @Test
    fun `a group beyond the batch's groups ends the job with nothing`() = runTest {
        val state = FakeBatchState(batch, mutableListOf(file(1), file(2, albumKey = "a")))

        assertThat(scanGroup(UUID, groupIndex = 2, state, PerFileLocal(emptyMap()), FakeFingerprints()))
            .isEqualTo(BatchStep.Idle)
        assertThat(lookupGroup(UUID, groupIndex = 2, state, run = { _, _ -> PipelineResult(IdentificationOutcome.Unreadable) }))
            .isEqualTo(BatchStep.Idle)
    }

    @Test
    fun `a pipeline failure in phase b propagates, for WorkManager to retry`() = runTest {
        val state = FakeBatchState(batch, mutableListOf(file(1)))

        val failure = runCatching {
            lookupGroup(UUID, 0, state, run = { _, _ -> throw IOException("The store is closed") })
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java).hasMessage("The store is closed")
    }

    companion object {
        const val UUID = "6f9619ff-8b86-4d01-8d42-6e50c94637ac"

        /** A content key for [fileId] (64 lower-case hex digits, TI §3.1). */
        fun keyOf(fileId: Long): String = "%064x".format(fileId)
    }
}
