package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.library.db.CandidateEntity
import io.github.lorddetson.piplayer.library.db.IdentificationTraceEntity
import io.github.lorddetson.piplayer.library.db.LookupJobEntity

/**
 * The working rows of identification (TI §10.1), kept by content key: the lookup job with its "retry later" time, the
 * candidates of the last run and the trace of the last runs. The app's live in the library database
 * ([RoomIdentificationStore]); the debug spike keeps none ([NONE]).
 */
interface IdentificationStore {

    /** The lookup job of [contentKey], or null. */
    suspend fun job(contentKey: String): LookupJobEntity?

    /** Runs [block] on the tables in one transaction. */
    suspend fun write(block: suspend IdentificationTables.() -> Unit)

    companion object {

        /** Reads no job and keeps nothing: every run starts as the first. */
        val NONE: IdentificationStore = object : IdentificationStore {
            override suspend fun job(contentKey: String): LookupJobEntity? = null

            override suspend fun write(block: suspend IdentificationTables.() -> Unit) = Unit
        }
    }
}

/** The tables of [IdentificationStore] inside one of its transactions. */
interface IdentificationTables {

    /** Replaces the candidates of [contentKey] with [rows], which all belong to it. */
    suspend fun replaceCandidates(contentKey: String, rows: List<CandidateEntity>)

    suspend fun addTrace(rows: List<IdentificationTraceEntity>)

    /** The runs traced for [contentKey], in the order their rows were added, oldest first. */
    suspend fun tracedRuns(contentKey: String): List<String>

    suspend fun dropTracedRuns(contentKey: String, runIds: List<String>)

    suspend fun saveJob(job: LookupJobEntity)

    suspend fun dropJob(contentKey: String)
}
