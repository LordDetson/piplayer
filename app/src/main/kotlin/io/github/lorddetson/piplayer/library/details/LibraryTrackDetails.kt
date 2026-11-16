package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.covers.CoverFacts
import io.github.lorddetson.piplayer.covers.CoverFile
import io.github.lorddetson.piplayer.covers.CoverPlan
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.CoverStep
import io.github.lorddetson.piplayer.covers.LibraryCoverFacts
import io.github.lorddetson.piplayer.covers.OwnCovers
import io.github.lorddetson.piplayer.covers.SavedCoverIndex
import io.github.lorddetson.piplayer.covers.SavedCovers
import io.github.lorddetson.piplayer.library.db.AudioFileEntity
import io.github.lorddetson.piplayer.library.db.IdentificationTraceEntity
import io.github.lorddetson.piplayer.library.db.MediaIndexEntryEntity
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.library.mirror.FileValues
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.userdata.Decisions
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

    /**
     * What the library database holds of one file, read at once: its rows, its display row's badge state and the trace of
     * its content key (docs/library.md §18.7).
     */
    private class Stored(
        val file: AudioFileEntity,
        val entry: MediaIndexEntryEntity?,
        val track: TrackEntity?,
        val badgeState: Int?,
        val trace: List<IdentificationTraceEntity>,
    )

    /** The covers' view of the library, the loader's own (docs/library.md §13.1). */
    private val facts = LibraryCoverFacts(access, user)

    private val decisions = Decisions(user)

    /** The details of the file [fileId], or null when the library has no such file. */
    suspend fun read(fileId: Long): TrackDetails? = withContext(Dispatchers.IO) {
        val stored = access.read { database ->
            runBlocking {
                val dao = database.library()
                dao.file(fileId)?.let { file ->
                    val trace = file.contentKey?.let { database.identificationRuns().trace(it) }.orEmpty()
                    Stored(file, dao.entry(fileId), dao.trackOf(fileId), dao.badgeState(fileId), trace)
                }
            }
        } ?: return@withContext null
        val file = stored.file
        val identity = file.contentKey?.let { user.identities().idOfKey(Hex.bytes(it)) }
        val read = file.tagPassAt != null
        val tags = stored.track?.toTags()
        // The decisions of the song's identity, read from the user database as plays are (§18.2, §18.7).
        // The values the tag pass compares, so that "Keep PiPlayer's value" takes the same parts (TI §7.1).
        val location = FileValues.at(file.volumeName, file.relativePath, file.displayName, stored.track.takeIf { read })
        val records = identity?.let { decisions.storedOf(it).map(DecisionRecord::of) }
        val changed = identity?.let { id ->
            decisions.flaggedAt(id, location.volume, location.relativePath, location.displayName, records.orEmpty().map { it.values })
        }.orEmpty()
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
                tags = tags,
                user = identity?.let { userFacts(it) },
                cover = coverOf(fileId, stored, identity),
                decisions = records,
                changedInFile = changed,
                badgeState = stored.badgeState,
                trace = TraceRuns.of(stored.trace),
                target = identity?.let { id -> file.contentKey?.let { DecisionTarget(id, it, location) } },
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
        val file = facts.file(fileId) ?: return CoverDetail(CoverSource.NONE)
        // The plan reads the same facts of the file, not a second copy of them.
        val once = object : CoverFacts by facts {
            override suspend fun file(fileId: Long): CoverFile? = if (fileId == file.fileId) file else facts.file(fileId)
        }
        val steps = CoverPlan.of(CoverRequest.Track(fileId), once, own, names, online())
        for (step in steps) detailOf(step, stored, identity, file.albumKey)?.let { return it }
        return CoverDetail(CoverSource.NONE)
    }

    /** What [step] shows, or null when it has no picture. */
    private suspend fun detailOf(step: CoverStep, stored: Stored, identity: Long?, albumKey: String?): CoverDetail? = when (step) {
        is CoverStep.Own -> if (!copyExists(step.fileName)) {
            null
        } else if (identity != null && own.track(identity) == step.fileName) {
            // Copies have names of their own, so the song's and the album's never share one.
            user.covers().trackCover(identity)?.let { CoverDetail(CoverSource.SONG_OWN, it.width, it.height) }
        } else {
            albumKey?.let { user.covers().albumCover(it) }?.let { CoverDetail(CoverSource.ALBUM_OWN, it.width, it.height) }
        }
        is CoverStep.Saved -> saved?.file(step.id)?.takeIf { it.name == step.fileName }?.let {
            user.decisions().savedCover(step.id)?.let { row -> CoverDetail(CoverSource.SAVED, row.width, row.height, row.bytes) }
        }
        is CoverStep.Embedded ->
            probe.embedded(step.file.uri)?.let { CoverDetail(CoverSource.EMBEDDED, it.width, it.height, stored.file.embeddedCoverBytes) }
        is CoverStep.Thumbnail -> if (probe.hasThumbnail(step.file.uri)) CoverDetail(CoverSource.THUMBNAIL) else null
        is CoverStep.Caa -> if (probe.online(step)) CoverDetail(CoverSource.CAA) else null
    }

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
