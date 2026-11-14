package io.github.lorddetson.piplayer.library.lists

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
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

/** The Genres tab and the genre pages on the library database (docs/library.md §19). */
@RunWith(AndroidJUnit4::class)
class GenreListsTest {

    private val database = LibraryDatabase.open(TestFiles.targetContext, file = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true }, FakeRules())
    private val lists = LibraryLists(database, HiddenFiles())
    private val dao = database.library()

    @Before
    fun fill() {
        index.volume().rows = listOf(
            row(1, "Alpha", artist = "Ann", genre = "Rock; Pop", durationMs = 200_000),
            row(2, "Bravo", artist = "Bob", genre = "rock", durationMs = 100_000),
            row(3, "Charlie", artist = "Cid", genre = "Hip-Hop", durationMs = 300_000),
            row(4, "Delta", artist = "Dee", genre = "Hip Hop", durationMs = 50_000),
            row(5, "Echo", artist = "Eve", genre = null),
            row(6, "Foxtrot", artist = "Fay", genre = "Unknown genre"),
            row(7, "Golf", artist = "Gus", genre = "<unknown>"),
            row(8, "Ring", path = "Ringtones/", artist = "Sys", genre = "Rock", kinds = MediaKinds(music = false, ringtone = true)),
            row(9, "Short", artist = "Shorty", genre = "Jazz", durationMs = 3_000),
            // Two files whose audio is byte for byte the same: one identity, two songs.
            row(10, "Hotel", path = "Music/A/", name = "hotel.mp3", artist = "Hal", genre = "Ambient"),
            row(11, "Hotel", path = "Music/B/", name = "hotel.mp3", artist = "Hal", genre = "Ambient"),
        )
        runBlocking {
            sync.sync()
            for (id in listOf(10L, 11L)) dao.setContent(fileOf(id), "same audio", 60_000, "frames")
        }
    }

    @After
    fun close() {
        sync.close()
        database.close()
    }

    private fun fileOf(mediaStoreId: Long) = runBlocking { dao.files() }.single { it.mediaStoreId == mediaStoreId }.id

    private fun ids(query: SongQuery) = lists.songs(query).map { it.mediaStoreId }

    private fun key(name: String) = GenreKeys.of(name)

    /** Has the tag pass read [mediaStoreId] with [genres] in its tags. */
    private fun read(mediaStoreId: Long, vararg genres: String) {
        val fileId = fileOf(mediaStoreId)
        val tags = TrackTags.from(mapOf("TITLE" to listOf("Song $mediaStoreId"), "GENRE" to genres.toList()))
        runBlocking {
            dao.deleteTracks(listOf(fileId))
            dao.addTrack(TrackEntity.of(fileId, tags))
            dao.markRead(fileId, at = 1, readerVersion = 1, format = null, codec = null, sampleRate = null, bitDepth = null,
                channels = null, bitrate = null, tagEncoding = null, coverPresent = false, coverBytes = null, tagLengthMs = null)
            TrackDisplayWriter(database).refresh(listOf(fileId))
        }
    }

    @Test
    fun genresComeFromTheIndexWithTheirVisibleSongsAndTheSongsWithoutOneLast() {
        val genres = lists.genres()

        // "Hip Hop" is the lowest spelling in code point order; Jazz has only a short file, Rock's ringtone is hidden.
        assertThat(genres.map { it.name }).containsExactly("Ambient", "Hip Hop", "Pop", "Rock", "Unknown genre", "")
        assertThat(genres.map { it.songs }).containsExactly(2, 2, 1, 2, 1, 2)
        assertThat(genres.last().key).isEqualTo(GenreKeys.NONE)
        assertThat(genres[4].key).isEqualTo(key("Unknown genre")).isNotEqualTo(GenreKeys.NONE)
        assertThat(genres.map { it.key }).doesNotHaveDuplicates()
    }

    @Test
    fun aSongOfTwoGenresIsInBothAndTwoIdenticalFilesCountTwice() {
        assertThat(ids(SongQuery.genre(key("rock")))).containsExactly(1L, 2L)
        assertThat(ids(SongQuery.genre(key("pop")))).containsExactly(1L)
        assertThat(ids(SongQuery.genre(key("Hip-Hop")))).containsExactly(3L, 4L)
        assertThat(lists.genre(key("ambient"))?.songs).isEqualTo(2)
        assertThat(ids(SongQuery.genre(key("ambient")))).containsExactly(10L, 11L)
    }

    @Test
    fun theSongsWithoutAGenreHaveTheirOwnKeyAndPage() {
        assertThat(ids(SongQuery.genre(GenreKeys.NONE))).containsExactly(5L, 7L)
        assertThat(lists.genre(GenreKeys.NONE)?.songs).isEqualTo(2)
        assertThat(lists.genre(GenreKeys.NONE)?.unknown).isTrue()
        assertThat(ids(SongQuery.genre(key("Unknown genre")))).containsExactly(6L)
    }

    @Test
    fun hiddenFilesCountNowhere() {
        assertThat(lists.genre(key("jazz"))).isNull()
        assertThat(ids(SongQuery.genre(key("rock")))).doesNotContain(8L)

        val shown = LibraryLists(database, HiddenFiles(showSystemSounds = true, minLengthSeconds = 0))
        assertThat(shown.genre(key("rock"))?.songs).isEqualTo(3)
        assertThat(shown.genre(key("jazz"))?.songs).isEqualTo(1)
        assertThat(shown.songs(SongQuery.genre(key("rock"))).map { it.mediaStoreId }).containsExactly(1L, 2L, 8L)
    }

    @Test
    fun genresSortByNameOrSongsBothWaysAndTheSongsWithoutOneStayLast() {
        assertThat(lists.genres(GenreSort.NAME, reversed = true).map { it.name }).containsExactly("Unknown genre", "Rock", "Pop", "Hip Hop", "Ambient", "")
        val bySongs = lists.genres(GenreSort.SONGS)
        assertThat(bySongs.map { it.songs }).containsExactly(2, 2, 2, 1, 1, 2)
        assertThat(bySongs.map { it.name }.take(3)).containsExactly("Ambient", "Hip Hop", "Rock")
        assertThat(bySongs.map { it.section }.dropLast(1)).containsExactly("2", "2", "2", "1", "1")
        assertThat(lists.genres(GenreSort.SONGS, reversed = true).map { it.name }.last()).isEmpty()
    }

    @Test
    fun theSearchFindsGenresByNameAndLeavesTheSongsWithoutOneOut() {
        assertThat(lists.genres(search = "HIP").map { it.name }).containsExactly("Hip Hop")
        assertThat(lists.genres(search = "hiphop").map { it.name }).containsExactly("Hip Hop")
        assertThat(lists.genres(search = "unknown").map { it.key }).containsExactly(key("Unknown genre"))
        assertThat(lists.genres(search = "zzz")).isEmpty()
    }

    @Test
    fun aGenrePageListsItsSongsInEveryOrderWithItsSearch() {
        val rock = SongQuery.genre(key("rock"))
        assertThat(rock.sort).isEqualTo(SongSort.ARTIST)
        assertThat(ids(rock)).containsExactly(1L, 2L)
        assertThat(ids(rock.copy(sort = SongSort.LENGTH))).containsExactly(2L, 1L)
        assertThat(ids(rock.copy(sort = SongSort.TITLE, reversed = true))).containsExactly(2L, 1L)
        assertThat(ids(rock.copy(search = "bravo"))).containsExactly(2L)
        for (sort in SongSort.entries) {
            assertThat(ids(rock.copy(sort = sort, reversed = true))).describedAs("$sort").isEqualTo(ids(rock.copy(sort = sort)).reversed())
        }
    }

    @Test
    fun theOutlineOfAGenreIsItsSongs() {
        for (query in listOf(SongQuery.genre(key("rock")), SongQuery.genre(GenreKeys.NONE), SongQuery.genre(key("ambient")).copy(sort = SongSort.TITLE), SongQuery.genre("nothing"))) {
            val songs = lists.songs(query)
            val outline = lists.outline(query)
            assertThat(outline.ids.toList()).describedAs("$query").isEqualTo(songs.map { it.id })
            assertThat(outline.sections.toList()).describedAs("$query").isEqualTo(songs.map { it.section })
        }
    }

    @Test
    fun aKeyThatNamesNothingIsNotFound() {
        assertThat(lists.genre("nothing")).isNull()
        assertThat(ids(SongQuery.genre("nothing"))).isEmpty()
    }

    @Test
    fun afterTheReadTheTagsGenresCountAndTheIndexsNoLonger() {
        read(1, "Jazz", "jazz")
        read(2)

        assertThat(lists.genre(key("pop"))).isNull()
        assertThat(ids(SongQuery.genre(key("rock")))).isEmpty()
        assertThat(lists.genre(key("jazz"))?.songs).isEqualTo(1)
        // A read file without a genre tag has none, whatever the index says; read without an artist, it sorts last.
        assertThat(ids(SongQuery.genre(GenreKeys.NONE))).containsExactly(5L, 7L, 2L)
        assertThat(lists.genreKeys(fileOf(1))).containsExactly(key("jazz"))
        assertThat(runBlocking { dao.displayGenres(fileOf(1)) }.map { it.name }).containsExactly("Jazz")
    }

    @Test
    fun aFileReadAgainWithFewerGenresKeepsNoStaleRow() {
        read(3, "Hip-Hop", "Trap", "Grime")
        assertThat(lists.genreKeys(fileOf(3))).containsExactly(key("Hip-Hop"), key("Trap"), key("Grime"))

        read(3, "Grime")

        assertThat(runBlocking { dao.displayGenres(fileOf(3)) }.map { it.name }).containsExactly("Grime")
        assertThat(lists.genre(key("trap"))).isNull()
    }
}
