package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.userdata.BatchDecisionTrackRow
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.runBlocking

/**
 * The batch review over the app's two databases (TI §8): the `batch` and `batch_file` tables with the last runs of the
 * library database, and the `decision` rows with the batch's UUID in the user database. The library is read and written
 * through [library]'s lock, and the user database only after the library's block ended, so the two locks are never held
 * at once (TI §7.1).
 *
 * A file's key comes from the library when the tag pass has read it. A file it has not read yet keeps no key there, so a
 * decision of the batch names it by its place, the location hint of the identity Apply made (TI §10.2); the card then
 * reads the run by that key, and the track shows as the run found it rather than as a file without one.
 */
class RoomBatchReviewStore(
    private val library: LibraryAccess,
    private val user: () -> UserDatabase,
) : BatchReviewStore {

    override suspend fun reviewing(): List<BatchEntity> =
        library.use { database -> runBlocking { database.batches().inStatus(BatchEntity.STATUS_REVIEW) } }

    override suspend fun files(batchUuid: String): List<BatchFile> =
        library.use { database -> runBlocking { database.batches().files(batchUuid) } }

    override suspend fun states(batchUuid: String): Map<Long, FileState> {
        // One pass over the library: the files' places and keys, the scores and retry markers of those keys.
        val (places, scores, retry) = library.use { database ->
            val places = runBlocking { database.batches().places(batchUuid) }
            val runs = database.identificationRuns()
            val scores = HashMap<String, MutableList<Double>>()
            val retry = HashSet<String>()
            val keys = places.mapNotNull { it.contentKey }.distinct()
            for (chunk in keys.chunked(CHUNK)) {
                for (row in runBlocking { runs.scores(chunk) }) scores.getOrPut(row.contentKey) { mutableListOf() } += row.score
                retry += runBlocking { runs.retryLater(chunk) }
            }
            Triple(places, scores, retry)
        }
        if (places.isEmpty()) return emptyMap()
        // The batch's decisions, read after the library's block: the tracks they stand for, by key or by place. A decision
        // made for a file its place names wins over a key the library still holds for audio that changed in place.
        val bytes = requireNotNull(Hex.mbidBytes(batchUuid)) { "Not a batch UUID: $batchUuid" }
        val decisions = user().decisions().decidedTracksOfBatch(bytes)
        val decided = decisions.mapTo(HashSet()) { Hex.text(it.contentKey) }
        val atPlace = decisions.mapNotNull { row -> row.place()?.let { it to Hex.text(row.contentKey) } }.toMap()
        return places.associate { place ->
            val named = atPlace[Triple(place.volume, place.relativePath, place.displayName)]
            val key = named ?: place.contentKey?.takeIf(String::isNotEmpty)
            place.fileId to FileState(
                contentKey = key,
                scores = key?.let(scores::get).orEmpty().take(2),
                retryLater = key != null && key in retry,
                decided = named != null || (key != null && key in decided),
            )
        }
    }

    override suspend fun setStatus(batchUuid: String, status: String) {
        library.use { database -> runBlocking { database.batches().setStatus(batchUuid, status) } }
    }

    /** The place a decision's identity is hinted at, null when hinting names no place. */
    private fun BatchDecisionTrackRow.place(): Triple<String, String, String>? {
        val volume = hintVolume ?: return null
        val path = hintRelativePath ?: return null
        val name = hintDisplayName ?: return null
        return Triple(volume, path, name)
    }

    private companion object {
        /** Content keys bound at a time, under SQLite's limit of arguments. */
        const val CHUNK = 500
    }
}
