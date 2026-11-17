package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.library.db.BatchEntity
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The review queue of the Fix tab (TI §8) over a store of fakes: the cards of the batches in review, built from their
 * files and the state of each (its last run and the decision of the batch), and the batches whose cards are all settled
 * marked done.
 */
class BatchReviewReadTest {

    private fun file(id: Long, albumKey: String? = "a", title: String? = "An Album") = BatchFile(id, null, albumKey, title)

    /** A store of one batch's rows, with the writes it was asked for. */
    private class FakeStore(
        var batches: List<BatchEntity> = listOf(reviewBatch()),
        val files: MutableMap<String, List<BatchFile>> = mutableMapOf(),
        val states: MutableMap<String, Map<Long, FileState>> = mutableMapOf(),
    ) : BatchReviewStore {
        val statuses = mutableListOf<Pair<String, String>>()

        override suspend fun reviewing(): List<BatchEntity> = batches

        override suspend fun files(batchUuid: String): List<BatchFile> = files[batchUuid].orEmpty()

        override suspend fun states(batchUuid: String): Map<Long, FileState> = states[batchUuid].orEmpty()

        override suspend fun setStatus(batchUuid: String, status: String) {
            statuses += batchUuid to status
            batches = batches.map { if (it.uuid == batchUuid) it.copy(status = status) else it }
        }
    }

    @Test
    fun `the cards of a batch come from its files and each file's state`() = runTest {
        val store = FakeStore(
            files = mutableMapOf(BATCH to listOf(file(1), file(2), file(3, albumKey = null, title = null))),
            states = mutableMapOf(
                BATCH to mapOf(
                    1L to FileState(keyOf(1), listOf(0.96), retryLater = false, decided = false),
                    2L to FileState(keyOf(2), emptyList(), retryLater = true, decided = true),
                    3L to FileState(null, emptyList(), retryLater = false, decided = false),
                ),
            ),
        )

        val views = BatchReviewRead(store).review()

        val cards = views.single().cards
        assertThat(views.single().batch.uuid).isEqualTo(BATCH)
        assertThat(cards.map { it.title }).containsExactly("An Album", null)
        assertThat(cards[0].tracks).isEqualTo(2)
        assertThat(cards[0].files.map { it.decided }).containsExactly(false, true)
        assertThat(cards[0].files.map { it.outcome }).containsExactly(FileOutcome.FOUND, FileOutcome.RETRY_LATER)
        // A file whose state names no run, as one the library holds no key for: nothing decides it, so the card is settled.
        assertThat(cards[1].files.single().outcome).isEqualTo(FileOutcome.NO_RUN)
        assertThat(cards[1].settled).isTrue()
    }

    @Test
    fun `a batch whose cards are all settled moves to done`() = runTest {
        val store = FakeStore(
            files = mutableMapOf(BATCH to listOf(file(1))),
            states = mutableMapOf(BATCH to mapOf(1L to FileState(keyOf(1), listOf(0.96), retryLater = false, decided = true))),
        )

        BatchReviewRead(store).review()

        assertThat(store.statuses).containsExactly(BATCH to BatchEntity.STATUS_DONE)
    }

    @Test
    fun `a batch with a track left to decide stays in review`() = runTest {
        val store = FakeStore(
            files = mutableMapOf(BATCH to listOf(file(1), file(2))),
            states = mutableMapOf(
                BATCH to mapOf(
                    1L to FileState(keyOf(1), listOf(0.96), retryLater = false, decided = true),
                    2L to FileState(keyOf(2), listOf(0.70), retryLater = false, decided = false),
                ),
            ),
        )

        val views = BatchReviewRead(store).review()

        assertThat(store.statuses).isEmpty()
        assertThat(views.single().batch.status).isEqualTo(BatchEntity.STATUS_REVIEW)
        assertThat(views.single().cards.single().pending).isEqualTo(1)
    }

    @Test
    fun `a batch without files is left as it is`() = runTest {
        val store = FakeStore()

        val views = BatchReviewRead(store).review()

        assertThat(views.single().cards).isEmpty()
        assertThat(store.statuses).isEmpty()
    }

    private companion object {
        const val BATCH = "6f9619ff-8b86-4d01-8d42-6e50c94637ac"

        fun reviewBatch() = BatchEntity(BATCH, createdAt = 1, source = BatchEntity.SOURCE_FOLDER, status = BatchEntity.STATUS_REVIEW)

        fun keyOf(fileId: Long): String = "%064x".format(fileId)
    }
}
