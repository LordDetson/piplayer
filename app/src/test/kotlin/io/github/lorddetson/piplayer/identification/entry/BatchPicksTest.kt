package io.github.lorddetson.piplayer.identification.entry

import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.lists.MissingInfoDecision
import io.github.lorddetson.piplayer.library.lists.MissingReasons
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

/** The files a batch takes from each entry point (TI §1 "Entry points", §8). */
class BatchPicksTest {

    @Test
    fun `a selection and a folder give their files in their order, each once`() {
        assertThat(BatchPicks.files(listOf(7, 3, 7, 9))).containsExactly(7L, 3L, 9L)
        assertThat(BatchPicks.files(emptyList())).isEmpty()
    }

    @Test
    fun `Songs missing information keeps its order and leaves out Keep as-is and Skip`() {
        val tracks = listOf(
            BatchTrack(1),
            BatchTrack(2, MissingInfoDecision.KEEP_AS_IS),
            BatchTrack(3, MissingInfoDecision.SKIPPED),
            BatchTrack(4),
            BatchTrack(4),
        )

        assertThat(BatchPicks.missingInfo(tracks)).containsExactly(1L, 4L)
        assertThat(BatchPicks.missingInfo(emptyList())).isEmpty()
        // Every track of the list decided: nothing to do, and no batch.
        assertThat(
            BatchPicks.missingInfo(
                listOf(BatchTrack(1, MissingInfoDecision.KEEP_AS_IS), BatchTrack(2, MissingInfoDecision.SKIPPED)),
            ),
        ).isEmpty()
    }

    @Test
    fun `each entry point records its own source`() {
        assertThat(BatchRequest.Selection(listOf(1)).source).isEqualTo(BatchEntity.SOURCE_SELECTION)
        assertThat(BatchRequest.Folder("external_primary", "Music/Ann/").source).isEqualTo(BatchEntity.SOURCE_FOLDER)
        assertThat(BatchRequest.MissingInfo(MissingReasons.DEFAULT).source).isEqualTo(BatchEntity.SOURCE_MISSING_INFO)
    }

    @Test
    fun `a batch is queued with the source that started it`() {
        val batch = queuedBatch(BatchEntity.SOURCE_FOLDER, listOf(4L, 5L), uuid = "b1", createdAt = 42)

        assertThat(batch.uuid).isEqualTo("b1")
        assertThat(batch.createdAt).isEqualTo(42)
        assertThat(batch.source).isEqualTo(BatchEntity.SOURCE_FOLDER)
        assertThat(batch.status).isEqualTo(BatchEntity.STATUS_QUEUED)
        assertThat(batch.writeToFile).isNull()
    }

    @Test
    fun `a batch needs files and a source of the entry points`() {
        assertThatIllegalArgumentException()
            .isThrownBy { queuedBatch(BatchEntity.SOURCE_SELECTION, emptyList(), uuid = "b1", createdAt = 0) }
        assertThatIllegalArgumentException()
            .isThrownBy { queuedBatch("watched", listOf(1L), uuid = "b1", createdAt = 0) }
    }
}
