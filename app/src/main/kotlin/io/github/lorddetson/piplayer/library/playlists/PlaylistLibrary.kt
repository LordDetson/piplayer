package io.github.lorddetson.piplayer.library.playlists

import android.content.ContentUris
import android.provider.MediaStore
import androidx.room3.RoomRawQuery
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.PlaylistFileRow
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.KeyedFile
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.userdata.NewEntry
import io.github.lorddetson.piplayer.userdata.PlaylistEntryEntity
import io.github.lorddetson.piplayer.userdata.PlaylistEntryRow
import io.github.lorddetson.piplayer.userdata.Playlists
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray

/** An entry as the playlist page shows it: its song when it is available (docs/library.md §16.2), otherwise its hints. */
data class PlaylistItem(
    val entryId: Long,
    val position: Int,
    val song: Song?,
    val title: String?,
    val artist: String?,
    val durationMs: Long?,
) {
    val available: Boolean get() = song != null
}

/** What an import did: the new playlist, and how many of its songs were found (docs/library.md §16.6). */
data class ImportResult(val playlistId: Long, val name: String, val found: Int, val total: Int)

/** What the attach pass asks of playlists (docs/library.md §16.2): the hints of step 5, and the linking of entries. */
interface PlaylistLinks {
    /** The hints of the entries of each identity that entries refer to. */
    suspend fun identityHints(): Map<Long, List<EntryHint>>

    /** Links each entry without an identity to the identity of the file of [files] its hints name. */
    suspend fun link(files: List<KeyedFile>)
}

/**
 * Playlists on both databases (docs/library.md §16): their songs, new entries from the library, import and export. The
 * reads block their thread; callers run off the main thread.
 */
class PlaylistLibrary(private val access: LibraryTrackRefs.Access, private val user: UserDatabase) : PlaylistLinks {

    private val playlists get() = Playlists(user)

    /** The entries of [playlistId] in order, each with its song when it is available. */
    fun items(playlistId: Long): List<PlaylistItem> = itemsOf(runBlocking { user.playlists().entryRows(playlistId) })

    /** [rows], as the user database gave them for one playlist, with their songs. */
    fun itemsOf(rows: List<PlaylistEntryRow>): List<PlaylistItem> = resolved(rows).map { (row, file) ->
        if (file == null) {
            PlaylistItem(row.id, row.position, null, row.hintTitle ?: row.hintDisplayName?.substringBeforeLast('.'), row.hintArtist, row.hintDurationMs)
        } else {
            val song = songOf(file)
            PlaylistItem(row.id, row.position, song, song.title, song.artist, song.durationMs)
        }
    }

    /** The songs [playlistId] plays: its available entries in order (docs/library.md §16.4). */
    fun songs(playlistId: Long): List<Song> = items(playlistId).mapNotNull { it.song }

    /** New entries for the files [fileIds], in order, each with its identity when it has one (docs/library.md §16.2). */
    fun entriesFor(fileIds: List<Long>): List<NewEntry> {
        if (fileIds.isEmpty()) return emptyList()
        val files = access.read { database ->
            fileIds.distinct().chunked(CHUNK).flatMap { chunk ->
                query(database, "WHERE f.id IN (SELECT value FROM json_each(?))", chunk.joinToString(",", "[", "]"))
            }
        }.associateBy { it.fileId }
        val identities = identitiesOf(files.values.mapNotNull { it.contentKey })
        return fileIds.mapNotNull { id -> files[id]?.let { newEntry(it, identities) } }
    }

    /** Puts the files [fileIds] at the end of [playlistId]; how many. */
    suspend fun addFiles(playlistId: Long, fileIds: List<Long>): Int {
        val entries = withContext(Dispatchers.IO) { entriesFor(fileIds) }
        return playlists.add(playlistId, entries)
    }

    /** The lines of an export of [playlistId] (docs/library.md §16.6): an unavailable entry is written from its hints. */
    fun exportEntries(playlistId: Long, paths: M3uPaths): List<M3uEntry> =
        resolved(runBlocking { user.playlists().entryRows(playlistId) }).mapNotNull { (row, file) ->
            if (file != null) {
                M3uEntry(paths.pathOf(Place(file.volume, file.relativePath, file.displayName)), M3u.seconds(file.durationMs), file.artist, file.title)
            } else {
                val place = identityPlace(row) ?: entryHint(row).place
                val location = place?.let(paths::pathOf)
                    ?: row.hintDisplayName?.let { name -> row.hintRelativePath.orEmpty() + name }
                    ?: return@mapNotNull null
                M3uEntry(location, M3u.seconds(row.hintDurationMs), row.hintArtist, row.hintTitle ?: row.hintDisplayName?.substringBeforeLast('.'))
            }
        }

    fun export(playlistId: Long, paths: M3uPaths): String = M3u.write(exportEntries(playlistId, paths))

    /**
     * A new playlist from the playlist file [text] (docs/library.md §16.6), named by its `#PLAYLIST:` line or else
     * [fallbackName]; relative lines go from [folder]. Lines that find no file stay as unavailable entries.
     */
    suspend fun import(text: String, fallbackName: String, folder: DocumentFolder?, paths: M3uPaths): ImportResult {
        val playlist = M3u.parse(text)
        val name = Playlists.cleanName(playlist.name.orEmpty()) ?: Playlists.cleanName(fallbackName) ?: DEFAULT_NAME
        val (entries, found) = withContext(Dispatchers.IO) {
            val files = access.read { database -> query(database, "WHERE f.folder_rule != $EXCLUDED") }
            val byId = files.associateBy { it.fileId }
            val matcher = FileMatcher(files.map(::hintFile))
            val matched = playlist.entries.map { line ->
                val place = paths.placeOf(line.location, folder) ?: return@map null
                val hint = place.hint(line.durationSeconds?.let { it * 1_000 })
                line to (place to matcher.find(hint)?.let { byId.getValue(it.fileId) })
            }
            val identities = identitiesOf(matched.mapNotNull { it?.second?.second?.contentKey })
            val entries = matched.filterNotNull().map { (line, found) ->
                val (place, file) = found
                if (file != null) {
                    newEntry(file, identities)
                } else {
                    NewEntry(
                        identityId = null,
                        volume = place.volume,
                        relativePath = place.relativePath,
                        displayName = place.displayName,
                        durationMs = line.durationSeconds?.let { it * 1_000 },
                        title = line.title,
                        artist = line.artist,
                    )
                }
            }
            entries to matched.count { it?.second?.second != null }
        }
        val id = playlists.create(name)
        playlists.add(id, entries)
        return ImportResult(id, name, found, playlist.entries.size)
    }

    override suspend fun identityHints(): Map<Long, List<EntryHint>> =
        user.playlists().identityHints().groupBy({ it.identityId }) { row ->
            EntryHint(row.hintVolumeName, row.hintRelativePath, row.hintDisplayName, row.hintDurationMs, null, Hex.mbidText(row.hintRecordingMbid))
        }

    override suspend fun link(files: List<KeyedFile>) {
        val unlinked = user.playlists().unlinkedEntries()
        if (unlinked.isEmpty() || files.isEmpty()) return
        val links = PlaylistHints.link(
            unlinked.associate { it.id to hintOf(it) },
            files.map { HintFile(it.fileId, it.place, it.durationMs, it.contentKey, it.recordingMbid) },
        )
        if (links.isEmpty()) return
        val identities = identitiesOf(links.values.mapNotNull { it.contentKey })
        val dao = user.playlists()
        user.withWriteTransaction {
            for ((entryId, file) in links) identities[file.contentKey]?.let { dao.link(entryId, it.id) }
        }
    }

    /** Each of [rows] with the file it plays, or null while it is unavailable (docs/library.md §16.2). */
    private fun resolved(rows: List<PlaylistEntryRow>): List<Pair<PlaylistEntryRow, PlaylistFileRow?>> {
        if (rows.isEmpty()) return emptyList()
        val targets = rows.map { row ->
            PlaylistResolution.Target(row.identityId, row.identityKey?.let(Hex::text), identityPlace(row), entryHint(row))
        }
        val keys = targets.mapNotNull { if (it.identityId != null) it.identityKey else it.hint.contentKey }.distinct()
        val places = targets.filter { it.identityId == null }.mapNotNull { it.hint.place }.distinct()
        val (withKey, atPlace) = access.read { database ->
            val byKey = keys.chunked(CHUNK).flatMap { chunk ->
                val json = buildJsonArray { chunk.forEach { add(it) } }.toString()
                query(database, "WHERE f.folder_rule != $EXCLUDED AND f.content_key IN (SELECT value FROM json_each(?))", json)
            }
            val byPlace = places.chunked(CHUNK).flatMap { chunk ->
                val json = buildJsonArray {
                    for (place in chunk) addJsonArray {
                        add(place.volume)
                        add(place.relativePath)
                        add(place.displayName)
                    }
                }.toString()
                query(
                    database,
                    "JOIN json_each(?) j ON f.volume_name = j.value->>0 AND f.relative_path = j.value->>1 AND " +
                        "f.display_name = j.value->>2 WHERE f.folder_rule != $EXCLUDED",
                    json,
                )
            }
            byKey.groupBy { it.contentKey!! } to byPlace.groupBy { Place(it.volume, it.relativePath, it.displayName) }
        }
        val byId = (withKey.values.flatten() + atPlace.values.flatten()).associateBy { it.fileId }
        return rows.zip(targets).map { (row, target) ->
            val hit = PlaylistResolution.fileOf(
                target,
                withKey = { key -> withKey[key].orEmpty().map(::hintFile) },
                atPlace = { place -> atPlace[place].orEmpty().map(::hintFile) },
            )
            row to hit?.let { byId[it.fileId] }
        }
    }

    /** The identities of the content keys [keys] (hex), by key. */
    private fun identitiesOf(keys: List<String>): Map<String, TrackIdentityEntity> = runBlocking {
        keys.distinct().chunked(CHUNK).flatMap { chunk -> user.identities().withKeys(chunk.map(Hex::bytes)) }
            .associateBy { Hex.text(it.contentKey) }
    }

    /**
     * The entry of [file]: its identity when it has one, its place, its key and, once its key is known, its length (the
     * index's length before the tag pass can be far off for a VBR MP3 without a header, docs/library.md §16.2).
     */
    private fun newEntry(file: PlaylistFileRow, identities: Map<String, TrackIdentityEntity>): NewEntry {
        val identity = file.contentKey?.let(identities::get)
        return NewEntry(
            identityId = identity?.id,
            volume = file.volume,
            relativePath = file.relativePath,
            displayName = file.displayName,
            durationMs = if (file.contentKey != null) file.fileDurationMs else null,
            contentKey = file.contentKey?.let(Hex::bytes),
            recordingMbid = identity?.recordingMbid,
            title = file.title,
            artist = file.artist,
        )
    }

    private fun query(database: LibraryDatabase, condition: String, vararg texts: String): List<PlaylistFileRow> = runBlocking {
        database.library().playlistFiles(
            RoomRawQuery("$FILES $condition") { statement -> texts.forEachIndexed { i, text -> statement.bindText(i + 1, text) } },
        )
    }

    private fun songOf(file: PlaylistFileRow) = Song(
        id = file.fileId,
        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, file.mediaStoreId).toString(),
        title = file.title,
        artist = file.artist,
        album = file.album,
        durationMs = file.durationMs,
        mediaStoreId = file.mediaStoreId,
        discNumber = file.discNumber,
    )

    private fun hintFile(file: PlaylistFileRow) =
        HintFile(file.fileId, Place(file.volume, file.relativePath, file.displayName), file.fileDurationMs, file.contentKey)

    companion object {
        /** Bound in one statement at most, well under SQLite's limits. */
        private const val CHUNK = 500

        /** The folder rule of an excluded folder (docs/library.md §3.4): its files are not available. */
        private const val EXCLUDED = 2

        private const val DEFAULT_NAME = "Playlist"

        private const val FILES =
            "SELECT f.id AS fileId, f.media_store_id AS mediaStoreId, f.volume_name AS volume, f.relative_path AS relativePath, " +
                "f.display_name AS displayName, f.content_key AS contentKey, f.duration_ms AS fileDurationMs, d.title, d.artist, " +
                "d.album, d.duration_ms AS durationMs, d.disc_number AS discNumber FROM audio_file f JOIN track_display d ON d.file_id = f.id"

        private fun identityPlace(row: PlaylistEntryRow): Place? {
            val volume = row.identityVolume ?: return null
            val path = row.identityPath ?: return null
            val name = row.identityName ?: return null
            return Place(volume, path, name)
        }

        private fun entryHint(row: PlaylistEntryRow) = EntryHint(
            row.hintVolumeName, row.hintRelativePath, row.hintDisplayName, row.hintDurationMs,
            row.hintContentKey?.let(Hex::text), Hex.mbidText(row.hintRecordingMbid),
        )

        private fun hintOf(entry: PlaylistEntryEntity) = EntryHint(
            entry.hintVolumeName, entry.hintRelativePath, entry.hintDisplayName, entry.hintDurationMs,
            entry.hintContentKey?.let(Hex::text), Hex.mbidText(entry.hintRecordingMbid),
        )
    }
}
