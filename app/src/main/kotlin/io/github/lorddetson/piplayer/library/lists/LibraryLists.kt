package io.github.lorddetson.piplayer.library.lists

import android.content.ContentUris
import android.provider.MediaStore
import androidx.room3.RoomRawQuery
import io.github.lorddetson.piplayer.library.db.AlbumGroupRow
import io.github.lorddetson.piplayer.library.db.ArtistGroupRow
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.ListSongRow
import io.github.lorddetson.piplayer.library.search.SearchTerms
import io.github.lorddetson.piplayer.library.values.TextForms
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.sort.SortKey
import io.github.lorddetson.piplayer.library.sort.SortKeys
import io.github.lorddetson.piplayer.library.sort.Sections
import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.settings.HiddenFiles
import kotlinx.coroutines.runBlocking

/**
 * A song list without its songs (docs/playback.md §10.5): row i is the file [ids] i in the list's order, with its
 * [sections] i and the UTF-8 bytes of its title, artist and album (0 for none), what the controller tree weighs it by.
 */
class SongOutline(
    val ids: LongArray,
    val sections: Array<String?>,
    val titleBytes: IntArray,
    val artistBytes: IntArray,
    val albumBytes: IntArray,
) {
    val size: Int get() = ids.size

    companion object {
        val EMPTY = SongOutline(LongArray(0), emptyArray(), IntArray(0), IntArray(0), IntArray(0))

        /** The outline of [songs], read whole. */
        fun of(songs: List<Song>) = SongOutline(
            ids = LongArray(songs.size) { songs[it].id },
            sections = Array(songs.size) { songs[it].section },
            titleBytes = IntArray(songs.size) { utf8Bytes(songs[it].title) },
            artistBytes = IntArray(songs.size) { utf8Bytes(songs[it].artist) },
            albumBytes = IntArray(songs.size) { utf8Bytes(songs[it].album) },
        )

        /** What [text] takes in UTF-8, as SQLite's `length(CAST(text AS BLOB))` counts it. */
        fun utf8Bytes(text: CharSequence?): Int {
            if (text == null) return 0
            var bytes = 0
            for (char in text) {
                bytes += when {
                    char.code < 0x80 -> 1
                    // A surrogate pair is four bytes.
                    char.code < 0x800 || char.isSurrogate() -> 2
                    else -> 3
                }
            }
            return bytes
        }
    }
}

/** The orders of the Albums tab (docs/library.md §6.3). */
enum class AlbumSort { TITLE, ARTIST, YEAR }

/** The orders of the Artists tab; [SONGS] puts the artist with the most songs first. */
enum class ArtistSort { NAME, SONGS }

/** An album as lists show it; [key] is its album key, which its page is found by (docs/library.md §6.5). */
data class AlbumGroup(
    val key: String,
    val title: String,
    val credit: AlbumCredit,
    val year: Int?,
    val songs: Int,
    internal val titleSort: SortKey,
    internal val artistSort: SortKey?,
    /** The section of the scroll thumb's bubble, by the list's order (docs/library.md §11.2). */
    val section: String? = null,
)

/** An artist as lists show it; [key] is the normalized name, which its page is found by. */
data class ArtistGroup(
    val key: String,
    val name: String,
    val songs: Int,
    val albums: Int,
    /** The section of the scroll thumb's bubble, by the list's order (docs/library.md §11.2). */
    val section: String? = null,
    internal val sortKey: SortKey? = null,
)

/** What a song's menu and Now Playing go to (docs/library.md §6.1). */
data class SongLinks(val albumKey: String?, val artists: List<ArtistLink>, val folder: FolderKey)

data class ArtistLink(val key: String, val name: String)

/** An artist of a file as lists group it: the name, its key and its search text (docs/library.md §6.2). */
data class DisplayArtist(val name: String, val key: String, val searchText: String, val sortKey: SortKey = SortKey.EMPTY)

/** The artist key of a name (docs/library.md §6.2). */
object DisplayArtists {
    /**
     * The normalized name, which the artist's page is found by, so it stays what it is when sort keys change. A name of
     * punctuation alone, such as `+`, normalizes to nothing and keeps itself.
     */
    fun keyOf(name: String): String = keyOf(name, TextNormalizer.normalize(name))

    internal fun keyOf(name: String, normalized: String) = normalized.ifEmpty { name.lowercase() }

    /**
     * [names] as rows, one per key, in order, each sorted by the key of its name; [sortTag], the artist's sort tag, names
     * a single artist (docs/library.md §11.1).
     */
    fun of(names: List<String>, forms: TextForms = TextForms(), sortTag: String? = null): List<DisplayArtist> =
        names.map { name ->
            forms.shared(name).let { DisplayArtist(name, keyOf(name, it.normalized), it.indexed, forms.sortKey(sortTag ?: name)) }
        }.distinctBy { it.key }
}

/**
 * The lists of the library tabs and pages on the library database, without the hidden files (docs/library.md §6). The
 * calls block; make them off the main thread.
 */
class LibraryLists(private val database: LibraryDatabase, private val hidden: HiddenFiles = HiddenFiles()) {

    private val dao get() = database.library()

    /** The songs of [query] in its order, the first [limit] when given, each with its section (docs/library.md §11.2). */
    fun songs(query: SongQuery, limit: Int? = null): List<Song> {
        val section = sectionOf(query.sort)
        return rows(query, limit).map { row -> song(row, section(row.sectionKey, row.sectionNumber)) }
    }

    /**
     * The rows of [query] in its order with only what the controller tree splits it by (docs/playback.md §10.2, §10.5):
     * each row's file ID, section and the UTF-8 bytes of its title, artist and album. Reading whole songs costs a third
     * more; the tree reads them for the rows it shows.
     */
    fun outline(query: SongQuery): SongOutline {
        if (query.kind == ListKind.TREE) return SongOutline.of(songs(query))
        val sql = Sql(hidden)
        sql.where(filterOf(query) ?: return SongOutline.EMPTY)
        sql.search("d.search_text", query.search)
        val rows = runBlocking { dao.outlineRows(sql.outline(query.sort, orderOf(query.sort, query.reversed))) }
        val section = sectionOf(query.sort)
        return SongOutline(
            ids = LongArray(rows.size) { rows[it].fileId },
            sections = Array(rows.size) { section(rows[it].sectionKey, rows[it].sectionNumber) },
            titleBytes = IntArray(rows.size) { rows[it].titleBytes ?: 0 },
            artistBytes = IntArray(rows.size) { rows[it].artistBytes ?: 0 },
            albumBytes = IntArray(rows.size) { rows[it].albumBytes ?: 0 },
        )
    }

    private fun song(row: ListSongRow, section: String?) =
        Song(
            id = row.fileId,
            uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row.mediaStoreId).toString(),
            title = row.title,
            artist = row.artist,
            album = row.album,
            durationMs = row.durationMs,
            discNumber = row.discNumber,
            mediaStoreId = row.mediaStoreId,
            section = section,
        )

    /** A song read by its ID, in no list's order, so without a section. */
    private fun songOf(row: ListSongRow) = song(row, section = null)

    /** How a row of [sort] finds its section from what the query selected for it. */
    private fun sectionOf(sort: SongSort): (key: ByteArray?, number: Long?) -> String? = when (sort) {
        SongSort.TITLE, SongSort.ARTIST, SongSort.ALBUM, SongSort.FILE_NAME -> {
            val buckets = SortKeys.current().buckets
            { key, _ -> Sections.letter(SortKey.of(key), buckets) }
        }
        SongSort.LENGTH -> { _, number -> Sections.minutes(number) }
        SongSort.YEAR -> { _, number -> Sections.year(number?.toInt()) }
        SongSort.TRACK -> { _, _ -> null }
    }

    private fun rows(query: SongQuery, limit: Int?): List<ListSongRow> {
        if (query.kind == ListKind.TREE) {
            val songs = treeSongs(query)
            return if (limit == null) songs else songs.take(limit)
        }
        val sql = Sql(hidden)
        sql.where(filterOf(query) ?: return emptyList())
        sql.search("d.search_text", query.search)
        return run(sql.songs(query.sort, orderOf(query.sort, query.reversed), limit))
    }

    /** A folder with its subfolders: each folder in the order of its tree, its own songs in the query's sort. */
    private fun treeSongs(query: SongQuery): List<ListSongRow> {
        val folder = query.folder ?: return emptyList()
        val order = folders().treeOrder(folder.volume, folder.relativePath)
        if (order.isEmpty()) return emptyList()
        val sql = Sql(hidden)
        sql.where("f.volume_name = ?", folder.volume)
        if (folder.relativePath.isNotEmpty()) {
            // Every path that starts with the folder's sorts between it and it followed by the highest code point.
            sql.where("f.relative_path >= ? AND f.relative_path < ?", folder.relativePath, folder.relativePath + MAX_CODE_POINT)
        }
        sql.search("d.search_text", query.search)
        val byFolder = run(sql.songs(query.sort, orderOf(query.sort, reversed = false), limit = null)).groupBy { it.relativePath }
        val songs = order.flatMap { byFolder[it].orEmpty() }
        return if (query.reversed) songs.reversed() else songs
    }

    fun albums(sort: AlbumSort = AlbumSort.TITLE, reversed: Boolean = false, search: String = ""): List<AlbumGroup> {
        val sql = Sql(hidden)
        sql.having("d.album_search", search)
        val albums = runAlbums(sql)
        val comparator = when (sort) {
            AlbumSort.TITLE -> compareBy<AlbumGroup> { it.titleSort }
            AlbumSort.ARTIST -> compareBy<AlbumGroup, SortKey?>(nullsLast()) { it.artistSort }.thenBy { it.titleSort }
            AlbumSort.YEAR -> compareBy<AlbumGroup, Int?>(nullsLast()) { it.year }.thenBy { it.titleSort }
        }.thenBy { it.key }
        val buckets by lazy(LazyThreadSafetyMode.NONE) { SortKeys.current().buckets }
        return albums.sortedWith(if (reversed) comparator.reversed() else comparator).map { album ->
            album.copy(
                section = when (sort) {
                    AlbumSort.TITLE -> Sections.letter(album.titleSort, buckets)
                    AlbumSort.ARTIST -> Sections.letter(album.artistSort, buckets)
                    AlbumSort.YEAR -> Sections.year(album.year)
                },
            )
        }
    }

    /** The album of [key], or null when no visible song has it. */
    fun album(key: String): AlbumGroup? = runAlbums(Sql(hidden).where("d.album_key = ?", key)).singleOrNull()

    /** The albums [artistKey] has songs on, by year, then title. */
    fun artistAlbums(artistKey: String): List<AlbumGroup> {
        val sql = Sql(hidden).where(
            "d.album_key IN (SELECT t.album_key FROM display_artist a JOIN track_display t ON t.file_id = a.file_id " +
                "JOIN audio_file g ON g.id = a.file_id WHERE a.artist_key = ? AND t.album_key IS NOT NULL AND " +
                visible("g", "t") + ")",
            artistKey, hidden.showSystemSounds, hidden.showRecordings, hidden.minLengthMs,
        )
        return runAlbums(sql).sortedWith(compareBy<AlbumGroup, Int?>(nullsLast()) { it.year }.thenBy { it.titleSort }.thenBy { it.key })
    }

    fun artists(sort: ArtistSort = ArtistSort.NAME, reversed: Boolean = false, search: String = ""): List<ArtistGroup> {
        val sql = Sql(hidden)
        sql.having("a.search_text", search)
        val byName = compareBy<ArtistGroup, SortKey?>(nullsLast()) { it.sortKey }.thenBy { it.key }
        val comparator = when (sort) {
            ArtistSort.NAME -> byName
            ArtistSort.SONGS -> compareByDescending<ArtistGroup> { it.songs }.then(byName)
        }
        val buckets by lazy(LazyThreadSafetyMode.NONE) { SortKeys.current().buckets }
        return runArtists(sql).sortedWith(if (reversed) comparator.reversed() else comparator).map { artist ->
            artist.copy(
                section = when (sort) {
                    ArtistSort.NAME -> Sections.letter(artist.sortKey, buckets)
                    ArtistSort.SONGS -> Sections.count(artist.songs)
                },
            )
        }
    }

    /** The artist of [key], or null when no visible song has them. */
    fun artist(key: String): ArtistGroup? = runArtists(Sql(hidden).where("a.artist_key = ?", key)).singleOrNull()

    /** The folder levels of the visible songs. */
    fun folders(): FolderLevels {
        val sql = Sql(hidden)
        val rows = runBlocking {
            dao.folderCounts(
                sql.raw(
                    "SELECT f.volume_name AS volume, f.relative_path AS relativePath, COUNT(*) AS songs " +
                        "FROM track_display d JOIN audio_file f ON f.id = d.file_id WHERE ${sql.conditions()} " +
                        "GROUP BY f.volume_name, f.relative_path",
                ),
            )
        }
        return FolderLevels(rows.map { FolderSongs(it.volume, it.relativePath, it.songs) })
    }

    /** Where the song of [fileId] belongs, or null for none in the library. */
    fun links(fileId: Long): SongLinks? = runBlocking {
        val place = dao.songPlace(fileId) ?: return@runBlocking null
        SongLinks(
            albumKey = place.albumKey,
            artists = dao.displayArtists(place.fileId).map { ArtistLink(it.artistKey, it.name) },
            folder = FolderKey(place.volume, place.relativePath),
        )
    }

    /**
     * The songs of the files [ids], visible or hidden, by their file ID (docs/playback.md §9.1, §9.4): the songs added to
     * a queue and the added entries of a restored one. A file that is gone is left out.
     */
    fun songsById(ids: Collection<Long>): Map<Long, Song> = ids.distinct().chunked(ID_CHUNK).flatMap { chunk ->
        val sql = "SELECT f.id AS fileId, f.media_store_id AS mediaStoreId, d.title, d.artist, d.album, " +
            "d.duration_ms AS durationMs, d.disc_number AS discNumber, f.relative_path AS relativePath " +
            "FROM track_display d JOIN audio_file f ON f.id = d.file_id WHERE f.id IN (" + chunk.joinToString(",") { "?" } + ")"
        run(RoomRawQuery(sql) { statement -> chunk.forEachIndexed { i, id -> statement.bindLong(i + 1, id) } })
    }.associate { it.fileId to songOf(it) }

    private fun filterOf(query: SongQuery): Pair<String, List<Any>>? = when (query.kind) {
        ListKind.SONGS -> "1" to emptyList()
        ListKind.ALBUM -> "d.album_key = ?" to listOf(query.key)
        ListKind.ARTIST -> "d.file_id IN (SELECT file_id FROM display_artist WHERE artist_key = ?)" to listOf(query.key)
        ListKind.FOLDER -> query.folder?.let { "f.volume_name = ? AND f.relative_path = ?" to listOf(it.volume, it.relativePath) }
        ListKind.TREE -> null
    }

    private fun run(query: RoomRawQuery): List<ListSongRow> = runBlocking { dao.listSongs(query) }

    private fun runAlbums(sql: Sql): List<AlbumGroup> = runBlocking {
        dao.albumGroups(
            sql.raw(
                "SELECT d.album_key AS albumKey, MIN(d.album) AS title, MAX(d.album_artist) AS albumArtist, " +
                    "MAX(d.compilation) AS compilation, MIN(d.artist) AS artist, " +
                    "(COUNT(d.artist_key) = COUNT(*) AND MIN(d.artist_key) = MAX(d.artist_key)) AS oneArtist, " +
                    "MIN(d.year) AS year, COUNT(*) AS songs, MIN(d.album_sort) AS titleSort, " +
                    "COALESCE(MAX(d.album_artist_sort), MIN(d.artist_sort)) AS artistSort " +
                    "FROM track_display d JOIN audio_file f ON f.id = d.file_id " +
                    "WHERE d.album_key IS NOT NULL AND ${sql.conditions()} GROUP BY d.album_key${sql.havingClause()}",
            ),
        )
    }.map(::albumOf)

    private fun albumOf(row: AlbumGroupRow): AlbumGroup {
        val credit = AlbumCredit.of(row.albumArtist, row.compilation, row.artist, row.oneArtist)
        return AlbumGroup(
            key = row.albumKey,
            title = row.title,
            credit = credit,
            year = row.year,
            songs = row.songs,
            titleSort = SortKey.of(row.titleSort) ?: SortKey.EMPTY,
            artistSort = SortKey.of(row.artistSort),
        )
    }

    private fun runArtists(sql: Sql): List<ArtistGroup> = runBlocking {
        dao.artistGroups(
            sql.raw(
                "SELECT a.artist_key AS artistKey, MIN(a.name) AS name, COUNT(DISTINCT a.file_id) AS songs, " +
                    "COUNT(DISTINCT d.album_key) AS albums, MIN(a.sort_key) AS sortKey FROM display_artist a JOIN track_display d ON d.file_id = a.file_id " +
                    "JOIN audio_file f ON f.id = a.file_id WHERE ${sql.conditions()} GROUP BY a.artist_key${sql.havingClause()}",
            ),
        )
    }.map { ArtistGroup(it.artistKey, it.name, it.songs, it.albums, sortKey = SortKey.of(it.sortKey)) }

    /** A query's conditions and their arguments, bound in the order they appear. */
    private class Sql(private val hidden: HiddenFiles) {
        private val where = mutableListOf<String>()
        private val whereArgs = mutableListOf<Any>()
        private var having: String? = null
        private val havingArgs = mutableListOf<Any>()
        private var visibleArgs = emptyList<Any>()

        fun where(condition: String, vararg args: Any) = apply {
            where += condition
            whereArgs.addAll(args)
        }

        fun where(condition: Pair<String, List<Any>>) = apply {
            where += condition.first
            whereArgs.addAll(condition.second)
        }

        /** Every word of [search] in [column]. */
        fun search(column: String, search: String) {
            for (term in SearchTerms.of(search)) where("instr($column, ?) > 0", term)
        }

        /** Groups with a row that holds every word of [search] in [column]. */
        fun having(column: String, search: String) {
            val terms = SearchTerms.of(search)
            if (terms.isEmpty()) return
            having = "MAX(" + terms.joinToString(" AND ") { "instr($column, ?) > 0" } + ")"
            havingArgs.addAll(terms)
        }

        fun havingClause() = having?.let { " HAVING $it" }.orEmpty()

        /** The visibility of docs/library.md §3.5 and the conditions; its arguments come first. */
        fun conditions(): String {
            visibleArgs = listOf(hidden.showSystemSounds, hidden.showRecordings, hidden.minLengthMs)
            return (listOf(visible("f", "d")) + where).joinToString(" AND ")
        }

        fun songs(sort: SongSort, order: String, limit: Int?): RoomRawQuery = raw(
            "SELECT f.id AS fileId, f.media_store_id AS mediaStoreId, d.title, d.artist, d.album, d.duration_ms AS durationMs, " +
                "d.disc_number AS discNumber, f.relative_path AS relativePath, ${SECTIONS.getValue(sort)} " +
                "FROM track_display d JOIN audio_file f ON f.id = d.file_id WHERE ${conditions()} " +
                "ORDER BY $order" + (limit?.let { " LIMIT $it" } ?: ""),
        )

        /** The outline of [SongOutline]: the file ID, the section's key or number, the UTF-8 bytes of three texts. */
        fun outline(sort: SongSort, order: String): RoomRawQuery = raw(
            "SELECT f.id AS fileId, ${SECTIONS.getValue(sort)}, length(CAST(d.title AS BLOB)) AS titleBytes, " +
                "length(CAST(d.artist AS BLOB)) AS artistBytes, length(CAST(d.album AS BLOB)) AS albumBytes " +
                "FROM track_display d JOIN audio_file f ON f.id = d.file_id WHERE ${conditions()} ORDER BY $order",
        )

        fun raw(sql: String): RoomRawQuery {
            val args = visibleArgs + whereArgs + havingArgs
            return RoomRawQuery(sql) { statement ->
                args.forEachIndexed { i, arg ->
                    when (arg) {
                        is String -> statement.bindText(i + 1, arg)
                        is Boolean -> statement.bindLong(i + 1, if (arg) 1 else 0)
                        is Number -> statement.bindLong(i + 1, arg.toLong())
                        else -> error("Cannot bind $arg")
                    }
                }
            }
        }
    }

    companion object {
        // Excluded folders never; included folders whole; elsewhere every file but the hidden kinds and short files. A file
        // whose length is unknown is never short (docs/library.md §3.5).
        // Its arguments: whether system sounds and recordings show, and the shortest length shown.
        private fun visible(f: String, d: String) = "$f.folder_rule != 2 AND ($f.folder_rule = 1 OR (" +
            "(? OR NOT ($f.is_ringtone OR $f.is_notification OR $f.is_alarm)) AND (? OR NOT $f.recording) " +
            "AND ($d.duration_ms IS NULL OR $d.duration_ms >= ?)))"

        /** File IDs bound in one query, well under SQLite's limit of arguments. */
        private const val ID_CHUNK = 500

        /** U+10FFFF, after which no character sorts. */
        private const val MAX_CODE_POINT = "\uDBFF\uDFFF"

        /**
         * The lowest album key of the list's songs on the album, so that songs that spell it differently stay together
         * (docs/library.md §11.1); songs without an album have none.
         */
        private const val ALBUM_SORT = "MIN(d.album_sort) OVER (PARTITION BY d.album_key)"

        /** The earliest year of the list's songs on the album; songs without an album group on their own. */
        private const val ALBUM_YEAR = "MIN(d.year) OVER (PARTITION BY d.album_key)"

        /**
         * The terms of each order, ascending; a missing value sorts last. Ties go by title, then the index's ID. The sort
         * keys are BLOBs, which SQLite compares byte by byte: the collator's order (docs/library.md §11.1).
         */
        private val ORDERS = mapOf(
            SongSort.TITLE to listOf("d.title_sort"),
            SongSort.ARTIST to listOf("d.artist_sort IS NULL", "d.artist_sort", "d.title_sort"),
            SongSort.ALBUM to listOf(
                "$ALBUM_SORT IS NULL", ALBUM_SORT, "d.album_key", "d.disc_number IS NULL", "d.disc_number",
                "d.track_number IS NULL", "d.track_number", "d.title_sort",
            ),
            SongSort.LENGTH to listOf("d.duration_ms IS NULL", "d.duration_ms", "d.title_sort"),
            SongSort.TRACK to listOf(
                "d.disc_number IS NULL", "d.disc_number", "d.track_number IS NULL", "d.track_number", "d.file_sort", "d.title_sort",
            ),
            // Each album stays together, by its earliest year; songs without an album come last, by their own year.
            SongSort.YEAR to listOf(
                "d.album_key IS NULL", "$ALBUM_YEAR IS NULL", ALBUM_YEAR, ALBUM_SORT, "d.album_key",
                "d.disc_number IS NULL", "d.disc_number", "d.track_number IS NULL", "d.track_number", "d.year IS NULL",
                "d.year", "d.title_sort",
            ),
            SongSort.FILE_NAME to listOf("d.file_sort", "d.title_sort"),
        )

        /** What each order's section is found from (docs/library.md §11.2): the key it sorts by first, or a number. */
        private val SECTIONS = mapOf(
            SongSort.TITLE to "d.title_sort AS sectionKey, NULL AS sectionNumber",
            SongSort.ARTIST to "d.artist_sort AS sectionKey, NULL AS sectionNumber",
            SongSort.ALBUM to "$ALBUM_SORT AS sectionKey, NULL AS sectionNumber",
            SongSort.FILE_NAME to "d.file_sort AS sectionKey, NULL AS sectionNumber",
            SongSort.LENGTH to "NULL AS sectionKey, d.duration_ms AS sectionNumber",
            SongSort.YEAR to "NULL AS sectionKey, CASE WHEN d.album_key IS NULL THEN d.year ELSE $ALBUM_YEAR END AS sectionNumber",
            SongSort.TRACK to "NULL AS sectionKey, NULL AS sectionNumber",
        )

        /** [sort]'s terms, all reversed for [reversed], which reverses the whole list since the ID makes it total. */
        fun orderOf(sort: SongSort, reversed: Boolean): String =
            (ORDERS.getValue(sort) + "f.media_store_id").joinToString(", ") { if (reversed) "$it DESC" else it }
    }
}
