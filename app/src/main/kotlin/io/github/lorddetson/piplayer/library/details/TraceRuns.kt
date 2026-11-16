package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.identification.pipeline.RunTrace
import io.github.lorddetson.piplayer.library.db.IdentificationTraceEntity

/** The identification trace as track details show it (TI §10.1, docs/library.md §18.7). */
object TraceRuns {

    /**
     * The runs of [rows], one content key's trace in the order its rows were added, newest run first and at most
     * [MAX_RUNS]: each with its outcome row's code and retry time, and its stage rows in position order.
     */
    fun of(rows: List<IdentificationTraceEntity>): List<TraceRun> {
        val runs = LinkedHashMap<String, MutableList<IdentificationTraceEntity>>()
        for (row in rows) runs.getOrPut(row.runId) { ArrayList() } += row
        return runs.values.reversed().take(MAX_RUNS).map { own ->
            val ordered = own.sortedBy { it.position }
            val outcome = ordered.lastOrNull { it.stage == RunTrace.OUTCOME }
            val notes = tokens(outcome?.note)
            TraceRun(
                startedAt = outcome?.startedAt ?: ordered.first().startedAt,
                outcome = notes.firstOrNull { !it.startsWith(RETRY_AT) },
                retryAt = notes.firstOrNull { it.startsWith(RETRY_AT) }?.removePrefix(RETRY_AT)?.toLongOrNull(),
                steps = ordered.filter { it.stage != RunTrace.OUTCOME }.map(::step),
            )
        }
    }

    private fun step(row: IdentificationTraceEntity): TraceStep {
        val notes = tokens(row.note)
        fun note(name: String) = notes.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
        return TraceStep(row.stage, row.provider, row.httpStatus, row.resultCount, row.bestScore, row.elapsedMs, note("skipped"), note("failed"))
    }

    /** The codes of a note, as `RunTrace` writes them: separated by spaces. */
    private fun tokens(note: String?): List<String> = note?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()

    private const val RETRY_AT = "retry_at="

    /** The runs the trace keeps (TI §10.1). */
    const val MAX_RUNS = 3
}
