package io.github.lorddetson.piplayer.library.lists

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.sort.SortKeys
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.settings.HiddenFiles
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** The lists in the order of the app's language, with sort tags and the sections of the scroll thumb (docs/library.md §11). */
@RunWith(AndroidJUnit4::class)
class LibrarySortingTest {

    private val database = LibraryDatabase.open(TestFiles.targetContext, file = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true }, FakeRules())
    private val lists = LibraryLists(database, HiddenFiles())
    private val appLanguages = TestFiles.targetContext.resources.configuration.locales.let { list -> (0 until list.size()).map { list[it] } }
    private val russian = listOf(Locale.forLanguageTag("ru"))

    @Before
    fun fill() {
        SortKeys.use(russian)
        index.volume().rows = listOf(
            row(1, "Zebra", path = "Music/1/", artist = "Cher", album = "Zeta", year = 1990, durationMs = 200_000),
            row(2, "Ёжик", path = "Music/2/", artist = "Ёлка", album = "Ёлочка", year = 2001, durationMs = 61_000),
            row(3, "Ежевика", path = "Music/3/", artist = "Ария", album = "Альбом", year = 2005, durationMs = 30_000),
            row(4, "Арбуз", path = "Music/4/", artist = "The Beatles", album = "Abbey Road", year = 1969, durationMs = 125_000),
            row(5, "10 Years", path = "Music/5/", artist = "Abba", album = "Gold", year = 1992, durationMs = 59_000),
            row(6, "2 Unlimited", path = "Music/6/", artist = "2 Unlimited", album = "Get Ready", year = 1992, durationMs = null),
            row(7, "(I Can't Get No) Satisfaction", path = "Music/7/", artist = "Rolling Stones", album = "Out of Our Heads", year = 1965, durationMs = 223_000),
        )
        runBlocking { sync.sync() }
    }

    @After
    fun close() {
        sync.close()
        database.close()
        SortKeys.use(appLanguages)
    }

    private fun songs(sort: SongSort, reversed: Boolean = false) = lists.songs(SongQuery.SONGS.copy(sort = sort, reversed = reversed))

    private fun ids(sort: SongSort) = songs(sort).map { it.mediaStoreId }

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
    fun songsSortByTitleInRussianOrderWithTheirLetters() {
        val byTitle = songs(SongSort.TITLE)

        assertThat(byTitle.map { it.mediaStoreId }).containsExactly(6L, 5L, 4L, 3L, 2L, 7L, 1L)
        assertThat(byTitle.map { it.section }).containsExactly("#", "#", "А", "Е", "Е", "I", "Z")
        assertThat(songs(SongSort.TITLE, reversed = true).map { it.section }).isEqualTo(byTitle.map { it.section }.reversed())
    }

    @Test
    fun songsByLengthShowTheWholeMinuteAndAMissingLengthLast() {
        val byLength = songs(SongSort.LENGTH)

        assertThat(byLength.map { it.mediaStoreId }).containsExactly(3L, 5L, 2L, 4L, 1L, 7L, 6L)
        assertThat(byLength.map { it.section }).containsExactly("0:00", "0:00", "1:00", "2:00", "3:00", "3:00", "?")
    }

    @Test
    fun anArtistsSortTagCountsOnceTheFileIsRead() {
        assertThat(ids(SongSort.ARTIST)).containsExactly(6L, 3L, 2L, 5L, 1L, 7L, 4L)
        assertThat(songs(SongSort.ARTIST).map { it.section }).containsExactly("#", "А", "Е", "A", "C", "R", "T")

        read(4, mapOf("TITLE" to "Арбуз", "ARTIST" to "The Beatles", "ARTISTSORT" to "Beatles, The", "ALBUM" to "Abbey Road"))

        assertThat(ids(SongSort.ARTIST)).containsExactly(6L, 3L, 2L, 5L, 4L, 1L, 7L)
        assertThat(songs(SongSort.ARTIST).map { it.section }).containsExactly("#", "А", "Е", "A", "B", "C", "R")
        val artists = lists.artists()
        assertThat(artists.map { it.name }).containsExactly("2 Unlimited", "Ария", "Ёлка", "Abba", "The Beatles", "Cher", "Rolling Stones")
        assertThat(artists.map { it.section }).containsExactly("#", "А", "Е", "A", "B", "C", "R")
    }

    @Test
    fun aTitlesSortTagCountsOnceTheFileIsRead() {
        read(1, mapOf("TITLE" to "Zebra", "TITLESORT" to "Aardvark", "ARTIST" to "Cher", "ALBUM" to "Zeta"))

        assertThat(ids(SongSort.TITLE)).containsExactly(6L, 5L, 4L, 3L, 2L, 1L, 7L)
        assertThat(songs(SongSort.TITLE).map { it.section }).containsExactly("#", "#", "А", "Е", "Е", "A", "I")
    }

    @Test
    fun albumsSortByTheirKeysWithLettersOrYears() {
        val byTitle = lists.albums(AlbumSort.TITLE)
        assertThat(byTitle.map { it.title }).containsExactly("Альбом", "Ёлочка", "Abbey Road", "Get Ready", "Gold", "Out of Our Heads", "Zeta")
        assertThat(byTitle.map { it.section }).containsExactly("А", "Е", "A", "G", "G", "O", "Z")

        val byYear = lists.albums(AlbumSort.YEAR)
        assertThat(byYear.map { it.title }).containsExactly("Out of Our Heads", "Abbey Road", "Zeta", "Get Ready", "Gold", "Ёлочка", "Альбом")
        assertThat(byYear.map { it.section }).containsExactly("1965", "1969", "1990", "1992", "1992", "2001", "2005")

        val byArtist = lists.albums(AlbumSort.ARTIST)
        assertThat(byArtist.map { it.title }).containsExactly("Get Ready", "Альбом", "Ёлочка", "Gold", "Zeta", "Out of Our Heads", "Abbey Road")
        assertThat(byArtist.map { it.section }).containsExactly("#", "А", "Е", "A", "C", "R", "T")
    }

    @Test
    fun artistsByNumberOfSongsShowTheNumber() {
        resync(row(8, "Second", path = "Music/8/", artist = "Cher", album = "Zeta", year = 1990))

        val bySongs = lists.artists(ArtistSort.SONGS)

        assertThat(bySongs.first().name).isEqualTo("Cher")
        assertThat(bySongs.map { it.section }).containsExactly("2", "1", "1", "1", "1", "1", "1")
    }

    @Test
    fun anAlbumWhoseSongsSpellItsTitleDifferentlyStaysTogetherInTrackOrder() {
        index.volume().apply {
            rows = listOf(
                row(11, "One", path = "Music/GH/", artist = "Kim", album = "Greatest Hits", year = 1999, trackNumber = 1),
                row(12, "Two", path = "Music/GH/", artist = "Kim", album = "Greatest Hits", year = 1999, trackNumber = 2),
                row(13, "Three", path = "Music/GH/", artist = "Kim", album = "Greatest hits", year = 1999, trackNumber = 3),
                row(14, "Other", path = "Music/Other/", artist = "Kim", album = "Greatest Hitz", year = 1999, trackNumber = 1),
            )
            version = "$version+"
        }
        runBlocking { sync.sync() }

        assertThat(ids(SongSort.ALBUM)).containsExactly(11L, 12L, 13L, 14L)
        assertThat(ids(SongSort.YEAR)).containsExactly(11L, 12L, 13L, 14L)
        assertThat(songs(SongSort.ALBUM).map { it.section }).containsOnly("G")
    }

    @Test
    fun anAlbumPageByDiscAndTrackHasNoSections() {
        val album = lists.albums().single { it.title == "Zeta" }

        assertThat(lists.songs(SongQuery.album(album.key)).map { it.section }).containsOnlyNulls()
    }

    @Test
    fun aSyncUnderAnotherCollationWritesEveryKeyAgainAndUnderTheSameOneNone() {
        SortKeys.use(listOf(Locale.ENGLISH))
        assertThat(runBlocking { sync.sync() }).isTrue()

        assertThat(ids(SongSort.TITLE)).containsExactly(6L, 5L, 7L, 1L, 4L, 3L, 2L)
        assertThat(songs(SongSort.TITLE).map { it.section }).containsExactly("#", "#", "I", "Z", "…", "…", "…")
        assertThat(runBlocking { sync.sync() }).isFalse()

        SortKeys.use(russian)
        assertThat(runBlocking { sync.sync() }).isTrue()
        assertThat(ids(SongSort.TITLE)).containsExactly(6L, 5L, 4L, 3L, 2L, 7L, 1L)
    }

    /** Adds [extra] to the index's rows under a new index version, so that the sync reads them all. */
    private fun resync(vararg extra: io.github.lorddetson.piplayer.library.sync.IndexRow) {
        index.volume().apply {
            rows = rows + extra
            version = "$version+"
        }
        runBlocking { sync.sync() }
    }
}
