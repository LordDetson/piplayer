package io.github.lorddetson.piplayer.library.lists

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.PRIMARY
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.MediaKinds
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.settings.HiddenFiles
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The lists of the library tabs and pages on the library database (docs/library.md §6.2 to §6.4). */
@RunWith(AndroidJUnit4::class)
class LibraryListsTest {

    private val database = LibraryDatabase.open(TestFiles.targetContext, file = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true }, FakeRules())
    private val lists = LibraryLists(database, HiddenFiles())

    @Before
    fun fill() {
        index.volume().rows = listOf(
            row(1, "Bravo", path = "Music/Ann/First/", name = "02 Bravo.mp3", artist = "Ann", album = "First", year = 1990, trackNumber = 2, durationMs = 200_000),
            row(2, "Alpha", path = "Music/Ann/First/", name = "01 Alpha.mp3", artist = "Ann", album = "First", year = 1990, trackNumber = 1, durationMs = 100_000),
            row(3, "Charlie", path = "Music/Bob/Second/CD1/", name = "01 Charlie.mp3", artist = "Bob", album = "Second", albumArtist = "Bob", year = 2001, trackNumber = 1001, durationMs = 300_000),
            row(4, "Delta", path = "Music/Bob/Second/CD2/", name = "01 Delta.mp3", artist = "Bob", album = "Second", albumArtist = "Bob", year = 2001, trackNumber = 2001, durationMs = 50_000),
            row(5, "Echo", path = "Music/Mix/", name = "b.mp3", artist = "Cid", album = "Mix", year = 1985, trackNumber = null, durationMs = 70_000),
            row(6, "Foxtrot", path = "Music/Mix/", name = "a.mp3", artist = "Dee", album = "Mix", year = 1985, trackNumber = null, durationMs = null),
            row(7, "Golf", path = "Music/", name = "golf.mp3", artist = "Ann", album = null, year = null, durationMs = 30_000),
            row(8, "Ring", path = "Ringtones/", artist = "Sys", album = "Tones", kinds = MediaKinds(music = false, ringtone = true)),
            row(9, "Short", path = "Music/Short/", artist = "Shorty", album = "Short", durationMs = 3_000),
        )
        runBlocking { sync.sync() }
    }

    @After
    fun close() {
        sync.close()
        database.close()
    }

    /** The songs of [query] by their media index IDs, which the test chose; their file IDs are the library's. */
    private fun ids(query: SongQuery, limit: Int? = null) = lists.songs(query, limit).map { it.mediaStoreId }

    private fun fileOf(mediaStoreId: Long) = runBlocking { database.library().files() }.single { it.mediaStoreId == mediaStoreId }.id

    private val first get() = lists.albums().single { it.title == "First" }
    private val second get() = lists.albums().single { it.title == "Second" }
    private val mix get() = lists.albums().single { it.title == "Mix" }

    @Test
    fun albumsAreGroupedFromTheIndexWithDiscFoldersAsOneAlbum() {
        val albums = lists.albums()

        assertThat(albums.map { it.title }).containsExactly("First", "Mix", "Second")
        assertThat(albums.map { it.songs }).containsExactly(2, 2, 2)
        assertThat(first.credit).isEqualTo(AlbumCredit.Named("Ann"))
        assertThat(second.credit).isEqualTo(AlbumCredit.Named("Bob"))
        assertThat(mix.credit).isEqualTo(AlbumCredit.Various)
        assertThat(second.year).isEqualTo(2001)
    }

    @Test
    fun albumsSortByTitleArtistOrYearAndReverse() {
        assertThat(lists.albums(AlbumSort.YEAR).map { it.title }).containsExactly("Mix", "First", "Second")
        assertThat(lists.albums(AlbumSort.ARTIST).map { it.title }).containsExactly("First", "Second", "Mix")
        assertThat(lists.albums(AlbumSort.TITLE, reversed = true).map { it.title }).containsExactly("Second", "Mix", "First")
    }

    @Test
    fun albumsAreFoundByTitleAndArtist() {
        assertThat(lists.albums(search = "SEC").map { it.title }).containsExactly("Second")
        assertThat(lists.albums(search = "ann").map { it.title }).containsExactly("First")
        assertThat(lists.albums(search = "zzz")).isEmpty()
    }

    @Test
    fun anAlbumIsFoundByItsKeyAndListsItsSongsByDiscAndTrack() {
        val key = second.key

        assertThat(lists.album(key)?.title).isEqualTo("Second")
        assertThat(lists.album("no such key")).isNull()
        val songs = lists.songs(SongQuery.album(key))
        assertThat(songs.map { it.id }).containsExactly(3L, 4L)
        assertThat(songs.map { it.discNumber }).containsExactly(1, 2)
        assertThat(ids(SongQuery.album(first.key))).containsExactly(2L, 1L)
        assertThat(ids(SongQuery.album(first.key).copy(reversed = true))).containsExactly(1L, 2L)
    }

    @Test
    fun artistsAreListedWithTheirSongsAndHiddenFilesCountNowhere() {
        val artists = lists.artists()

        assertThat(artists.map { it.name }).containsExactly("Ann", "Bob", "Cid", "Dee")
        assertThat(artists.map { it.songs }).containsExactly(3, 2, 1, 1)
        assertThat(artists.map { it.albums }).containsExactly(1, 1, 1, 1)
        assertThat(lists.artists(ArtistSort.SONGS).map { it.name }.take(2)).containsExactly("Ann", "Bob")
        assertThat(lists.artists(ArtistSort.NAME, reversed = true).map { it.name }).containsExactly("Dee", "Cid", "Bob", "Ann")
        assertThat(lists.artists(search = "bo").map { it.name }).containsExactly("Bob")
        assertThat(lists.albums().map { it.title }).doesNotContain("Tones", "Short")
    }

    @Test
    fun anArtistPageListsAlbumsByYearAndSongs() {
        val ann = lists.artists().single { it.name == "Ann" }

        assertThat(lists.artist(ann.key)?.name).isEqualTo("Ann")
        assertThat(lists.artist("nobody")).isNull()
        assertThat(lists.artistAlbums(ann.key).map { it.title }).containsExactly("First")
        // Album by year, then disc and track; the song without an album last.
        assertThat(ids(SongQuery.artist(ann.key))).containsExactly(2L, 1L, 7L)
        assertThat(ids(SongQuery.artist(ann.key).copy(sort = SongSort.TITLE))).containsExactly(2L, 1L, 7L)
        assertThat(ids(SongQuery.artist(ann.key).copy(search = "golf"))).containsExactly(7L)
    }

    @Test
    fun songsSortByEveryFieldAndTheReverseIsExact() {
        val songs = SongQuery.SONGS
        assertThat(ids(songs)).containsExactly(2L, 1L, 3L, 4L, 5L, 6L, 7L)
        assertThat(ids(songs.copy(sort = SongSort.ARTIST))).containsExactly(2L, 1L, 7L, 3L, 4L, 5L, 6L)
        assertThat(ids(songs.copy(sort = SongSort.ALBUM))).containsExactly(2L, 1L, 5L, 6L, 3L, 4L, 7L)
        // No length sorts last.
        assertThat(ids(songs.copy(sort = SongSort.LENGTH))).containsExactly(7L, 4L, 5L, 2L, 1L, 3L, 6L)
        for (sort in SongSort.entries) {
            assertThat(ids(songs.copy(sort = sort, reversed = true))).describedAs("$sort").isEqualTo(ids(songs.copy(sort = sort)).reversed())
        }
    }

    @Test
    fun aFirstPageIsTheStartOfTheList() {
        for (sort in SongSort.entries) {
            val query = SongQuery.SONGS.copy(sort = sort)
            assertThat(ids(query, limit = 3)).describedAs("$sort").isEqualTo(ids(query).take(3))
        }
    }

    @Test
    fun songsAreFoundByTheirSearchText() {
        assertThat(ids(SongQuery.SONGS.copy(search = "ann"))).containsExactly(2L, 1L, 7L)
        assertThat(ids(SongQuery.SONGS.copy(search = "alpha ann"))).containsExactly(2L)
        assertThat(ids(SongQuery.SONGS.copy(sort = SongSort.LENGTH, search = "bob"))).containsExactly(4L, 3L)
    }

    @Test
    fun foldersHoldTheirVisibleSongs() {
        val levels = lists.folders()

        assertThat(levels.top().entries.map { it.relativePath }).containsExactly("Music/")
        assertThat(levels.children(PRIMARY, "Music/Bob/Second/").map { it.name }).containsExactly("CD1", "CD2")
        assertThat(levels.children(PRIMARY, "Music/").map { it.songs }).containsExactly(2, 2, 2)
        assertThat(ids(SongQuery.folder(PRIMARY, "Music/Mix/"))).containsExactly(6L, 5L)
        assertThat(ids(SongQuery.folder(PRIMARY, "Music/Mix/").copy(sort = SongSort.TITLE))).containsExactly(5L, 6L)
        assertThat(ids(SongQuery.folder(PRIMARY, "Music/"))).containsExactly(7L)
    }

    @Test
    fun aTreePlaysEachFolderBeforeItsSubfolders() {
        assertThat(ids(SongQuery.tree(PRIMARY, "Music/"))).containsExactly(7L, 2L, 1L, 3L, 4L, 6L, 5L)
        assertThat(ids(SongQuery.tree(PRIMARY, "Music/Bob/"))).containsExactly(3L, 4L)
        assertThat(ids(SongQuery.tree(PRIMARY, "Music/").copy(reversed = true)))
            .containsExactly(5L, 6L, 4L, 3L, 1L, 2L, 7L)
        assertThat(ids(SongQuery.tree(PRIMARY, "Music/").copy(search = "delta"))).containsExactly(4L)
        assertThat(ids(SongQuery.tree(PRIMARY, "Music/Nowhere/"))).isEmpty()
    }

    @Test
    fun aReadFileIsGroupedByItsTagsWithEveryArtist() {
        val dao = database.library()
        val fileId = runBlocking { dao.files() }.single { it.mediaStoreId == 7L }.id
        val tags = TrackTags.from(
            mapOf(
                "TITLE" to listOf("Golf"),
                "ARTISTS" to listOf("Ann", "Zed"),
                "ALBUM" to listOf("Duets"),
                "DATE" to listOf("2010"),
            ),
        )
        runBlocking {
            dao.addTrack(TrackEntity.of(fileId, tags))
            dao.markRead(fileId, at = 1, readerVersion = 1, format = null, codec = null, sampleRate = null, bitDepth = null,
                channels = null, bitrate = null, tagEncoding = null, coverPresent = false, coverBytes = null, tagLengthMs = null)
            TrackDisplayWriter(database).refresh(listOf(fileId))
        }

        assertThat(lists.artists().map { it.name }).contains("Zed")
        assertThat(lists.artists().single { it.name == "Ann" }.let { it.key to it.songs }).isEqualTo(DisplayArtists.keyOf("Ann") to 3)
        assertThat(lists.albums().single { it.title == "Duets" }.year).isEqualTo(2010)
        val zed = lists.artists().single { it.name == "Zed" }
        assertThat(ids(SongQuery.artist(zed.key))).containsExactly(7L)
    }

    @Test
    fun aSongLinksToItsAlbumArtistsAndFolder() {
        val links = lists.links(fileOf(3))!!

        assertThat(links.albumKey).isEqualTo(second.key)
        assertThat(links.artists.map { it.name }).containsExactly("Bob")
        assertThat(links.folder).isEqualTo(io.github.lorddetson.piplayer.library.db.FolderKey(PRIMARY, "Music/Bob/Second/CD1/"))
        assertThat(lists.links(fileOf(7))!!.albumKey).isNull()
        assertThat(lists.links(999)).isNull()
    }

    /** Replaces the index's rows; a new index version has the sync read them all, as their generations are not newer. */
    private fun resync(vararg rows: io.github.lorddetson.piplayer.library.sync.IndexRow) {
        index.volume().apply {
            this.rows = rows.toList()
            version = "$version+"
        }
        runBlocking { sync.sync() }
    }

    /** Has the tag pass read [mediaStoreId] with [tags]. */
    private fun read(mediaStoreId: Long, tags: Map<String, String>) {
        val dao = database.library()
        val fileId = runBlocking { dao.files() }.single { it.mediaStoreId == mediaStoreId }.id
        runBlocking {
            dao.addTrack(TrackEntity.of(fileId, TrackTags.from(tags.mapValues { listOf(it.value) })))
            dao.markRead(fileId, at = 1, readerVersion = 1, format = null, codec = null, sampleRate = null, bitDepth = null,
                channels = null, bitrate = null, tagEncoding = null, coverPresent = false, coverBytes = null, tagLengthMs = null)
            TrackDisplayWriter(database).refresh(listOf(fileId))
        }
    }

    @Test
    fun theArtistPageKeepsEachAlbumTogetherByItsEarliestYear() {
        resync(
            row(1, "X two", path = "Music/X/", artist = "Kim", album = "X", year = 2005, trackNumber = 2),
            row(2, "X one", path = "Music/X/", artist = "Kim", album = "X", year = 1999, trackNumber = 1),
            row(3, "Y one", path = "Music/Y/", artist = "Kim", album = "Y", year = 2001, trackNumber = 1),
            row(4, "A single", path = "Music/", artist = "Kim", album = null, year = 1995),
            row(5, "X bonus", path = "Music/X/", artist = "Kim", album = "X", year = null, trackNumber = 3),
        )
        val kim = DisplayArtists.keyOf("Kim")

        assertThat(ids(SongQuery.artist(kim))).containsExactly(2L, 1L, 5L, 3L, 4L)
        assertThat(ids(SongQuery.artist(kim).copy(reversed = true))).containsExactly(4L, 3L, 5L, 1L, 2L)
        assertThat(ids(SongQuery.artist(kim).copy(sort = SongSort.TITLE))).containsExactly(4L, 5L, 2L, 1L, 3L)
        assertThat(lists.artistAlbums(kim).map { it.title to it.year }).containsExactly("X" to 1999, "Y" to 2001)
    }

    @Test
    fun aTreeHoldsSubfoldersWhoseNamesHaveCharactersBeyondTheBasicPlane() {
        resync(
            row(1, "Top", path = "Music/\uD83C\uDFB8 Rock/", name = "a.mp3"),
            row(2, "Live", path = "Music/\uD83C\uDFB8 Rock/Live/", name = "b.mp3"),
            row(3, "Other", path = "Music/\uD83C\uDFB8 Rockabilly/", name = "c.mp3"),
        )

        assertThat(ids(SongQuery.tree(PRIMARY, "Music/\uD83C\uDFB8 Rock/"))).containsExactly(1L, 2L)
        assertThat(ids(SongQuery.tree(PRIMARY, "Music/"))).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun anArtistPageListsOnlyAlbumsWithAVisibleSongOfTheArtist() {
        resync(
            row(1, "Intro", path = "Music/Mix/", artist = "Zed", album = "Mix", durationMs = 3_000),
            row(2, "Hit", path = "Music/Mix/", artist = "Other", album = "Mix"),
            row(3, "Own", path = "Music/Zed/", artist = "Zed", album = "Zed's own"),
        )

        assertThat(lists.artistAlbums(DisplayArtists.keyOf("Zed")).map { it.title }).containsExactly("Zed's own")
    }

    @Test
    fun anAlbumCreditComparesArtistsByTheirKeysAndNeedsEverySong() {
        resync(
            row(1, "One", path = "Music/Q/", artist = "Queen", album = "Q"),
            row(2, "Two", path = "Music/Q/", artist = "QUEEN", album = "Q"),
            row(3, "Three", path = "Music/H/", artist = "Half", album = "H"),
            row(4, "Four", path = "Music/H/", artist = null, album = "H", name = "track.mp3"),
            row(5, "Five", path = "Music/N/", artist = null, album = "N", name = "five.mp3"),
        )

        assertThat(lists.albums().single { it.title == "Q" }.credit).isIn(AlbumCredit.Named("Queen"), AlbumCredit.Named("QUEEN"))
        assertThat(lists.albums().single { it.title == "H" }.credit).isEqualTo(AlbumCredit.Various)
        assertThat(lists.albums().single { it.title == "N" }.credit).isEqualTo(AlbumCredit.Unknown)
    }

    @Test
    fun aCompilationIsOneAlbumApartFromTheSameTitleWithoutTheFlag() {
        resync(
            row(1, "C1", path = "Music/Hits/", album = "Hits"),
            row(2, "C2", path = "Music/Hits/", album = "Hits"),
            row(3, "Plain", path = "Music/Hits/", album = "Hits"),
        )
        read(1, mapOf("TITLE" to "C1", "ARTIST" to "Same", "ALBUM" to "Hits", "COMPILATION" to "1"))
        read(2, mapOf("TITLE" to "C2", "ARTIST" to "Same", "ALBUM" to "Hits", "COMPILATION" to "1"))
        read(3, mapOf("TITLE" to "Plain", "ARTIST" to "Same", "ALBUM" to "Hits"))

        val hits = lists.albums().filter { it.title == "Hits" }
        assertThat(hits).hasSize(2)
        val compilation = hits.single { it.songs == 2 }
        assertThat(compilation.credit).isEqualTo(AlbumCredit.Various)
        assertThat(hits.single { it.songs == 1 }.credit).isEqualTo(AlbumCredit.Named("Same"))
        assertThat(ids(SongQuery.album(compilation.key))).containsExactly(1L, 2L)
    }

    @Test
    fun aPageSearchFiltersItsSongs() {
        assertThat(ids(SongQuery.album(second.key).copy(search = "delta"))).containsExactly(4L)
        assertThat(ids(SongQuery.folder(PRIMARY, "Music/Mix/").copy(search = "echo"))).containsExactly(5L)
        assertThat(ids(SongQuery.album(second.key).copy(search = "zzz"))).isEmpty()
    }

    @Test
    fun songsAreFoundByFileIdHiddenOnesToo() {
        val bravo = fileOf(1)
        val ring = fileOf(8)

        val found = lists.songsById(listOf(bravo, ring, 99_999))

        assertThat(found.keys).containsExactlyInAnyOrder(bravo, ring)
        assertThat(found.getValue(bravo).title).isEqualTo("Bravo")
        assertThat(found.getValue(bravo).mediaStoreId).isEqualTo(1)
        assertThat(found.getValue(ring).title).isEqualTo("Ring")
        assertThat(lists.songsById(emptyList())).isEmpty()
    }

    /** The outline of a list is its songs without them: the same rows, sections and texts' bytes (docs/playback.md §10.5). */
    @Test
    fun theOutlineOfAListHoldsItsSongsRowsSectionsAndTextBytes() {
        index.volume().rows = index.volume().rows + listOf(
            row(20, "Ёжик в тумане", path = "Music/Ёж/", artist = "Ёлкин Бор", album = "Ночной ёж", generation = 100),
            row(21, "夜に駆ける 🎵", path = "Music/Ёж/", artist = null, album = "", generation = 101),
        )
        runBlocking { sync.sync() }
        val queries = SongSort.entries.flatMap { sort -> listOf(false, true).map { SongQuery.SONGS.copy(sort = sort, reversed = it) } } +
            listOf(
                SongQuery.album(second.key), SongQuery.artist(lists.artists().first().key), SongQuery.folder(PRIMARY, "Music/Mix/"),
                SongQuery.tree(PRIMARY, "Music/"), SongQuery.SONGS.copy(search = "ёж"), SongQuery.album("nothing"),
            )

        for (query in queries) {
            val songs = lists.songs(query)
            val outline = lists.outline(query)

            assertThat(outline.ids.toList()).describedAs("$query").isEqualTo(songs.map { it.id })
            assertThat(outline.sections.toList()).describedAs("$query").isEqualTo(songs.map { it.section })
            fun bytes(text: String?) = text?.toByteArray(Charsets.UTF_8)?.size ?: 0
            assertThat(outline.titleBytes.toList()).describedAs("$query").isEqualTo(songs.map { bytes(it.title) })
            assertThat(outline.artistBytes.toList()).describedAs("$query").isEqualTo(songs.map { bytes(it.artist) })
            assertThat(outline.albumBytes.toList()).describedAs("$query").isEqualTo(songs.map { bytes(it.album) })
        }
        assertThat(lists.outline(SongQuery.SONGS).size).isGreaterThan(7)
    }
}
