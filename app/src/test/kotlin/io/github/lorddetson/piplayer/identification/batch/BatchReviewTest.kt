package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.identification.score.Band
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The cards of a batch's review (TI §8): one card per album group, its tracks, the group's confidence and the counts of
 * how each run ended, and the batch finishing once every card is settled.
 */
class BatchReviewTest {

    private fun file(id: Long, releaseMbid: String? = null, albumKey: String? = null, title: String? = null) =
        BatchFile(id, releaseMbid, albumKey, title)

    /** A file of the album [albumKey] whose last run found it with [score] and the runner-up [runnerUp]. */
    private fun found(
        id: Long,
        albumKey: String? = "a",
        title: String? = "An Album",
        score: Double = 0.96,
        runnerUp: Double? = null,
        decided: Boolean = false,
    ) = ReviewFile(file(id, albumKey = albumKey, title = title), keyOf(id), score, runnerUp, decided = decided)

    /** A file of the album [albumKey] whose last run found nothing. */
    private fun noMatch(id: Long, albumKey: String? = "a", title: String? = "An Album", retryLater: Boolean = false, decided: Boolean = false) =
        ReviewFile(file(id, albumKey = albumKey, title = title), keyOf(id), score = null, retryLater = retryLater, decided = decided)

    /** A file the batch's cards see no run for: no content key, so nothing of a run is shown. */
    private fun noRun(id: Long, albumKey: String? = "a", title: String? = "An Album") =
        ReviewFile(file(id, albumKey = albumKey, title = title), contentKey = null)

    @Test
    fun `one card per album group, with the album's title and its tracks`() {
        val cards = BatchReview.cards(
            listOf(
                found(1, albumKey = "a", title = "An Album"),
                found(2, albumKey = "a", title = "An Album"),
                found(3, albumKey = "b", title = "Another"),
                found(4, albumKey = null, title = null),
            ),
        )

        assertThat(cards.map { it.index }).containsExactly(0, 1, 2)
        assertThat(cards.map { it.title }).containsExactly("An Album", "Another", null)
        assertThat(cards.map { it.tracks }).containsExactly(2, 1, 1)
        assertThat(cards[0].files.map { it.file.fileId }).containsExactly(1L, 2L)
    }

    @Test
    fun `a card shows its least confident track`() {
        val highOnly = BatchReview.cards(listOf(found(1), found(2))).single()
        val withMedium = BatchReview.cards(listOf(found(1), found(2, score = 0.70))).single()
        val withNoMatch = BatchReview.cards(listOf(found(1), noMatch(2))).single()

        assertThat(highOnly.band).isEqualTo(Band.HIGH)
        assertThat(withMedium.band).isEqualTo(Band.MEDIUM)
        assertThat(withNoMatch.band).isEqualTo(Band.NO_MATCH)
    }

    @Test
    fun `a card counts its decided tracks and those the app prepared on its own`() {
        val card = BatchReview.cards(
            listOf(found(1, decided = true), found(2, decided = false), found(3, score = 0.70, decided = true)),
        ).single()

        assertThat(card.decided).isEqualTo(2)
        assertThat(card.pending).isEqualTo(1)
        assertThat(card.automatic).isEqualTo(1)
        assertThat(card.settled).isFalse()
    }

    @Test
    fun `a card whose tracks all stand decided is settled`() {
        val card = BatchReview.cards(listOf(found(1, decided = true), noMatch(2, decided = true, retryLater = true))).single()

        assertThat(card.settled).isTrue()
        assertThat(card.pending).isZero()
    }

    @Test
    fun `a file with no run leaves nothing to decide`() {
        val card = BatchReview.cards(listOf(found(1, decided = true), noRun(2))).single()

        assertThat(card.files[1].settled).isTrue()
        assertThat(card.settled).isTrue()
        assertThat(card.tracks).isEqualTo(2)
        assertThat(card.count(FileOutcome.NO_RUN)).isEqualTo(1)
    }

    @Test
    fun `the card counts how each run ended`() {
        val card = BatchReview.cards(
            listOf(found(1), noMatch(2), noMatch(3, retryLater = true), noRun(4)),
        ).single()

        assertThat(card.count(FileOutcome.FOUND)).isEqualTo(1)
        assertThat(card.count(FileOutcome.NO_MATCH)).isEqualTo(1)
        assertThat(card.count(FileOutcome.RETRY_LATER)).isEqualTo(1)
        assertThat(card.count(FileOutcome.NO_RUN)).isEqualTo(1)
    }

    @Test
    fun `a card opens the review for its first track left to decide`() {
        val card = BatchReview.cards(listOf(found(1, decided = true), noRun(2), noMatch(3))).single()

        assertThat(BatchReview.next(card)?.file?.fileId).isEqualTo(3L)
        assertThat(BatchReview.next(BatchReview.cards(listOf(found(1, decided = true))).single())).isNull()
    }

    @Test
    fun `a batch is finished once every card is settled`() {
        val settled = BatchReview.cards(listOf(found(1, decided = true), noMatch(2, decided = true)))
        val pending = BatchReview.cards(listOf(found(1, decided = true), noMatch(2)))

        assertThat(BatchReview.finished(settled)).isTrue()
        assertThat(BatchReview.finished(pending)).isFalse()
    }

    @Test
    fun `a batch without cards is not finished on its own`() {
        assertThat(BatchReview.finished(BatchReview.cards(emptyList()))).isFalse()
    }

    @Test
    fun `cards keep the order of the batch's groups`() {
        val cards = BatchReview.cards(
            listOf(found(1, albumKey = "b", title = "B"), found(2, albumKey = null, title = null), found(3, albumKey = "a", title = "A")),
        )

        // Album groups keep the order of their first file, and a file without an album follows them (TI §8).
        assertThat(cards.map { it.title }).containsExactly("B", "A", null)
    }

    private fun keyOf(fileId: Long): String = "%064x".format(fileId)
}
