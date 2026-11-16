package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.covers.CoverPlan
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.CoverStep
import io.github.lorddetson.piplayer.covers.LibraryCoverFacts
import io.github.lorddetson.piplayer.covers.OwnCovers
import io.github.lorddetson.piplayer.covers.SavedCoverIndex
import io.github.lorddetson.piplayer.covers.SavedCovers
import io.github.lorddetson.piplayer.library.db.AudioFileEntity
import io.github.lorddetson.piplayer.library.db.MediaIndexEntryEntity
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** The size of a picture in pixels. */
data class PictureSize(val width: Int, val height: Int)

/** Probes the pictures of a file for its details (docs/library.md §18.3); each call reads, so off the main thread. */
interface PictureProbe {
    /** The size of the picture embedded in the file at [uri], as the player reads it; null without one. */
    suspend fun embedded(uri: String): PictureSize?

    /** Whether Android has a thumbnail for the file at [uri]. */
    suspend fun hasThumbnail(uri: String): Boolean

    /** Whether the lazy Cover Art Archive [step] gives a picture, as the loader would load it (§18.3). */
    suspend fun online(step: CoverStep.Caa): Boolean = false
}

/**
 * Reads the details of a song of the library (docs/library.md §18): its rows from the library database, its identity's
 * date, plays and favorite from [user], its cover as the loader would choose it from [own] and [probe]. Blocks for a few
 * quick reads and the probe: off the main thread.
 */
class LibraryTrackDetails(
    private val access: LibraryTrackRefs.Access,
    private val user: UserDatabase,
    private val own: OwnCovers,
    private val probe: PictureProbe,
    /** The saved covers, whose files a decided cover needs (TI §5.2); none without them. */
    private val saved: SavedCovers? = null,
    /** Whether online lookups are on, without which the plan has no Cover Art Archive step (§13.1). */
    private val online: () -> Boolean = { false },
) {

    /** What the library database holds of one file, read at once. */
    private class Stored(val file: AudioFileEntity, val entry: MediaIndexEntryEntity?, val track: TrackEntity?)

    /** The covers' view of the library, the loader's own (docs/library.md §13.1). */
    private val facts = LibraryCoverFacts(access, user)

    /** The details of the file [fileId], or null when the library has no such file. */
    suspend fun read(fileId: Long): TrackDetails? = withContext(Dispatchers.IO) {
        val stored = access.read { database ->
            runBlocking {
                val dao = database.library()
                dao.file(fileId)?.let { Stored(it, dao.entry(fileId), dao.trackOf(fileId)) }
            }
        } ?: return@withContext null
        val file = stored.file
        val identity = file.contentKey?.let { user.identities().idOfKey(Hex.bytes(it)) }
        val read = file.tagPassAt != null
        TrackDetailsBuilder.of(
            DetailInputs(
                fileId = fileId,
                file = FileFacts(
                    displayName = file.displayName,
                    volume = file.volumeName,
                    relativePath = file.relativePath,
                    size = file.size,
                    mtimeSeconds = file.mtime,
                    mimeType = file.mimeType,
                    read = read,
                    tagState = if (read) null else tagStateOf(file.tagFailure),
                    format = file.format,
                    codec = file.codec,
                    sampleRate = file.sampleRate,
                    bitDepth = file.bitDepth,
                    channels = file.channels,
                    bitrateKbps = file.bitrate,
                    tagEncoding = file.tagEncoding,
                    exactDurationMs = file.durationMs,
                    durationSource = file.durationSource,
                    tagLengthMs = file.tagLengthMs,
                ),
                index = stored.entry?.let {
                    IndexValues(it.title, it.artist, it.album, it.composer, it.genre, it.durationMs, it.albumArtist, it.year, it.trackNumber)
                },
                tags = stored.track?.toTags(),
                user = identity?.let { userFacts(it) },
                cover = coverOf(fileId, stored, identity),
            ),
        )
    }

    /** The identity's date, plays and favorite, read from the user database rather than the mirror (§18.2). */
    private suspend fun userFacts(identityId: Long): UserFacts? {
        val identity = user.identities().identity(identityId) ?: return null
        val plays = user.plays().totalOf(identityId)
        return UserFacts(
            favorite = user.favorites().isFavorite(identityId),
            playCount = plays?.playCount ?: 0,
            lastPlayedAt = plays?.lastPlayedAt,
            addedAt = identity.createdAt,
        )
    }

    /**
     * The cover the loader would show, with its size (§18.3): the first step of the loader's own plan (§13.1) that has a
     * picture, each tried as the loader tries it.
     */
    private suspend fun coverOf(fileId: Long, stored: Stored, identity: Long?): CoverDetail {
        own.load()
        val names = saved?.names() ?: SavedCoverIndex.NONE
        val steps = CoverPlan.of(CoverRequest.Track(fileId), facts, own, names, online())
        for (step in steps) detailOf(step, stored, identity)?.let { return it }
        return CoverDetail(CoverSource.NONE)
    }

    /** What [step] shows, or null when it has no picture. */
    private suspend fun detailOf(step: CoverStep, stored: Stored, identity: Long?): CoverDetail? = when (step) {
        is CoverStep.Own -> if (!copyExists(step.fileName)) {
            null
        } else if (identity != null && own.track(identity) == step.fileName) {
            // Copies have names of their own, so the song's and the album's never share one.
            user.covers().trackCover(identity)?.let { CoverDetail(CoverSource.SONG_OWN, it.width, it.height) }
        } else {
            stored.albumKey()?.let { user.covers().albumCover(it) }?.let { CoverDetail(CoverSource.ALBUM_OWN, it.width, it.height) }
        }
        is CoverStep.Saved -> saved?.file(step.id)?.takeIf { it.name == step.fileName }?.let {
            user.decisions().savedCover(step.id)?.let { row -> CoverDetail(CoverSource.SAVED, row.width, row.height, row.bytes) }
        }
        is CoverStep.Embedded ->
            probe.embedded(step.file.uri)?.let { CoverDetail(CoverSource.EMBEDDED, it.width, it.height, stored.file.embeddedCoverBytes) }
        is CoverStep.Thumbnail -> if (probe.hasThumbnail(step.file.uri)) CoverDetail(CoverSource.THUMBNAIL) else null
        is CoverStep.Caa -> if (probe.online(step)) CoverDetail(CoverSource.CAA) else null
    }

    /** The album key the file's display row holds. */
    private suspend fun Stored.albumKey(): String? = facts.file(file.id)?.albumKey

    /** A reference whose copy is gone counts as no own cover (docs/library.md §13.2). */
    private fun copyExists(fileName: String) = File(own.dir, fileName).isFile

    companion object {
        /** Why the tag pass has not read a file, by its `tag_failure` (docs/library.md §5.3). */
        fun tagStateOf(failure: String?): TagState = when (failure) {
            null -> TagState.NOT_READ
            "unsupported" -> TagState.UNSUPPORTED
            "open" -> TagState.CANNOT_READ
            else -> TagState.FAILED
        }
    }
}
