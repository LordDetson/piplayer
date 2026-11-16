package io.github.lorddetson.piplayer.library.sync

import androidx.room3.PooledConnection
import androidx.sqlite.SQLiteStatement
import io.github.lorddetson.piplayer.library.db.FOLDER_AND_ONE_BELOW
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.values.AlbumGroupFacts
import io.github.lorddetson.piplayer.library.values.AlbumKey
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.library.values.TrackTags
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * Settles the album groups of a write of display rows (docs/library.md §6.2): the files whose album keys come from their
 * own values and are equal form a group, and they take the key of a release MBID only when every one of them ends up
 * with it. The display writer asks for [before] ahead of a write and calls [settleAlbumGroups] after it, in the write's
 * transaction, once the rows hold the keys of the files' own values.
 */
internal object AlbumGroupKeys {

    /** What a write has to settle, known before its rows are replaced. */
    sealed interface Before {
        /** The write holds every file: its own values settle every group. */
        data object EveryFile : Before

        /** No file of the library has a decided release MBID: every key is a file's own, as the rows are written. */
        data object NoRelease : Before

        /** The keys the rows of the written files held, for the groups that a file leaves. */
        class Keys(val keys: Map<Long, String>) : Before
    }

    /** A file of a group that the write did not touch: its own album key, its release and the key its row holds. */
    private class Stored(val fileId: Long, val ownKey: String?, val release: String?, val storedKey: String?)

    /** What a write of [fileIds] has to settle; read before the write replaces their rows. */
    suspend fun PooledConnection.before(fileIds: List<Long>): Before {
        // Counting the files is only worth it for a write that may hold all of them.
        if (fileIds.size >= EVERY_FILE_FROM && fileIds.size >= count(FILE_COUNT)) return Before.EveryFile
        if (count(DECIDED_RELEASES) == 0L) return Before.NoRelease
        return usePrepared(KEYS_OF) { statement ->
            statement.bindText(1, buildJsonArray { fileIds.forEach { add(it) } }.toString())
            Before.Keys(buildMap { while (statement.step()) if (!statement.isNull(1)) put(statement.getLong(0), statement.getText(1)) })
        }
    }

    /**
     * Gives each file of the groups of [fileIds] (written with [values], in the same order) the key its group holds, and
     * so too the files of the groups they left. A write of every file settles every group from [values] alone. A
     * smaller one reads the other files of each group: for a key of an album and folder, the files whose rows hold it
     * as their own, or when none does (the group holds a release's key, or the write holds all of it) the files of its
     * album folder and the folders one below it, or past [FOLDERS_ONE_BY_ONE] such groups the files with a decided
     * release, since only they can hold a release's key that is not their own; for a key of a tag release MBID, the
     * files whose tags carry it.
     */
    suspend fun PooledConnection.settleAlbumGroups(fileIds: List<Long>, values: List<DisplayValues>, before: Before) {
        if (before == Before.NoRelease) return
        val groups = HashMap<String, MutableList<Pair<Long, AlbumGroupFacts>>>()
        for (i in fileIds.indices) {
            val facts = values[i].albumGroup ?: continue
            val key = values[i].albumKey ?: continue
            groups.getOrPut(key) { ArrayList() } += fileIds[i] to facts
        }
        val updates = HashMap<Long, String>()
        if (before == Before.EveryFile) {
            // No other file can belong to a group, and only a decided release can move one.
            for ((key, members) in groups) {
                if (members.none { it.second.decidedRelease }) continue
                val target = AlbumKey.ofGroup(key, members.map { it.second.release })
                if (target != key) members.forEach { (fileId, _) -> updates[fileId] = target }
            }
        } else {
            settleWithStored(fileIds, values, groups, (before as Before.Keys).keys, updates)
        }
        // One statement per key.
        for ((key, files) in updates.entries.groupBy({ it.value }, { it.key })) {
            usePrepared(SET_KEY) { statement ->
                statement.bindText(1, key)
                statement.bindText(2, buildJsonArray { files.forEach { add(it) } }.toString())
                statement.step()
            }
        }
    }

    private suspend fun PooledConnection.settleWithStored(
        fileIds: List<Long>,
        values: List<DisplayValues>,
        groups: Map<String, List<Pair<Long, AlbumGroupFacts>>>,
        keysBefore: Map<Long, String>,
        updates: MutableMap<Long, String>,
    ) {
        val written = fileIds.toHashSet()
        // The groups the written files left: a key a row held that is no longer the file's.
        val left = HashMap<String, Long>()
        for (i in fileIds.indices) {
            val old = keysBefore[fileIds[i]] ?: continue
            if (old != values[i].albumKey && old !in groups) left.putIfAbsent(old, fileIds[i])
        }
        if (groups.isEmpty() && left.isEmpty()) return
        val keys = groups.keys + left.keys
        val (released, own) = keys.partition { it.startsWith(AlbumKey.RELEASE_PREFIX) }
        // A statement for all groups at once, so that a whole run of the decision mirror reads no folder by folder.
        val holding = select(HOLDING, own).filter { it.fileId !in written && it.ownKey == it.storedKey }.groupBy { it.ownKey }
        val tagged = select(TAGGED, released).filter { it.fileId !in written }.groupBy { it.ownKey }
        // A group left behind is found by the files that hold its key; with none, nothing of it is left to move back.
        val unheld = own.filter { it !in holding && it in groups }
        val elsewhere = when {
            unheld.isEmpty() -> emptyMap()
            unheld.size <= FOLDERS_ONE_BY_ONE -> unheld.associateWith { key -> inFolder(groups.getValue(key).first().second) }
            else -> select(DECIDED, null).groupBy { it.ownKey }
        }
        for (key in keys) {
            val members = groups[key].orEmpty()
            val others = when {
                key.startsWith(AlbumKey.RELEASE_PREFIX) -> tagged[key]
                else -> holding[key] ?: elsewhere[key]
            }.orEmpty().filter { it.fileId !in written && it.ownKey == key }
            if (members.isEmpty() && others.isEmpty()) continue
            val target = AlbumKey.ofGroup(key, members.map { it.second.release } + others.map { it.release })
            if (target != key) members.forEach { (fileId, _) -> updates[fileId] = target }
            for (other in others) if (other.storedKey != target) updates[other.fileId] = target
        }
    }

    private suspend fun PooledConnection.count(sql: String): Long = usePrepared(sql) { statement ->
        statement.step()
        statement.getLong(0)
    }

    /** The files of [sql] for the album [keys] (none when there are none), or every file of [sql] for null. */
    private suspend fun PooledConnection.select(sql: String, keys: List<String>?): List<Stored> {
        if (keys != null && keys.isEmpty()) return emptyList()
        return usePrepared(sql) { statement ->
            if (keys != null) statement.bindText(1, buildJsonArray { keys.forEach { add(it) } }.toString())
            stored(statement)
        }
    }

    /** The files of [facts]' volume in its album folder or one folder below it; the caller keeps those of the group. */
    private suspend fun PooledConnection.inFolder(facts: AlbumGroupFacts): List<Stored> = usePrepared(IN_FOLDER) { statement ->
        statement.bindText(1, facts.volume)
        statement.bindText(2, facts.albumFolder)
        statement.bindText(3, facts.albumFolder + FolderKey.AFTER_PATHS)
        stored(statement)
    }

    /** The rows of [statement], in the columns of [COLUMNS], with the key and release of each file's own values. */
    private fun stored(statement: SQLiteStatement): List<Stored> = buildList {
        while (statement.step()) {
            val read = statement.getLong(3) != 0L
            val tags = TrackTags(
                album = statement.text(4),
                albumArtist = statement.text(5),
                releaseMbid = statement.text(6),
                compilation = !statement.isNull(7) && statement.getLong(7) != 0L,
            )
            val own = MergedValues.ownAlbum(IndexValues(album = statement.text(8), albumArtist = statement.text(9)), tags, read)
            val decided = statement.getLong(10) != 0L
            add(
                Stored(
                    fileId = statement.getLong(0),
                    ownKey = own?.let { AlbumKey.of(it, statement.getText(1), statement.getText(2)) },
                    release = if (decided) statement.text(11) else tags.releaseMbid.takeIf { read },
                    storedKey = statement.text(12),
                ),
            )
        }
    }

    private fun SQLiteStatement.text(i: Int): String? = if (isNull(i)) null else getText(i)

    /** A write of fewer files than this does not count the library's: it settles as a write of some of them. */
    private const val EVERY_FILE_FROM = 500

    /** Up to this many groups whose rows hold none of their keys read their album folders one by one. */
    private const val FOLDERS_ONE_BY_ONE = 16

    private const val FILE_COUNT = "SELECT COUNT(*) FROM audio_file"

    // Stops at the first: in an identified library that is soon.
    private const val DECIDED_RELEASES =
        "SELECT EXISTS(SELECT 1 FROM decided_mirror WHERE field = 'release_mbid' AND value IS NOT NULL)"

    private const val KEYS_OF = "SELECT file_id, album_key FROM track_display WHERE file_id IN (SELECT value FROM json_each(?))"

    private const val SET_KEY = "UPDATE track_display SET album_key = ? WHERE file_id IN (SELECT value FROM json_each(?))"

    // What a file's own album key and its release are made of, as the display writer resolves them (MergedValues).
    private const val COLUMNS = "SELECT f.id, f.volume_name, f.relative_path, t.file_id IS NOT NULL, t.album, t.album_artist, " +
        "t.release_mbid, t.compilation, e.album, e.album_artist, m.file_id IS NOT NULL, m.value, d.album_key FROM "

    private const val READ_TRACK = "LEFT JOIN track t ON t.file_id = f.id AND t.start_ms = 0 AND f.tag_pass_at IS NOT NULL "

    private const val JOINS = "LEFT JOIN media_index_entry e ON e.file_id = f.id " +
        "LEFT JOIN decided_mirror m ON m.file_id = f.id AND m.field = 'release_mbid' "

    private const val DISPLAY = "LEFT JOIN track_display d ON d.file_id = f.id "

    /** The files whose rows hold one of the keys; the caller keeps those whose own key it is. */
    private const val HOLDING = COLUMNS + "track_display d JOIN audio_file f ON f.id = d.file_id " + READ_TRACK + JOINS +
        "WHERE d.album_key IN (SELECT value FROM json_each(?))"

    /** The read files whose tags give one of the keys of a release MBID, by the tag pass's albums. */
    private const val TAGGED = COLUMNS + "album a JOIN track t ON t.album_id = a.id AND t.start_ms = 0 " +
        "JOIN audio_file f ON f.id = t.file_id AND f.tag_pass_at IS NOT NULL " + JOINS + DISPLAY +
        "WHERE a.album_key IN (SELECT value FROM json_each(?))"

    /** The files with a decided release MBID: only they can hold a release's key that is not their own. */
    private const val DECIDED = COLUMNS + "decided_mirror m JOIN audio_file f ON f.id = m.file_id " + READ_TRACK +
        "LEFT JOIN media_index_entry e ON e.file_id = f.id " + DISPLAY + "WHERE m.field = 'release_mbid' AND m.value IS NOT NULL"

    /** The files of a folder and of the folders one below it. */
    private const val IN_FOLDER = COLUMNS + "audio_file f " + READ_TRACK + JOINS + DISPLAY + "WHERE " + FOLDER_AND_ONE_BELOW
}
