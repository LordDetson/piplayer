package io.github.lorddetson.piplayer.identification.pipeline

import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.CandidateEntity
import io.github.lorddetson.piplayer.library.db.IdentificationRunDao
import io.github.lorddetson.piplayer.library.db.IdentificationTraceEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LookupJobEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The working rows of identification in the library database that [library] reaches (TI §10.1), so that a rebuild never
 * closes the database under a read or a write. A run's rows are written in one transaction.
 */
class RoomIdentificationStore(
    private val library: LibraryAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IdentificationStore {

    override suspend fun job(contentKey: String): LookupJobEntity? = withContext(ioDispatcher) {
        // The block runs under the library's lock, held by its thread: the read blocks that thread.
        library.use { database -> runBlocking { database.identificationRuns().job(contentKey) } }
    }

    override suspend fun write(block: suspend IdentificationTables.() -> Unit) = withContext(ioDispatcher) {
        library.use { database ->
            runBlocking { database.withWriteTransaction { Tables(database.identificationRuns()).block() } }
        }
    }

    private class Tables(private val dao: IdentificationRunDao) : IdentificationTables {

        override suspend fun replaceCandidates(contentKey: String, rows: List<CandidateEntity>) {
            require(rows.all { it.contentKey == contentKey }) { "A candidate of another content key" }
            dao.dropCandidates(contentKey)
            if (rows.isNotEmpty()) dao.addCandidates(rows)
        }

        override suspend fun addTrace(rows: List<IdentificationTraceEntity>) {
            if (rows.isNotEmpty()) dao.addTrace(rows)
        }

        override suspend fun tracedRuns(contentKey: String): List<String> = dao.tracedRuns(contentKey)

        override suspend fun dropTracedRuns(contentKey: String, runIds: List<String>) {
            // A few runs at most, far under SQLite's limit of arguments.
            if (runIds.isNotEmpty()) dao.dropTracedRuns(contentKey, runIds)
        }

        override suspend fun saveJob(job: LookupJobEntity) = dao.saveJob(job)

        override suspend fun dropJob(contentKey: String) = dao.dropJob(contentKey)
    }
}
