package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.library.db.BatchEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Which batches a new process enqueues again, and from which album group (TI §8, PIM-16). */
class BatchResumeTest {

    private fun batch(uuid: String, status: String, group: Int = 0) =
        BatchEntity(uuid = uuid, createdAt = 1L, source = BatchEntity.SOURCE_FOLDER, status = status, groupIndex = group)

    @Test
    fun `a queued batch comes back at its first group`() {
        val batches = listOf(batch("a", BatchEntity.STATUS_QUEUED))

        assertThat(BatchResume.resumable(batches)).containsExactly("a" to 0)
    }

    @Test
    fun `a processing batch comes back at the group it was on`() {
        val batches = listOf(batch("b", BatchEntity.STATUS_PROCESSING, group = 7))

        assertThat(BatchResume.resumable(batches)).containsExactly("b" to 7)
    }

    @Test
    fun `finished, cancelled and waiting-for-review batches are left alone`() {
        val batches = listOf(
            batch("done", BatchEntity.STATUS_DONE),
            batch("cancelled", BatchEntity.STATUS_CANCELLED),
            batch("review", BatchEntity.STATUS_REVIEW),
        )

        assertThat(BatchResume.resumable(batches)).isEmpty()
    }

    @Test
    fun `a group index that went below zero starts the batch again`() {
        // A row written by an older build has no group, and one set back must not enqueue a group that does not exist.
        val batches = listOf(batch("c", BatchEntity.STATUS_PROCESSING, group = -3))

        assertThat(BatchResume.resumable(batches)).containsExactly("c" to 0)
    }

    @Test
    fun `the batches keep their order, so the oldest is enqueued first`() {
        val batches = listOf(
            batch("first", BatchEntity.STATUS_PROCESSING, group = 1),
            batch("second", BatchEntity.STATUS_QUEUED, group = 0),
        )

        assertThat(BatchResume.resumable(batches).map { it.first }).containsExactly("first", "second")
    }
}