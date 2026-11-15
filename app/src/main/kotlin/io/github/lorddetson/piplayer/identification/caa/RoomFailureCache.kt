package io.github.lorddetson.piplayer.identification.caa

import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.ApiCacheEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * The failure entries of the app (TI §5.2, §10.1): rows of the library database's `api_cache`, reached through
 * [library] so a rebuild never closes the database under them. A 404 is a row with status 404, a 503 one with status
 * 503 and its count of 503s in a row; no row has a body. Times are kept to the millisecond. An expired entry stays while
 * [FailureTtlPolicy.busy] can still read it, until [FailureTtlPolicy.BUSY_ROW_GAP] after it expired, and the next [put]
 * after that drops it. Each call blocks its thread for one short query, so callers stay off the main thread
 * ([CoverArtArchiveClient] reads on its I/O dispatcher).
 */
class RoomFailureCache(private val library: LibraryAccess) : FailureCache {

    override fun get(key: String): FailureEntry? =
        library.use { database -> runBlocking { database.identificationCache().api(key) } }?.toEntry()

    override fun put(key: String, entry: FailureEntry) {
        val failedAt = entry.failedAt.toEpochMilli()
        val row = ApiCacheEntity(
            key = key,
            status = entry.kind.status,
            body = null,
            fetchedAt = failedAt,
            ttl = entry.expiresAt.toEpochMilli() - failedAt,
            busyInARow = entry.busyInARow,
        )
        library.use { database ->
            runBlocking {
                database.withWriteTransaction {
                    database.identificationCache().dropApiExpiredBefore(failedAt - FailureTtlPolicy.BUSY_ROW_GAP.inWholeMilliseconds)
                    database.identificationCache().saveApi(row)
                }
            }
        }
    }

    override fun remove(key: String) {
        library.use { database -> runBlocking { database.identificationCache().dropApi(key) } }
    }

    private fun ApiCacheEntity.toEntry(): FailureEntry? {
        val kind = FailureKind.entries.firstOrNull { it.status == status } ?: return null
        return FailureEntry(kind, Instant.ofEpochMilli(fetchedAt), Instant.ofEpochMilli(fetchedAt + ttl), busyInARow)
    }

    private val FailureKind.status: Int
        get() = when (this) {
            FailureKind.NOT_FOUND -> 404
            FailureKind.BUSY -> 503
        }
}
