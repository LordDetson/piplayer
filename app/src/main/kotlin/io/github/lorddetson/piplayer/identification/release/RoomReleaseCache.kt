package io.github.lorddetson.piplayer.identification.release

import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzJson
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.ReleaseCacheEntity
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The release cache of the app (TI §5.1, §10.1): the library database's `release_cache`, reached through [library] so a
 * rebuild never closes the database under it. A row is MusicBrainz JSON under one MBID the release is found by; a row
 * older than [maxAge] (time from [clock]) counts as missing, so a later lookup brings MusicBrainz's corrections, and the
 * next [put] drops such rows. A row that no longer reads as a release counts as missing too.
 */
class RoomReleaseCache(
    private val library: LibraryAccess,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAge: Duration = MAX_AGE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReleaseCache {

    override suspend fun get(releaseId: String): ReleaseDto? = withContext(ioDispatcher) { read(releaseId) }

    private fun read(releaseId: String): ReleaseDto? {
        // The block runs under the library's lock, which is held by its thread: the read blocks that thread.
        val row = library.use { database -> runBlocking { database.identificationCache().release(releaseId) } } ?: return null
        if (row.fetchedAt < oldestKept()) return null
        return try {
            MusicBrainzJson.decodeFromString<ReleaseDto>(row.payloadJson)
        } catch (e: IllegalArgumentException) {
            // Kotlin serialization's errors, SerializationException among them, are of this type.
            null
        }
    }

    override suspend fun put(release: ReleaseDto, requestedId: String): Unit =
        withContext(ioDispatcher) { write(release, requestedId) }

    private fun write(release: ReleaseDto, requestedId: String) {
        val now = clock.millis()
        val json = MusicBrainzJson.encodeToString(ReleaseDto.serializer(), release)
        val rows = listOf(release.id, requestedId).distinct().map { ReleaseCacheEntity(it, json, now) }
        library.use { database ->
            runBlocking {
                database.withWriteTransaction {
                    database.identificationCache().dropReleasesBefore(now - maxAge.inWholeMilliseconds)
                    database.identificationCache().saveReleases(rows)
                }
            }
        }
    }

    private fun oldestKept(): Long = clock.millis() - maxAge.inWholeMilliseconds

    companion object {
        /** How long a looked-up release is used (TI §10.1). */
        val MAX_AGE: Duration = 30.days
    }
}
