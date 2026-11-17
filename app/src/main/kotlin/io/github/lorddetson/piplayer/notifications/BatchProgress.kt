package io.github.lorddetson.piplayer.notifications

import kotlin.math.ceil

/**
 * The progress of a batch as its notification says it (TI §8, PIM-21), without Android: how many of the batch's files
 * are done, and how long it has left.
 *
 * A batch runs one album group at a time, and each group is two WorkManager jobs, phase (a) then phase (b), which each
 * report the files of their own group (`BatchData.progress`). A finished job therefore means half a group done, so
 * [batchProgress] counts the groups whose two jobs have finished and adds what the running job has done in its group.
 */

/** What a running job of a batch reports (TI §8): the files it has finished of its album group and the group's size. */
data class JobProgress(val done: Int, val total: Int)

/** How far a batch is (TI §8, PIM-21): its files done of its files in all. */
data class BatchProgress(val done: Int, val total: Int) {

    /** What a progress bar shows, from 0 to 1; null while the batch's files are unknown. */
    val fraction: Float? get() = if (total > 0) done.toFloat() / total else null
}

/**
 * The progress of a batch (TI §8): [groupSizes] are the sizes of its album groups in their order, [finishedJobs] how
 * many of the batch's jobs have finished, and [running] what a running job reports, if one runs. The groups whose two
 * jobs have finished count whole, and the running job's files count up to the size of the group it works on, so the
 * number never passes the batch's files.
 */
fun batchProgress(groupSizes: List<Int>, finishedJobs: Int, running: JobProgress?): BatchProgress {
    val total = groupSizes.sum()
    val groupsDone = (finishedJobs / 2).coerceIn(0, groupSizes.size)
    val before = groupSizes.take(groupsDone).sum()
    val inGroup = (running?.done ?: 0).coerceIn(0, groupSizes.getOrNull(groupsDone) ?: 0)
    return BatchProgress((before + inGroup).coerceAtMost(total), total)
}

/** The time a batch has left, rounded the way its notification says it (TI §8, PIM-21). */
sealed interface Eta {

    /** Not measured: a batch that has just started, one that has not moved lately, or a finished one. */
    data object Unknown : Eta

    /** Under a minute: the notification says so rather than "0 min". */
    data object LessThanAMinute : Eta

    /** Whole minutes, rounded up. */
    data class Minutes(val minutes: Int) : Eta

    /** An hour or more: an estimate that long is not worth a number. */
    data object OverAnHour : Eta
}

/** The time left [millis] in the words of the notification (TI §8): whole minutes, rounded up, an hour at most. */
fun etaOf(millis: Long?): Eta = when {
    millis == null || millis <= 0 -> Eta.Unknown
    millis < MINUTE_MILLIS -> Eta.LessThanAMinute
    else -> {
        val minutes = (millis + MINUTE_MILLIS - 1) / MINUTE_MILLIS
        if (minutes > 60) Eta.OverAnHour else Eta.Minutes(minutes.toInt())
    }
}

/**
 * The speed of a batch, for its ETA (TI §8, PIM-21): samples of the files done at the times they were seen, and the
 * rate over the last [windowMillis] of them. A batch that waits for the network or for the battery makes no samples,
 * and its ETA is unknown rather than hours off, so the notification keeps estimating while the batch waits.
 */
class EtaMeter(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val staleMillis: Long = DEFAULT_STALE_MILLIS,
) {

    private val times = ArrayDeque<Long>()
    private val files = ArrayDeque<Int>()

    /** Takes a sample at [timeMillis]; one that shows fewer files than the last starts the meter again. */
    fun record(timeMillis: Long, filesDone: Int) {
        if (files.isNotEmpty() && filesDone < files.last()) restart()
        times.addLast(timeMillis)
        files.addLast(filesDone)
        // The last sample before the window stays, so that the rate spans the window and not just its last step.
        while (times.size > 2 && timeMillis - times[1] >= windowMillis) {
            times.removeFirst()
            files.removeFirst()
        }
    }

    /**
     * The time left for [filesDone] of [filesTotal] seen at [nowMillis]; null while the rate is not known: fewer than
     * two samples, a span too short to mean anything, no file done in it, nothing left, or no sample for
     * [staleMillis] because the batch waits.
     */
    fun etaMillis(nowMillis: Long, filesDone: Int, filesTotal: Int): Long? {
        if (filesDone >= filesTotal || times.isEmpty()) return null
        if (nowMillis - times.last() > staleMillis) return null
        val span = times.last() - times.first()
        val risen = files.last() - files.first()
        if (span < MIN_SPAN_MILLIS || risen <= 0) return null
        val left = filesTotal - filesDone
        return ceil(left * span.toDouble() / risen).toLong()
    }

    /** Forgets every sample, as after the batch was cancelled and started again. */
    fun restart() {
        times.clear()
        files.clear()
    }

    private companion object {

        /** The span the rate looks back over: long enough to smooth one album group, short enough to follow it. */
        const val DEFAULT_WINDOW_MILLIS = 60_000L

        /** No sample for this long means the batch is not moving, and its ETA is unknown. */
        const val DEFAULT_STALE_MILLIS = 30_000L

        /** Shorter than this and a sample pair is noise rather than a rate. */
        const val MIN_SPAN_MILLIS = 3_000L
    }
}

private const val MINUTE_MILLIS = 60_000L
