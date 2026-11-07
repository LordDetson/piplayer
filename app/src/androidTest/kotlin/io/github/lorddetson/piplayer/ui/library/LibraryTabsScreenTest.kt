package io.github.lorddetson.piplayer.ui.library

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.player.PlayerTags
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The library tabs and pages over the library database (docs/library.md §6). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryTabsScreenTest {

    private val rows = listOf(
        row(1, "Bravo", path = "Music/Ann/First/", name = "02 Bravo.mp3", artist = "Ann", album = "First", trackNumber = 2),
        row(2, "Alpha", path = "Music/Ann/First/", name = "01 Alpha.mp3", artist = "Ann", album = "First", trackNumber = 1),
        row(3, "Charlie", path = "Music/Bob/Second/CD1/", name = "01 Charlie.mp3", artist = "Bob", album = "Second", trackNumber = 1001),
        row(4, "Delta", path = "Music/Bob/Second/CD2/", name = "01 Delta.mp3", artist = "Bob", album = "Second", trackNumber = 2001),
        row(5, "Golf", path = "Music/", name = "golf.mp3", artist = "Ann", album = null),
    )
    private val index = FakeMediaIndex().apply { volume().rows = rows }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
        // The service outlives a test: the queue of the one before must not take the songs added here.
        onMain {
            controller.stop()
            controller.clearMediaItems()
        }
        controller.waitUntil("the player is empty") { mediaItemCount == 0 }
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private val inPage = hasAnyAncestor(hasTestTag(LibraryTags.LIST))
    private val inSongs = hasAnyAncestor(hasTestTag(SongsTags.LIST))

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private fun tab(id: Int) = tap(hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS)))

    /** The file IDs the library gave the songs of these media index IDs, which the test chose. */
    private fun fileIds(vararg mediaStoreIds: Long): List<Long> {
        val files = runBlocking { Library.database(TestFiles.targetContext).library().files() }
        return mediaStoreIds.map { id -> files.single { it.mediaStoreId == id }.id }
    }

    /** The queue the tap built, named by the songs' media index IDs; media IDs are file IDs (docs/library.md §8). */
    private fun waitForQueue(vararg ids: Long) {
        val expected = fileIds(*ids).map { it.toString() }
        controller.waitUntil("the queue ${ids.toList()}") {
            mediaItemCount == ids.size && (0 until mediaItemCount).map { getMediaItemAt(it).mediaId } == expected
        }
    }

    @Test
    fun theTabsListAlbumsArtistsAndFolders() {
        tab(R.string.tab_albums)
        waitFor(hasText("First") and inPage)
        waitFor(hasText("Second") and inPage)
        tab(R.string.tab_artists)
        waitFor(hasText("Bob") and inPage)
        tab(R.string.tab_folders)
        waitFor(hasText("Music") and inPage)
        tab(R.string.tab_songs)
        waitFor(hasText("Alpha") and inSongs)
    }

    @Test
    fun anAlbumPageShowsItsDiscsAndPlaysTheAlbumFromTheTappedSong() {
        tab(R.string.tab_albums)
        tap(hasText("Second") and inPage)
        waitFor(hasText(text(R.string.album_disc, 2)) and inPage)
        tap(hasText("Delta") and inPage)

        waitForQueue(3, 4)
        controller.waitUntil("Delta is current") { currentMediaItemIndex == 1 }
    }

    @Test
    fun aFolderPagePlaysTheFolderWithItsSubfolders() {
        tab(R.string.tab_folders)
        tap(hasText("Music") and inPage)
        tap(hasText("Bob") and inPage)
        tap(hasText(text(R.string.folder_play_all)))

        waitForQueue(3, 4)
    }

    @Test
    fun anArtistPageListsTheAlbumsAndTheSongs() {
        tab(R.string.tab_artists)
        tap(hasText("Ann") and inPage)
        waitFor(hasText("First") and inPage)
        tap(hasText("Golf") and inPage)

        waitForQueue(2, 1, 5)
    }

    @Test
    fun aPageIsFoundAgainAfterTheLibraryDatabaseIsRebuilt() {
        tab(R.string.tab_albums)
        tap(hasText("Second") and inPage)
        waitFor(hasText("Charlie") and inPage)

        // A new index gives every file a new ID; the page is found by its album key, not by a row.
        val moved = FakeMediaIndex().apply { volume().rows = rows.map { it.copy(mediaStoreId = it.mediaStoreId + 100) } }
        runBlocking { Library.reopen(TestFiles.targetContext, moved, empty = true) }
        SongList.forget()
        LibraryRule.startAsANewProcess()
        compose.activityRule.scenario.recreate()

        tap(hasText("Charlie") and inPage)
        waitForQueue(103, 104)
    }

    @Test
    fun anAlbumThatIsGoneSaysSo() {
        tab(R.string.tab_albums)
        tap(hasText("Second") and inPage)
        waitFor(hasText("Charlie") and inPage)

        synchronized(index) { index.volume().rows = rows.filter { it.album != "Second" } }
        Library.sync(TestFiles.targetContext).request()

        waitFor(hasText(text(R.string.album_not_found)))
    }

    @Test
    fun theSearchFiltersTheTabAndATapPlaysWhatItShows() {
        compose.onNode(hasContentDescription(text(R.string.library_search))).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("ann")

        waitFor(hasText("Golf") and inSongs)
        compose.waitUntilDoesNotExist(hasText("Charlie") and inSongs, timeoutMillis = 10_000)
        tap(hasText("Golf") and inSongs)
        waitForQueue(2, 1, 5)

        tab(R.string.tab_albums)
        waitFor(hasText("First") and inPage)
        compose.waitUntilDoesNotExist(hasText("Second") and inPage, timeoutMillis = 10_000)
    }

    @Test
    fun theSortMenuReversesTheList() {
        waitFor(hasText("Alpha") and inSongs)
        compose.onNode(hasContentDescription(text(R.string.library_sort))).performClick()
        tap(hasText(text(R.string.sort_reverse)))

        // The tap plays the list shown, so only once the reversed list is on screen.
        compose.waitUntil(10_000) { firstSong() == "Golf" }
        tap(hasText("Golf") and inSongs)
        waitForQueue(5, 4, 3, 1, 2)
    }

    /** The title of the first row of the Songs list. */
    private fun firstSong(): String? = compose.onNode(hasTestTag(SongsTags.LIST)).fetchSemanticsNode().children
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    @Test
    fun theSongMenuGoesToTheAlbumArtistAndFolder() {
        waitFor(hasText("Charlie") and inSongs)
        tap(hasContentDescription(text(R.string.song_menu, "Charlie")))
        tap(hasText(text(R.string.go_to_album)))
        waitFor(hasText(text(R.string.album_disc, 1)) and inPage)

        back()
        tap(hasContentDescription(text(R.string.song_menu, "Charlie")))
        tap(hasText(text(R.string.go_to_artist, "Bob")))
        waitFor(hasText("Bob") and hasAnyAncestor(hasTestTag(LibraryTags.TOP_BAR)))

        back()
        tap(hasContentDescription(text(R.string.song_menu, "Charlie")))
        tap(hasText(text(R.string.go_to_folder)))
        waitFor(hasText("Charlie") and inPage)
        waitFor(hasText("CD1") and hasAnyAncestor(hasTestTag(LibraryTags.TOP_BAR)))
    }

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun aPageSearchThatFindsNothingSaysSo() {
        tab(R.string.tab_albums)
        tap(hasText("Second") and inPage)
        waitFor(hasText("Charlie") and inPage)
        compose.onNode(hasContentDescription(text(R.string.library_search))).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("zzz")

        waitFor(hasText(text(R.string.search_nothing_found)))
    }

    @Test
    fun nowPlayingOpensTheArtistAndTheAlbumAndTheListMarksThePlayingSong() {
        tab(R.string.tab_albums)
        tap(hasText("Second") and inPage)
        tap(hasText("Charlie") and inPage)
        waitForQueue(3, 4)
        // The index's files do not exist, so the player skips them and stops at the last one, Delta: wait for that, then
        // the song it stopped at is the playing one.
        controller.waitUntil("playback stops") { playerError != null }
        assertThat(onMain { controller.currentMediaItem?.mediaId }).isEqualTo(fileIds(4).single().toString())
        waitFor(hasText("Delta") and inPage and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.song_playing)))

        back()
        tab(R.string.tab_songs)
        tap(hasTestTag(PlayerTags.MINI_PLAYER))
        tap(hasText("Second") and hasAnyAncestor(hasTestTag(PlayerTags.NOW_PLAYING)))
        waitFor(hasText("Second") and hasAnyAncestor(hasTestTag(LibraryTags.TOP_BAR)))

        tap(hasTestTag(PlayerTags.MINI_PLAYER))
        tap(hasText("Bob") and hasAnyAncestor(hasTestTag(PlayerTags.NOW_PLAYING)))
        waitFor(hasText("Bob") and hasAnyAncestor(hasTestTag(LibraryTags.TOP_BAR)))
    }


    @Test
    fun theSongMenuPlaysASongNextAndAddsOneToTheQueue() {
        tap(hasText("Alpha") and inSongs)
        waitForQueue(2, 1, 3, 4, 5)
        // The index's files do not exist: playback skips them and stops at the last one, Golf, which is then current.
        controller.waitUntil("playback stops") { playerError != null && currentMediaItemIndex == 4 }
        val (alpha, charlie) = fileIds(2, 3)

        tap(hasContentDescription(text(R.string.song_menu, "Charlie")))
        tap(hasText(text(R.string.play_next)))
        waitFor(hasText(text(R.string.queue_plays_next, "Charlie")))
        controller.waitUntil("Charlie is added") { mediaItemCount == 6 }

        tap(hasContentDescription(text(R.string.song_menu, "Alpha")))
        tap(hasText(text(R.string.add_to_queue)))
        controller.waitUntil("Alpha is added") { mediaItemCount == 7 }
        val ids = onMain { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } }
        assertThat(ids.drop(5)).containsExactly("$charlie/a1", "$alpha/a2")
    }

    @Test
    fun anAlbumMenuAddsTheAlbumToAnEmptyQueue() {
        tab(R.string.tab_albums)
        tap(hasContentDescription(text(R.string.song_menu, "Second")))
        tap(hasText(text(R.string.add_to_queue)))

        val (charlie, delta) = fileIds(3, 4)
        controller.waitUntil("the album is the queue") {
            mediaItemCount == 2 && getMediaItemAt(0).mediaId == "$charlie/a1" && getMediaItemAt(1).mediaId == "$delta/a2"
        }
        waitFor(hasText(compose.activity.resources.getQuantityString(R.plurals.queue_added_count, 2, 2)))
        assertThat(onMain { controller.playWhenReady }).isFalse()
    }

    @Test
    fun anArtistRowAndAFolderPageAddTheirSongs() {
        tab(R.string.tab_artists)
        tap(hasContentDescription(text(R.string.song_menu, "Bob")))
        tap(hasText(text(R.string.add_to_queue)))
        controller.waitUntil("Bob's songs are the queue") { mediaItemCount == 2 }

        tab(R.string.tab_folders)
        tap(hasText("Music") and inPage)
        tap(hasText("Ann") and inPage)
        tap(hasContentDescription(text(R.string.page_menu)))
        tap(hasText(text(R.string.play_next)))
        // Ann's folder with its subfolder First: Alpha and Bravo, by file name.
        val (alpha, bravo) = fileIds(2, 1)
        controller.waitUntil("Ann's folder is added") { mediaItemCount == 4 }
        val ids = onMain { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } }
        val at = ids.indexOf("$alpha/a3")
        assertThat(at).isPositive()
        assertThat(ids[at + 1]).isEqualTo("$bravo/a4")
    }
}
