package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Bands
import io.github.lorddetson.piplayer.library.db.BatchEntity

/**
 * The review of a batch by albums (TI §8): one card per album group, which shows the album, its tracks, the group's
 * confidence and how its runs ended, and which opens the single-file review for the first track left to decide. A track
 * stands decided once a decision of the batch carries the batch's UUID; a track the batch could not read has nothing to
 * decide and does not hold the batch open. When every card of a batch is settled, the batch moves to `done`.
 */

/** How one file of a batch ended for its card (TI §8). */
enum class FileOutcome {

    /** The last run found the file. */
    FOUND,

    /** The last run found nothing in the medium band. */
    NO_MATCH,

    /** The last run's AcoustID lookup found nothing and asks to try again later (TI §3.2). */
    RETRY_LATER,

    /**
     * The card sees no run for the file: the library holds no content key for it and no decision of the batch names it, so
     * there is nothing to show of a run.
     */
    NO_RUN,
}

/** What the review reads of one file of a batch (TI §8): its key, its last run and the decision of the batch. */
data class FileState(
    /** The track's content key (TI §3.1), null when neither the library nor a decision of the batch gives one. */
    val contentKey: String?,
    /** The last run's scores, best first, empty when it found nothing or did not run. */
    val scores: List<Double>,
    /** Whether the last run's AcoustID lookup asks to try again later (TI §3.2). */
    val retryLater: Boolean,
    /** Whether a decision of the batch stands for the file (TI §7.1, §8). */
    val decided: Boolean,
)

/** One file of a batch as its card reads it (TI §8): what groups it, its last run and the decision of the batch. */
data class ReviewFile(
    val file: BatchFile,
    /** The file's content key (TI §3.1), null when the library holds none or the batch could not read it. */
    val contentKey: String?,
    /** The last run's best score, null when it found nothing or did not run. */
    val score: Double? = null,
    /** The last run's second best score, which the high band's gap needs (TI §4.1). */
    val runnerUp: Double? = null,
    /** Whether the last run's AcoustID lookup asked to try again later (TI §3.2). */
    val retryLater: Boolean = false,
    /** Whether a decision of the batch stands for the file (TI §7.1, §8). */
    val decided: Boolean = false,
) {

    /** The file's confidence band (TI §4.1); a file with no run has no match. */
    val band: Band get() = Bands.of(score, runnerUp)

    /** How the file's run ended (TI §8). */
    val outcome: FileOutcome
        get() = when {
            score != null -> FileOutcome.FOUND
            contentKey == null -> FileOutcome.NO_RUN
            retryLater -> FileOutcome.RETRY_LATER
            else -> FileOutcome.NO_MATCH
        }

    /** Whether nothing is left to decide (TI §8): a decision of the batch stands, or there is no run to decide. */
    val settled: Boolean get() = decided || outcome == FileOutcome.NO_RUN
}

/** One album group's card (TI §8). */
data class BatchCard(
    /** The group's index among the batch's groups, as [groupByAlbum] orders them. */
    val index: Int,
    /** The album's title, null for a group without an album entity. */
    val title: String?,
    /** The group's files, in the batch's order. */
    val files: List<ReviewFile>,
) {

    /** How many tracks the card covers. */
    val tracks: Int get() = files.size

    /** The group's confidence (TI §8): the least confident of its tracks, so that any doubt makes the card a review. */
    val band: Band get() = files.maxOfOrNull { it.band } ?: Band.NO_MATCH

    /** Tracks a decision of the batch stands for. */
    val decided: Int get() = files.count { it.decided }

    /** Tracks left to decide: those a run read and no decision covers yet. */
    val pending: Int get() = files.count { !it.settled }

    /** Whether the card is settled, so that the batch needs nothing more here (TI §8). */
    val settled: Boolean get() = pending == 0

    /** Tracks the app prepared on its own: decided in the high band (TI §8). */
    val automatic: Int get() = files.count { it.decided && it.band == Band.HIGH }

    /** How many tracks ended as [outcome]. */
    fun count(outcome: FileOutcome): Int = files.count { it.outcome == outcome }
}

/** A batch of the Fix tab's review queue with the cards of its album groups (TI §8). */
data class BatchReviewView(val batch: BatchEntity, val cards: List<BatchCard>)

/** What the batch review reads and writes (TI §8), the app over its two databases and a test over fakes. */
interface BatchReviewStore {

    /** The batches in `review`, oldest first. */
    suspend fun reviewing(): List<BatchEntity>

    /** The files of [batchUuid] in their order, with the album data that groups them (TI §8). */
    suspend fun files(batchUuid: String): List<BatchFile>

    /** The last run and the decision of the batch of each of its files, by file ID (TI §8). */
    suspend fun states(batchUuid: String): Map<Long, FileState>

    suspend fun setStatus(batchUuid: String, status: String)
}

/** The cards of the batches in review, and the batches whose cards are all settled (TI §8). */
class BatchReviewRead(private val store: BatchReviewStore) {

    /**
     * The batches in `review` with their cards, oldest first. A batch whose cards are all settled moves to `done`
     * before this returns; a batch without cards is left as it is, since nothing of it is there to review.
     */
    suspend fun review(): List<BatchReviewView> {
        val views = store.reviewing().map { batch ->
            val states = store.states(batch.uuid)
            BatchReviewView(
                batch,
                BatchReview.cards(
                    store.files(batch.uuid).map { file ->
                        val state = states[file.fileId]
                        ReviewFile(
                            file,
                            state?.contentKey,
                            state?.scores?.firstOrNull(),
                            state?.scores?.getOrNull(1),
                            state?.retryLater ?: false,
                            decided = state?.decided ?: false,
                        )
                    },
                ),
            )
        }
        for (view in views) {
            if (BatchReview.finished(view.cards)) store.setStatus(view.batch.uuid, BatchEntity.STATUS_DONE)
        }
        return views
    }
}

object BatchReview {

    /** The cards of [files], one per album group, in the order the groups come (TI §8). */
    fun cards(files: List<ReviewFile>, grouping: (List<BatchFile>) -> List<BatchGroup> = ::groupByAlbum): List<BatchCard> {
        val byFile = files.associateBy { it.file.fileId }
        return grouping(files.map { it.file }).mapIndexed { index, group ->
            BatchCard(
                index,
                (group as? BatchGroup.Album)?.title,
                group.files.mapNotNull { byFile[it.fileId] },
            )
        }
    }

    /** The track the card's review opens: the first one left to decide, null for a settled card (TI §8). */
    fun next(card: BatchCard): ReviewFile? = card.files.firstOrNull { !it.settled }

    /** Whether every card of a batch is settled, so that the batch is done (TI §8); a batch without cards is not. */
    fun finished(cards: List<BatchCard>): Boolean = cards.isNotEmpty() && cards.all { it.settled }
}
