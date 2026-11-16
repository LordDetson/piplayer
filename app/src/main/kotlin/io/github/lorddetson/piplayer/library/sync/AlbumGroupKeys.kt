package io.github.lorddetson.piplayer.library.sync

import androidx.room3.PooledConnection
import androidx.sqlite.SQLiteStatement
import io.github.lorddetson.piplayer.library.values.AlbumGroupFacts
import io.github.lorddetson.piplayer.library.values.AlbumKey
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.library.values.TrackTags

/**
 * Settles the album groups of a write of display rows (docs/library.md §6.2): a file's album key comes from its own
 * values, and the files of a group take the key of a release MBID only when every one of them ends up with it. The
 * display writer calls it in the transaction of every write, after the rows are written with the keys of the files' own
 * values.
 */
internal object AlbumGroupKeys {

    /** A file of a group that the write did not touch: its own album key, its release and the key its row holds. */
    private class Stored(val fileId: Long, val ownKey: String?, val release: String?, val storedKey: String?)

    /**
     * Gives each file of the groups of [fileIds] (written with [values], in the same order) the key its group holds. A
     * write of every file settles every group from [values] alone. A smaller one, while some file of the library has a
     * decided release MBID, reads the other files of each group: for a key of the files' own album and folder, the files
     * whose rows hold it, or when none does (the group took a release's key, or the write holds all of it) the files of
     * its album folder; for a key of a tag release MBID, the files whose tags carry it.
     */
    suspend fun PooledConnection.settleAlbumGroups(fileIds: List<Long>, values: List<DisplayValues>) {
        val groups = HashMap<String, MutableList<Pair<Long, AlbumGroupFacts>>>()
        for (i in fileIds.indices) {
            val facts = values[i].albumGroup ?: continue
            val key = values[i].albumKey ?: continue
            groups.getOrPut(key) { ArrayList() } += fileIds[i] to facts
        }
        if (groups.isEmpty()) return
        val updates = LinkedHashMap<Long, String>()
        if (fileIds.size >= count(FILE_COUNT)) {
            // Every file is here: no other file can belong to a group, and only a decided release can move one.
            for ((key, members) in groups) {
                if (members.none { it.second.decidedRelease }) continue
                val target = AlbumKey.ofGroup(key, members.map { it.second.release })
                if (target != key) members.forEach { (fileId, _) -> updates[fileId] = target }
            }
        } else {
            // Without a decided release anywhere every key is a file's own, as the rows were written.
            if (count(DECIDED_RELEASES) == 0L) return
            val written = fileIds.toHashSet()
            val (released, own) = groups.keys.partition { it.startsWith(RELEASE_PREFIX) }
            // In two statements for all groups, so that a whole run of the decision mirror reads no folder by folder.
            val holding = holding(own).filter { it.fileId !in written }.groupBy { it.storedKey }
            val tagged = tagged(released).filter { it.fileId !in written }.groupBy { it.ownKey }
            for ((key, members) in groups) {
                val others = if (key.startsWith(RELEASE_PREFIX)) {
                    tagged[key].orEmpty()
                } else {
                    holding[key] ?: inFolder(members.first().second).filter { it.fileId !in written && it.ownKey == key }
                }
                val target = AlbumKey.ofGroup(key, members.map { it.second.release } + others.map { it.release })
                if (target != key) members.forEach { (fileId, _) -> updates[fileId] = target }
                for (other in others) if (other.storedKey != target) updates[other.fileId] = target
            }
        }
        for ((fileId, key) in updates) {
            usePrepared(SET_KEY) { statement ->
                statement.bindText(1, key)
                statement.bindLong(2, fileId)
                statement.step()
            }
        }
    }

    private suspend fun PooledConnection.count(sql: String): Long = usePrepared(sql) { statement ->
        statement.step()
        statement.getLong(0)
    }

    /**
     * The files whose rows hold one of the [keys] of an album and folder; such a key is never a release's, so a file
     * that holds it holds its own, and its release is its decided one (its tags carry none, or its key would be one).
     */
    private suspend fun PooledConnection.holding(keys: List<String>): List<Stored> {
        if (keys.isEmpty()) return emptyList()
        return usePrepared(HOLDING) { statement ->
            statement.bindText(1, json(keys))
            buildList {
                while (statement.step()) {
                    val key = statement.getText(1)
                    add(Stored(statement.getLong(0), key, if (statement.getLong(2) != 0L) statement.text(3) else null, key))
                }
            }
        }
    }

    /** The files of [facts]' volume under its album folder; those of other folders below it the caller drops by their key. */
    private suspend fun PooledConnection.inFolder(facts: AlbumGroupFacts): List<Stored> = usePrepared(IN_FOLDER) { statement ->
        statement.bindText(1, facts.volume)
        statement.bindText(2, facts.albumFolder)
        statement.bindText(3, facts.albumFolder + LAST_CHARACTER)
        stored(statement)
    }

    /** The read files whose tags give one of the album [keys] of a release MBID, by the tag pass's albums. */
    private suspend fun PooledConnection.tagged(keys: List<String>): List<Stored> {
        if (keys.isEmpty()) return emptyList()
        return usePrepared(TAGGED) { statement ->
            statement.bindText(1, json(keys))
            stored(statement).filter { it.ownKey in keys }
        }
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

    /** [keys] as a JSON array of strings for `json_each`. */
    private fun json(keys: List<String>): String = keys.joinToString(",", "[", "]") { key ->
        buildString {
            append('"')
            for (c in key) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> append(c)
                }
            }
            append('"')
        }
    }

    private const val RELEASE_PREFIX = "mbid:"

    /** U+10FFFF, after every character a path may hold, so that the range ends after the folder's last path. */
    private const val LAST_CHARACTER = "􏿿"

    private const val FILE_COUNT = "SELECT COUNT(*) FROM audio_file"

    // Stops at the first: in an identified library that is soon.
    private const val DECIDED_RELEASES =
        "SELECT EXISTS(SELECT 1 FROM decided_mirror WHERE field = 'release_mbid' AND value IS NOT NULL)"

    private const val SET_KEY = "UPDATE track_display SET album_key = ? WHERE file_id = ?"

    private const val HOLDING = "SELECT d.file_id, d.album_key, m.file_id IS NOT NULL, m.value FROM track_display d " +
        "LEFT JOIN decided_mirror m ON m.file_id = d.file_id AND m.field = 'release_mbid' " +
        "WHERE d.album_key IN (SELECT value FROM json_each(?))"

    // What a file's own album key and its release are made of, as the display writer resolves them (MergedValues).
    private const val COLUMNS = "SELECT f.id, f.volume_name, f.relative_path, t.file_id IS NOT NULL, t.album, t.album_artist, " +
        "t.release_mbid, t.compilation, e.album, e.album_artist, m.file_id IS NOT NULL, m.value, d.album_key FROM "

    private const val JOINS = "LEFT JOIN media_index_entry e ON e.file_id = f.id " +
        "LEFT JOIN decided_mirror m ON m.file_id = f.id AND m.field = 'release_mbid' " +
        "LEFT JOIN track_display d ON d.file_id = f.id "

    private const val IN_FOLDER = COLUMNS + "audio_file f " +
        "LEFT JOIN track t ON t.file_id = f.id AND t.start_ms = 0 AND f.tag_pass_at IS NOT NULL " + JOINS +
        "WHERE f.volume_name = ? AND f.relative_path >= ? AND f.relative_path < ?"

    private const val TAGGED = COLUMNS + "album a JOIN track t ON t.album_id = a.id AND t.start_ms = 0 " +
        "JOIN audio_file f ON f.id = t.file_id AND f.tag_pass_at IS NOT NULL " + JOINS +
        "WHERE a.album_key IN (SELECT value FROM json_each(?))"
}
