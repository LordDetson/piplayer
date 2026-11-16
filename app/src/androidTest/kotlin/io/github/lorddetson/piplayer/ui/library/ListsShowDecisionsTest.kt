package io.github.lorddetson.piplayer.ui.library

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.lists.ArtistGroup
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.throughout
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.playlists.PlaylistPageViewModel
import io.github.lorddetson.piplayer.ui.songs.SongsState
import io.github.lorddetson.piplayer.ui.songs.SongsViewModel
import io.github.lorddetson.piplayer.userdata.DecisionFixtures
import io.github.lorddetson.piplayer.userdata.UserData
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The app's lists after Apply and Undo (TI §7.1 "Merged values everywhere", docs/library.md §1.4, §3.5): the Songs tab,
 * an album page's songs, the Artists tab and a playlist page, each through its own holder as the screens use it, over the
 * library database filled from a test index and the real decision mirror. A decision goes through `LibraryDecisions`, as
 * the review screen sends it, and no sync runs in between.
 *
 * Alpha and Bravo make the album First, so identifying Alpha alone keeps the album's key (docs/library.md §6.2) and its
 * page; Alpha's artist Ann has no other song, so the Artists tab shows the decided artist in her place.
 */
@RunWith(AndroidJUnit4::class)
class ListsShowDecisionsTest {

    private val context = TestFiles.targetContext
    private val app = context.applicationContext as Application
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", path = "Music/First/", name = "01 Alpha.mp3", artist = "Ann", album = "First", trackNumber = 1),
            row(2, "Bravo", path = "Music/First/", name = "02 Bravo.mp3", artist = "Bob", album = "First", trackNumber = 2),
            row(3, "Charlie", path = "Music/Third/", name = "01 Charlie.mp3", artist = "Cid", album = "Third"),
        )
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private val store = ViewModelStore()
    private val collecting = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var alpha = 0L
    private var bravo = 0L
    private lateinit var albumKey: String

    @Before
    fun prepare() = runBlocking {
        Library.sync(context).awaitIdle()
        val dao = Library.database(context).library()
        alpha = dao.files().single { it.mediaStoreId == 1L }.id
        bravo = dao.files().single { it.mediaStoreId == 2L }.id
        dao.setContent(alpha, KEY, 200_000, DurationSource.HEADER)
        Library.identities(context).attach()
        Library.decisionMirror(context).awaitIdle()
        albumKey = Library.read(context) { LibraryLists(it).albums() }.single { it.title == "First" }.key
    }

    @After
    fun release() {
        collecting.cancel()
        onMain { store.clear() }
    }

    /** A holder kept in this test's store, as a screen keeps its own, so that it is cleared after the test. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : ViewModel> held(key: String, make: () -> T): T = onMain {
        val factory = object : ViewModelProvider.Factory {
            override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
        }
        ViewModelProvider(store, factory)[key, ViewModel::class.java] as T
    }

    private fun songs(base: SongQuery = SongQuery.SONGS, list: ListName = ListName.SONGS) = held("songs ${base.kind} ${base.key}") {
        SongsViewModel(app, base, list).apply { onStart(granted = true, deniedForGood = false) }
    }

    private fun SongsViewModel.titles(): List<String>? = (state.value as? SongsState.Loaded)?.takeIf { it.complete }?.songs?.map { it.title }

    private fun ArtistsViewModel.names(): List<String>? = (state.value as? GroupsState.Loaded<List<ArtistGroup>>)?.value?.map { it.name }

    @Test
    fun theListsShowADecisionAtOnceAndTheTagsAgainAfterUndo() = runBlocking<Unit> {
        val playlist = UserData.playlists(context).create("Mix")
        Library.playlists(context).addFiles(playlist, listOf(bravo, alpha))
        val songs = songs()
        val album = songs(SongQuery.album(albumKey), ListName.ALBUM_PAGE)
        val artists = held("artists") { ArtistsViewModel(app).apply { onStart(granted = true) } }
        val page = held("playlist") { PlaylistPageViewModel(app, playlist) }
        collecting.launch { page.items.collect {} }
        fun playlistTitles() = page.items.value?.map { it.title }
        eventually("the lists are read", 20.seconds) {
            songs.titles() == listOf("Alpha", "Bravo", "Charlie") && album.titles() == listOf("Alpha", "Bravo") &&
                artists.names() == listOf("Ann", "Bob", "Cid") && playlistTitles() == listOf("Bravo", "Alpha")
        }
        val revision = Library.revision()
        val reads = index.rowReads

        val applied = checkNotNull(
            Library.decisions(context).apply(
                DecisionFixtures.request(KEY, DecisionFixtures.details(title = "Zulu", artist = "Zed", album = "Record"), fileId = alpha),
            ),
        )

        // The Songs tab sorts by the decided title, so the song moves to the end.
        eventually("the Songs tab shows the decided title") { songs.titles() == listOf("Bravo", "Charlie", "Zulu") }
        eventually("the album page shows the decided title") { album.titles()?.toSet() == setOf("Bravo", "Zulu") }
        eventually("the Artists tab shows the decided artist") { artists.names() == listOf("Bob", "Cid", "Zed") }
        eventually("the playlist page shows the decided title") { playlistTitles() == listOf("Bravo", "Zulu") }
        assertThat(SongList.current()!!.map { it.title }).describedAs("the list kept for the service").containsExactly("Bravo", "Charlie", "Zulu")

        checkNotNull(Library.decisions(context).undo(applied.decisionId))

        eventually("the Songs tab shows the tag's title again") { songs.titles() == listOf("Alpha", "Bravo", "Charlie") }
        eventually("the album page shows the tag's title again") { album.titles() == listOf("Alpha", "Bravo") }
        eventually("the Artists tab shows the tag's artist again") { artists.names() == listOf("Ann", "Bob", "Cid") }
        eventually("the playlist page shows the tag's title again") { playlistTitles() == listOf("Bravo", "Alpha") }
        assertThat(SongList.current()!!.map { it.title }).describedAs("the list kept for the service").containsExactly("Alpha", "Bravo", "Charlie")
        assertThat(Library.revision()).describedAs("no sync changed the library").isEqualTo(revision)
        assertThat(index.rowReads).describedAs("no sync read the index").isEqualTo(reads)
    }

    @Test
    fun aListKeptForTheServiceIsNotHandedOutAfterADecision() = runBlocking<Unit> {
        assertThat(SongList.load(context).map { it.title }).containsExactly("Alpha", "Bravo", "Charlie")
        assertThat(SongList.current()).isNotNull()

        val applied = checkNotNull(Library.decisions(context).apply(DecisionFixtures.request(KEY, DecisionFixtures.details(title = "Zulu"), fileId = alpha)))

        assertThat(SongList.current()).describedAs("the list read before Apply").isNull()
        assertThat(SongList.load(context).map { it.title }).containsExactly("Bravo", "Charlie", "Zulu")
        assertThat(SongList.current()).isNotNull()

        checkNotNull(Library.decisions(context).undo(applied.decisionId))

        assertThat(SongList.current()).describedAs("the list read before Undo").isNull()
        assertThat(SongList.load(context).map { it.title }).containsExactly("Alpha", "Bravo", "Charlie")
    }

    @Test
    fun aRunOfTheDecisionMirrorThatChangedNothingReadsNoListAgain() = runBlocking<Unit> {
        checkNotNull(Library.decisions(context).apply(DecisionFixtures.request(KEY, DecisionFixtures.details(title = "Zulu"), fileId = alpha)))
        val songs = songs()
        val changes = AtomicInteger()
        val smartChanges = AtomicInteger()
        collecting.launch { SongList.changes(context).collect { changes.incrementAndGet() } }
        // Songs missing information, which a decision can take a song off (docs/library.md §17.4).
        collecting.launch { SongList.changes(context, SongQuery.missingInfo()).collect { smartChanges.incrementAndGet() } }
        eventually("the list shows the decision", 20.seconds) { songs.titles() == listOf("Bravo", "Charlie", "Zulu") }
        eventually("the changes are watched") { changes.get() > 0 && smartChanges.get() > 0 }
        // The start's attach pass and the mirror of favorites and plays after it, whose run also reads smart lists again.
        Library.identities(context).awaitIdle()
        Library.mirror(context).awaitIdle()
        Library.decisionMirror(context).awaitIdle()
        val shown = songs.state.value
        val seen = changes.get()
        val smartSeen = smartChanges.get()

        // A whole run compares every decision with what is mirrored, as at a start-up after the user database changed.
        assertThat(Library.decisionMirror(context).mirrorAll()).describedAs("the run changed decided values").isFalse()

        throughout("the list is not read again", 1.seconds) {
            songs.state.value === shown && changes.get() == seen && smartChanges.get() == smartSeen
        }
        assertThat(SongList.current()).describedAs("the kept list is still handed out").isNotNull()

        // A run that changes values does read again, so the run above had its chance.
        Library.decisions(context).apply(DecisionFixtures.request(KEY, DecisionFixtures.details(title = "Yankee", recording = 101), fileId = alpha))
        eventually("the list shows the newer decision") { songs.titles() == listOf("Bravo", "Charlie", "Yankee") }
        assertThat(changes.get()).isGreaterThan(seen)
        eventually("a smart list may read again") { smartChanges.get() > smartSeen }
    }

    private companion object {
        val KEY = "%064x".format(7)
    }
}
