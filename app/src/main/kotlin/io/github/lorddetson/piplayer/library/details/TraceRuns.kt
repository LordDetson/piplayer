package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.library.db.IdentificationTraceEntity

/** The identification trace as track details show it (TI §10.1, docs/library.md §18.7). */
object TraceRuns {

    /**
     * The runs of [rows], one content key's trace in the order its rows were added, newest run first and at most
     * [MAX_RUNS]: each with its outcome row's code and retry time, and its stage rows in position order.
     */
    fun of(rows: List<IdentificationTraceEntity>): List<TraceRun> = TODO("step 22")

    /** The runs the trace keeps (TI §10.1). */
    const val MAX_RUNS = 3
}
