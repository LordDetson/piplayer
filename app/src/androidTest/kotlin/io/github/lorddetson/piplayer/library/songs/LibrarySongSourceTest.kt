package io.github.lorddetson.piplayer.library.songs

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.MediaKinds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The songs of the library database: what is listed, in which order and how (docs/library.md §1.4). */
@RunWith(AndroidJUnit4::class)
class LibrarySongSourceTest {

    private val database = LibraryDatabase.open(TestFiles.targetContext, file = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true })

    @After
    fun close() {
        sync.close()
        database.close()
    }

    private fun songs(vararg rows: IndexRow): List<Song> {
        index.volume().rows = rows.toList()
        runBlocking { sync.sync() }
        return LibrarySongSource(database).songs()
    }

    private fun titles(vararg titles: String) =
        songs(*titles.mapIndexed { i, title -> row(i + 1L, title) }.toTypedArray()).map { it.title }

    @Test
    fun onlyMusicIsListed() {
        val songs = songs(
            row(1),
            row(2, kinds = MediaKinds(music = false, ringtone = true)),
            row(3, kinds = MediaKinds(music = false, recording = true)),
        )

        assertThat(songs.map { it.id }).containsExactly(1L)
    }

    @Test
    fun aSongCarriesTheIndexIdItsContentUriAndWhatListsShow() {
        val song = songs(row(42, title = "Halo", artist = "<unknown>", album = "I Am... Sasha Fierce", durationMs = 0)).single()

        assertThat(song).isEqualTo(
            Song(
                id = 42,
                uri = "content://media/external/audio/media/42",
                title = "Halo",
                artist = null,
                album = "I Am... Sasha Fierce",
                durationMs = null,
            ),
        )
    }

    @Test
    fun theFirstSongsAreTheStartOfTheListInItsOrder() {
        val all = songs(row(1, "Delta"), row(2, "Alpha"), row(3, "Charlie"), row(4, "Bravo"), row(5, "Echo"))

        assertThat(LibrarySongSource(database).firstSongs(3)).containsExactlyElementsOf(all.take(3))
        assertThat(LibrarySongSource(database).firstSongs(10)).containsExactlyElementsOf(all)
    }

    @Test
    fun caseAndDiacriticsDoNotChangeTheOrder() {
        assertThat(titles("beyoncé", "Alpha", "BEYONCE B", "Ángel")).containsExactly("Alpha", "Ángel", "beyoncé", "BEYONCE B")
    }

    @Test
    fun yoSortsAsYe() {
        assertThat(titles("Ёлка", "Ежик", "Жук")).containsExactly("Ежик", "Ёлка", "Жук")
    }

    @Test
    fun digitsComeBeforeLatinAndLatinBeforeCyrillic() {
        assertThat(titles("Звезда", "Zero", "99 Luftballons", "Apple")).containsExactly("99 Luftballons", "Apple", "Zero", "Звезда")
    }

    @Test
    fun punctuationCountsAsASpace() {
        assertThat(titles("AB", "A-C", "A B")).containsExactly("A B", "A-C", "AB")
    }

    @Test
    fun equalTitlesKeepTheOrderOfTheirIndexIds() {
        val songs = songs(row(9, "Same"), row(3, "same"), row(5, "SAME"))

        assertThat(songs.map { it.id }).containsExactly(3L, 5L, 9L)
    }

    @Test
    fun lettersOutsideTheBasicPlaneSortByCodePoint() {
        // A Han letter of Extension B (U+20000) against a halfwidth katakana letter (U+FF71), both kept as they are by
        // the normalization: UTF-16 would put the surrogate pair first, code points put it last.
        assertThat(titles("𠀀", "ｱ")).containsExactly("ｱ", "𠀀")
    }
}
