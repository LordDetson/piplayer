package io.github.lorddetson.piplayer.library.songs

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.rules.FolderRule
import io.github.lorddetson.piplayer.library.sync.MediaKinds
import io.github.lorddetson.piplayer.settings.HiddenFiles
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
    private val rules = FakeRules()
    private val sync = LibrarySync(database, index, mayRead = { true }, rules)

    @After
    fun close() {
        sync.close()
        database.close()
    }

    private fun songs(vararg rows: IndexRow, hidden: HiddenFiles = HiddenFiles()): List<Song> {
        index.volume().rows = rows.toList()
        runBlocking { sync.sync() }
        return LibrarySongSource(database, hidden).songs()
    }

    private fun ids(vararg rows: IndexRow, hidden: HiddenFiles = HiddenFiles()) = songs(*rows, hidden = hidden).map { it.id }

    private fun titles(vararg titles: String) =
        songs(*titles.mapIndexed { i, title -> row(i + 1L, title) }.toTypedArray()).map { it.title }

    private val music = row(1, "Alpha")
    private val ringtone = row(2, "Bravo", path = "Ringtones/", kinds = MediaKinds(music = false, ringtone = true))
    private val alarm = row(3, "Charlie", path = "Alarms/", kinds = MediaKinds(music = false, alarm = true))
    private val recording = row(4, "Delta", path = "Recordings/", kinds = MediaKinds(music = false, recording = true))
    private val podcast = row(5, "Echo", path = "Podcasts/", kinds = MediaKinds(music = false, podcast = true))
    private val short = row(6, "Foxtrot", durationMs = 3_000)
    private val unknownLength = row(7, "Golf", durationMs = null)
    private val oldRecording = row(8, "Hotel", path = "Documents/Recordings/", kinds = MediaKinds(music = false, recording = null))
    private val notification = row(9, "India", path = "Notifications/", kinds = MediaKinds(music = false, notification = true))

    @Test
    fun byDefaultSystemSoundsRecordingsAndShortFilesAreHidden() {
        assertThat(ids(music, ringtone, alarm, recording, podcast, short, unknownLength, oldRecording, notification))
            .containsExactlyInAnyOrder(1L, 5L, 7L)
    }

    @Test
    fun eachHiddenKindHasItsOwnSetting() {
        val all = arrayOf(music, ringtone, alarm, recording, podcast, short, unknownLength, oldRecording, notification)

        assertThat(ids(*all, hidden = HiddenFiles(showSystemSounds = true))).containsExactlyInAnyOrder(1L, 2L, 3L, 5L, 7L, 9L)
        assertThat(ids(*all, hidden = HiddenFiles(showRecordings = true))).containsExactlyInAnyOrder(1L, 4L, 5L, 7L, 8L)
        assertThat(ids(*all, hidden = HiddenFiles(minLengthSeconds = 0))).containsExactlyInAnyOrder(1L, 5L, 6L, 7L)
    }

    @Test
    fun aFileJustAtTheLimitIsNotShort() {
        assertThat(ids(row(1, durationMs = 10_000), row(2, durationMs = 9_999))).containsExactly(1L)
    }

    @Test
    fun anExcludedFolderHidesWhatIsInIt() {
        rules.set(FolderRule(FakeMediaIndex.PRIMARY, "Music/Rock/", include = false))

        assertThat(ids(row(1, path = "Music/Rock/"), row(2, path = "Music/Rock/Live/"), row(3, path = "Music/Pop/")))
            .containsExactly(3L)
    }

    @Test
    fun anIncludedFolderShowsEverythingInItEvenWhatIsHiddenElsewhere() {
        rules.set(FolderRule(FakeMediaIndex.PRIMARY, "Recordings/Lectures/", include = true))

        assertThat(
            ids(
                row(1, path = "Recordings/Lectures/", kinds = MediaKinds(music = false, recording = true)),
                row(2, path = "Recordings/Lectures/", durationMs = 3_000),
                row(3, path = "Recordings/", kinds = MediaKinds(music = false, recording = true)),
            ),
        ).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun theFirstSongsFollowTheSameFilter() {
        songs(music, ringtone, short, unknownLength)

        assertThat(LibrarySongSource(database, HiddenFiles()).firstSongs(10).map { it.id }).containsExactly(1L, 7L)
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
