package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.library.db.CandidateEntity
import io.github.lorddetson.piplayer.library.db.IdentificationTraceEntity
import io.github.lorddetson.piplayer.library.db.LookupJobEntity

/**
 * The working rows as plain tables in memory, like the library database's: the trace keeps its rows in the order they
 * were added, as SQLite's row IDs do, and the store applies whatever a run asks for, so a test checks what the pipeline
 * itself writes. [readFailure] and [writeFailure] make reads or writes fail.
 */
class MemoryIdentificationStore : IdentificationStore {

    val jobs: MutableMap<String, LookupJobEntity> = mutableMapOf()
    val candidates: MutableList<CandidateEntity> = mutableListOf()
    val trace: MutableList<IdentificationTraceEntity> = mutableListOf()

    /** The transactions run so far. */
    var writes = 0
        private set

    @Volatile
    var readFailure: RuntimeException? = null

    @Volatile
    var writeFailure: RuntimeException? = null

    fun candidatesOf(contentKey: String) = synchronized(this) {
        candidates.filter { it.contentKey == contentKey }.sortedBy { it.position }
    }

    fun traceOf(contentKey: String) = synchronized(this) { trace.filter { it.contentKey == contentKey } }

    /** The runs traced for [contentKey], oldest first. */
    fun runsOf(contentKey: String) = traceOf(contentKey).map { it.runId }.distinct()

    override suspend fun job(contentKey: String): LookupJobEntity? {
        readFailure?.let { throw it }
        return synchronized(this) { jobs[contentKey] }
    }

    override suspend fun write(block: suspend IdentificationTables.() -> Unit) {
        writeFailure?.let { throw it }
        // One transaction: applied to copies, kept only when the block ends.
        val tables = Tables(jobs.toMutableMap(), candidates.toMutableList(), trace.toMutableList())
        tables.block()
        synchronized(this) {
            jobs.clear()
            jobs.putAll(tables.jobs)
            candidates.clear()
            candidates.addAll(tables.candidates)
            trace.clear()
            trace.addAll(tables.trace)
            writes++
        }
    }

    private class Tables(
        val jobs: MutableMap<String, LookupJobEntity>,
        val candidates: MutableList<CandidateEntity>,
        val trace: MutableList<IdentificationTraceEntity>,
    ) : IdentificationTables {

        override suspend fun replaceCandidates(contentKey: String, rows: List<CandidateEntity>) {
            require(rows.all { it.contentKey == contentKey })
            candidates.removeAll { it.contentKey == contentKey }
            require(rows.map { it.position }.toSet().size == rows.size) { "Two candidates at one position" }
            candidates += rows
        }

        override suspend fun addTrace(rows: List<IdentificationTraceEntity>) {
            val keys = trace.map { Triple(it.contentKey, it.runId, it.position) }.toSet()
            require(rows.none { Triple(it.contentKey, it.runId, it.position) in keys }) { "A trace row twice" }
            trace += rows
        }

        override suspend fun tracedRuns(contentKey: String): List<String> =
            trace.filter { it.contentKey == contentKey }.map { it.runId }.distinct()

        override suspend fun dropTracedRuns(contentKey: String, runIds: List<String>) {
            trace.removeAll { it.contentKey == contentKey && it.runId in runIds }
        }

        override suspend fun saveJob(job: LookupJobEntity) {
            jobs[job.contentKey] = job
        }

        override suspend fun dropJob(contentKey: String) {
            jobs.remove(contentKey)
        }
    }
}
