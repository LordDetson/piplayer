package io.github.lorddetson.piplayer.library

import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.TimingTest
import io.github.lorddetson.piplayer.library.search.LibrarySearchSpikeTest
import io.github.lorddetson.piplayer.library.lists.FolderLevels
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.library.songs.LibrarySongSource
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.LibraryTree
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The checks of docs/library.md §1.7 on the generated library (scripts/generate_library.py) in `Music/PiPlayerTest20k/`;
 * skipped on devices without it. Each test starts from an empty library database, as a first launch does.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GeneratedLibraryTest {

    private val context = TestFiles.targetContext
    private val library = LibraryRule()
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(library).around(compose)

    private val emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

    /**
     * docs/library.md §1.7 sets the time limits for the owner's phone and the API 30 emulator. Newer emulator images
     * take longer than the limit to show any activity at all, so there the times are only logged.
     */
    private fun assumeTimeLimitsApply() =
        assumeTrue("Time limits apply to a phone and the API 30 emulator", !emulator || Build.VERSION.SDK_INT == 30)

    @Before
    fun needsTheGeneratedLibrary() {
        val count = context.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$ROOT%"),
            null,
        )?.use { it.count } ?: 0
        assumeTrue("The generated library is not on this device ($count files in $ROOT)", count >= FILES)
    }

    private fun since(start: Long): Duration = (System.nanoTime() - start).nanoseconds

    private fun waitForTheFirstPage() =
        compose.waitUntilAtLeastOneExists(hasAnyAncestor(hasTestTag(SongsTags.LIST)), timeoutMillis = 30_000)

    @Test
    @TimingTest
    fun theFirstLaunchListsTheLibraryWithinSecondsAndSyncsItWithinTen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForTheFirstPage()
            val firstPage = since(library.startedAt)
            runBlocking { Library.sync(context).awaitIdle() }
            val fullSync = since(library.startedAt)
            Log.i(TAG, "First launch: first page after $firstPage, whole sync after $fullSync")
            assumeTimeLimitsApply()

            assertThat(firstPage.inWholeMilliseconds).describedAs("first page").isLessThanOrEqualTo(if (emulator) 4_000L else 3_000L)
            assertThat(fullSync.inWholeMilliseconds).describedAs("whole sync").isLessThanOrEqualTo(10_000L)
        }
    }

    @Test
    fun theGeneratedFilesArriveWithTheirValues() {
        runBlocking { Library.sync(context).awaitIdle() }
        val dao = Library.database(context).library()
        val files = runBlocking { dao.files() }.filter { it.relativePath.startsWith(ROOT) }
        val displays = runBlocking { dao.displays() }.associateBy { it.fileId }
        val byName = files.associateBy { it.relativePath + it.displayName }
        fun display(path: String) = displays.getValue(byName.getValue(ROOT + path).id)

        assertThat(files).hasSizeGreaterThanOrEqualTo(FILES)
        // The default limit of 10 s hides its 100 files of 3 s, and the VBR MP3 files without a frame-count header whose
        // length the index estimates under 10 s, until the tag pass measures them (docs/library.md §3.5).
        val listed = Library.read(context) { LibrarySongSource(it).songs() }.map { it.mediaStoreId }.toSet()
        val hidden = files.filter { it.mediaStoreId !in listed }.map { displays.getValue(it.id).durationMs }
        assertThat(hidden).allSatisfy { assertThat(it).isNotNull().isLessThan(10_000L) }
        assertThat(hidden.count { it!! <= 3_500L }).isEqualTo(100)
        with(display("Ёлкин Бор/Ночной ёж/01 Ёжик в тумане.mp3")) {
            assertThat(title).isEqualTo("Ёжик в тумане")
            assertThat(artist).isEqualTo("Ёлкин Бор")
            assertThat(album).isEqualTo("Ночной ёж")
        }
        with(display("Orchestra Nova/Grand Suite/CD2/01 Finale.flac")) {
            assertThat(title).isEqualTo("Finale")
            assertThat(album).isEqualTo("Grand Suite")
        }
        with(display("Various Artists/Сборник 2026/03 Lune de miel.m4a")) {
            assertThat(title).isEqualTo("Lune de miel")
            assertThat(artist).isEqualTo("Zoë Lune")
            assertThat(album).isEqualTo("Сборник 2026")
        }
        with(display("Без тегов/track 0001.mp3")) {
            assertThat(title).isEqualTo("track 0001")
            assertThat(artist).isNull()
        }
    }

    @Test
    @TimingTest
    fun aLaterLaunchListsTheSyncedLibraryWithinASecond() {
        runBlocking { Library.sync(context).awaitIdle() }

        // The next process: the database as the first launch left it, a new sync, a new screen.
        runBlocking { Library.reopen(context) }
        SongList.forget()
        val start = System.nanoTime()
        LibraryRule.startAsANewProcess()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForTheFirstPage()
            val firstPage = since(start)
            Log.i(TAG, "Later launch: first page after $firstPage")
            assumeTimeLimitsApply()

            assertThat(firstPage.inWholeMilliseconds).describedAs("first page").isLessThanOrEqualTo(1_000L)
        }
    }

    @Test
    @TimingTest
    fun searchAsYouTypeOnTheRealSchemaAnswersWithinFiftyMilliseconds() {
        runBlocking { Library.sync(context).awaitIdle() }
        val database = Library.database(context)

        val found = mutableMapOf<String, Int>()
        val lists = LibraryLists(database, HiddenFiles())
        val p95 = LibrarySearchSpikeTest.QUERIES.associateWith { query ->
            // The query the Songs tab runs (docs/library.md §6.3).
            fun search() = lists.songs(SongQuery.SONGS.copy(search = query), limit = PAGE).size
            found[query] = search()
            repeat(WARM_UP) { search() }
            val times = List(RUNS) { measureTime { search() } }.sorted()
            times[(RUNS * 95 + 99) / 100 - 1].also { Log.i(TAG, "Search '$query': p95 $it") }
        }

        // The generated names are built from syllables such as "ка", and never contain "zzzq".
        assertThat(found["ка"]).describedAs("rows found for 'ка'").isEqualTo(PAGE)
        assertThat(found["zzzq"]).describedAs("rows found for 'zzzq'").isZero()
        assertThat(p95.values.max().inWholeMilliseconds).describedAs("slowest p95").isLessThanOrEqualTo(50L)
    }

    /** The tabs and a page on 20 000 songs; no limit is set for them, so the times are logged (docs/library.md §6.6). */
    @Test
    @TimingTest
    fun theTabsReadTheGeneratedLibrary() {
        runBlocking { Library.sync(context).awaitIdle() }
        val lists = LibraryLists(Library.database(context), HiddenFiles())
        fun <T> timed(name: String, read: () -> T): T {
            repeat(WARM_UP) { read() }
            val times = List(5) { measureTime { read() } }.sorted()
            Log.i(TAG, "$name: median ${times[2]}")
            return read()
        }

        val albums = timed("Albums tab") { lists.albums() }
        val artists = timed("Artists tab") { lists.artists() }
        val folders = timed("Folder levels") { lists.folders() }
        timed("Album search 'ка'") { lists.albums(search = "ка") }
        timed("Songs by artist, first page") { lists.songs(SongQuery.SONGS.copy(sort = SongSort.ARTIST), limit = PAGE) }
        timed("Songs by artist, whole list") { lists.songs(SongQuery.SONGS.copy(sort = SongSort.ARTIST)) }
        timed("Tree of the generated library") { lists.songs(SongQuery.tree(FolderLevels.PRIMARY, ROOT)) }
        timed("An artist page") { lists.songs(SongQuery.artist(artists.first().key)) }

        assertThat(albums.size).describedAs("albums").isGreaterThan(1_000)
        assertThat(artists.size).describedAs("artists").isGreaterThan(300)
        assertThat(folders.contains(FolderLevels.PRIMARY, ROOT)).isTrue()
    }

    /**
     * The controller tree's Songs root and one of its buckets (docs/playback.md §10.5), each read by a new tree with no
     * list kept, as a controller's first browse after a start finds them; a bucket read right after its root is logged too.
     */
    @Test
    @TimingTest
    fun theControllerTreeAnswersTheSongsRootAndABucketWithin200MillisecondsOnThePhone() {
        runBlocking { Library.sync(context).awaitIdle() }
        // As the service builds it, with a cover URI for every song (docs/library.md §13.8).
        fun newTree(): LibraryTree {
            SongList.forget()
            return LibraryTree(context, io.github.lorddetson.piplayer.playback.ProviderArtwork(context))
        }
        fun median(name: String, read: () -> Unit): Duration {
            repeat(WARM_UP) { read() }
            val times = List(5) { measureTime { read() } }.sorted()
            Log.i(TAG, "$name: median ${times[2]} (${times.joinToString()})")
            return times[2]
        }

        val buckets = newTree().children("songs", 0, Int.MAX_VALUE)!!
        val bucket = buckets[buckets.size / 2].mediaId
        val root = median("Tree: Songs root") {
            assertThat(newTree().children("songs", 0, Int.MAX_VALUE)!!.size).isBetween(2, LibraryTree.MAX_CHILDREN)
        }
        val coldBucket = median("Tree: a bucket of Songs") {
            assertThat(newTree().children(bucket, 0, Int.MAX_VALUE)!!.size).isBetween(1, LibraryTree.MAX_CHILDREN)
        }
        val tree = newTree()
        tree.children("songs", 0, Int.MAX_VALUE)
        median("Tree: a bucket of Songs after its root") { tree.children(bucket, 0, Int.MAX_VALUE) }
        assumeTimeLimitsApply()

        // The phone is the reference; the emulator's sort of 20 000 rows alone takes most of its margin (§10.5).
        val limit = if (emulator) 300L else 200L
        assertThat(root.inWholeMilliseconds).describedAs("Songs root").isLessThanOrEqualTo(limit)
        assertThat(coldBucket.inWholeMilliseconds).describedAs("a bucket").isLessThanOrEqualTo(limit)
    }

    private companion object {
        const val TAG = "GeneratedLibrary"
        const val ROOT = "Music/PiPlayerTest20k/"
        const val FILES = 20_000
        const val PAGE = 50
        const val WARM_UP = 3
        const val RUNS = 20
    }
}
