package io.github.lorddetson.piplayer.ui.selection

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.playback.PlaybackStateStore
import io.github.lorddetson.piplayer.playback.PlaybackTesting.awaitState
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.playback.QueueIds
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.details.DetailsTags
import io.github.lorddetson.piplayer.ui.library.LibraryTags
import io.github.lorddetson.piplayer.ui.playlists.PlaylistTags
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import io.github.lorddetson.piplayer.userdata.NewEntry
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Selecting songs on the real library and service (docs/library.md §20): the bar, back, the tabs, and every action of
 * the selection menu on the Songs tab, an album page and a playlist page.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SelectionScreenTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply { volume().rows = ROWS }

    /** Alpha and Bravo have identities, as after the tag pass; Charlie's tags are not read. The player starts empty. */
    private val setUp = object : ExternalResource() {
        override fun before() {
            runBlocking {
                Library.sync(context).awaitIdle()
                val dao = Library.database(context).library()
                for (id in listOf(1L, 2L)) dao.setContent(fileId(id), key(id), 60_000, DurationSource.HEADER)
                Library.identities(context).attach()
                UserData.favorites(context).clear()
            }
            val cleaner = connectController()
            onMain {
                cleaner.stop()
                cleaner.clearMediaItems()
                cleaner.shuffleModeEnabled = false
            }
            cleaner.waitUntil("the player is empty") { mediaItemCount == 0 }
            runBlocking { PlaybackStateStore.get(context).clear() }
            onMain { cleaner.release() }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(setUp).around(compose)

    private fun key(n: Long) = "%064x".format(n)

    private fun fileId(mediaStoreId: Long) = runBlocking {
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id
    }

    private fun identity(n: Long) = runBlocking { UserData.database(context).identities().idOfKey(Hex.bytes(key(n))) }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun plural(id: Int, n: Int) = compose.activity.resources.getQuantityString(id, n, n)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private fun songRow(title: String, list: String = SongsTags.LIST) =
        hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and hasAnyAncestor(hasTestTag(list))

    private fun longPress(title: String, list: String = SongsTags.LIST) {
        waitFor(songRow(title, list))
        compose.onAllNodes(songRow(title, list))[0].performTouchInput { longClick() }
    }

    private val bar = hasTestTag(SelectionTags.BAR)

    private fun selected(n: Int) = waitFor(hasText(plural(R.plurals.selection_count, n)) and hasAnyAncestor(bar))

    private fun menuEntry(label: Int) {
        tap(hasTestTag(SelectionTags.MENU))
        tap(hasText(text(label)))
    }

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun tab(id: Int) = tap(hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS)))

    private fun queueIds(): List<String> {
        val controller = connectController()
        return try {
            onMain { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } }
        } finally {
            onMain { controller.release() }
        }
    }

    @Test
    fun aLongPressStartsSelectionTheBarCountsAndATapSelectsWithoutPlaying() {
        longPress("Bravo")
        selected(1)

        compose.onAllNodes(songRow("Charlie"))[0].performClick()
        selected(2)
        tap(hasText(text(R.string.selection_select_all)) and hasAnyAncestor(bar))
        selected(3)
        tap(hasText(text(R.string.selection_deselect_all)) and hasAnyAncestor(bar))

        compose.waitUntilDoesNotExist(bar, timeoutMillis = 10_000)
        assertThat(queueIds()).isEmpty()
    }

    @Test
    fun backEndsSelectionOnTheLibraryScreenAndTheNextBackLeavesTheApp() {
        longPress("Alpha")
        selected(1)

        back()

        compose.waitUntilDoesNotExist(bar, timeoutMillis = 10_000)
        assertThat(compose.activity.isFinishing).isFalse()
        back()
        compose.waitUntil(10_000) { compose.activity.isFinishing }
    }

    @Test
    fun onAnAlbumPageBackEndsSelectionAndThePageStaysUntilTheNextBack() {
        tab(R.string.tab_albums)
        tap(hasText("Red Album") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        longPress("Bravo", LibraryTags.LIST)
        selected(1)

        back()

        compose.waitUntilDoesNotExist(bar, timeoutMillis = 10_000)
        waitFor(songRow("Alpha", LibraryTags.LIST))
        back()
        waitFor(hasTestTag(LibraryTags.TABS))
    }

    @Test
    fun theCloseButtonAndATabSwitchEndSelectionAndTheSearchComesBack() {
        tap(hasContentDescription(text(R.string.library_search)))
        compose.onNode(hasSetTextAction()).performTextInput("a")
        longPress("Alpha")
        selected(1)
        // The search field gives way to the bar.
        compose.onNode(hasSetTextAction()).assertDoesNotExist()

        tap(hasContentDescription(text(R.string.selection_close)))

        compose.waitUntilDoesNotExist(bar, timeoutMillis = 10_000)
        waitFor(hasSetTextAction() and hasText("a"))
        longPress("Alpha")
        selected(1)
        tab(R.string.tab_albums)
        compose.waitUntilDoesNotExist(bar, timeoutMillis = 10_000)
        tab(R.string.tab_songs)
        waitFor(songRow("Alpha"))
        compose.onNode(bar).assertDoesNotExist()
    }

    @Test
    fun playPlaysOnlyTheSelectedSongsInTheListsOrderAndEndsSelection() {
        longPress("Charlie")
        compose.onAllNodes(songRow("Alpha"))[0].performClick()
        selected(2)

        menuEntry(R.string.playlist_play)

        compose.waitUntilDoesNotExist(bar, timeoutMillis = 10_000)
        val controller = connectController()
        try {
            // The test index names no real files: the player may pass on from Alpha, so only the queue is checked.
            controller.awaitState("Alpha, then Charlie") {
                mediaItemCount == 2 && (0..1).map { QueueIds.fileId(getMediaItemAt(it).mediaId) } == listOf(fileId(1), fileId(3))
            }
        } finally {
            controller.stopAndRelease()
        }
    }

    @Test
    fun allButOneSongAddedToTheQueueGoInTheListsOrder() {
        longPress("Alpha")
        tap(hasText(text(R.string.selection_select_all)) and hasAnyAncestor(bar))
        compose.onAllNodes(songRow("Bravo"))[0].performClick()
        selected(2)

        menuEntry(R.string.add_to_queue)

        compose.waitUntilDoesNotExist(bar, timeoutMillis = 10_000)
        waitFor(hasText(compose.activity.resources.getQuantityString(R.plurals.queue_added_count, 2, 2)))
        compose.waitUntil(10_000) { queueIds().size == 2 }
        assertThat(queueIds().map(QueueIds::fileId)).containsExactly(fileId(1), fileId(3))
    }

    @Test
    fun addToPlaylistAddsTheSelectedSongsAndACancelledChooserKeepsTheSelection() {
        val id = runBlocking { UserData.playlists(context).create("Mix") }
        longPress("Charlie")
        compose.onAllNodes(songRow("Bravo"))[0].performClick()

        menuEntry(R.string.add_to_playlist)
        waitFor(hasText("Mix") and hasAnyAncestor(isDialog()))
        // Back in the chooser's own window cancels it.
        Espresso.pressBack()
        compose.waitUntilDoesNotExist(isDialog(), timeoutMillis = 10_000)
        selected(2)

        menuEntry(R.string.add_to_playlist)
        tap(hasText("Mix") and hasAnyAncestor(isDialog()))

        compose.waitUntil(10_000) { runBlocking { Library.playlists(context).items(id) }.size == 2 }
        assertThat(runBlocking { Library.playlists(context).items(id) }.map { it.song?.id }).containsExactly(fileId(2), fileId(3))
        compose.waitUntilDoesNotExist(bar, timeoutMillis = 10_000)
    }

    @Test
    fun favoritesForSeveralSongsSkipTheUnreadOneAndThenOfferRemoval() {
        longPress("Alpha")
        tap(hasText(text(R.string.selection_select_all)) and hasAnyAncestor(bar))
        selected(3)

        menuEntry(R.string.favorite_add)

        val favorites = UserData.favorites(context)
        compose.waitUntil(10_000) { runBlocking { favorites.favoriteIdentities(listOfNotNull(identity(1), identity(2))) }.size == 2 }
        waitFor(hasText(plural(R.plurals.selection_favorites_added, 2), substring = true))
        waitFor(hasText(plural(R.plurals.selection_favorites_not_read_count, 1), substring = true))
        compose.waitUntilDoesNotExist(bar, timeoutMillis = 10_000)

        // All counted songs are favorites now; Charlie is still not read and does not count.
        longPress("Alpha")
        tap(hasText(text(R.string.selection_select_all)) and hasAnyAncestor(bar))
        tap(hasTestTag(SelectionTags.MENU))
        tap(hasText(text(R.string.favorite_remove)) and isEnabled())
        compose.waitUntil(10_000) { runBlocking { favorites.favoriteIdentities(listOfNotNull(identity(1), identity(2))) }.isEmpty() }
    }

    @Test
    fun onlyAnUnreadSongSelectedLeavesFavoritesOff() {
        longPress("Charlie")
        tap(hasTestTag(SelectionTags.MENU))

        waitFor(hasText(text(R.string.selection_favorites_not_read)))
        val entry = compose.onNode(hasText(text(R.string.selection_favorites_not_read))).fetchSemanticsNode().config
        assertThat(entry.getOrNull(SemanticsProperties.Disabled)).isNotNull()
    }

    @Test
    fun detailsOpenForOneSelectedSong() {
        longPress("Bravo")
        compose.onAllNodes(songRow("Alpha"))[0].performClick()
        tap(hasTestTag(SelectionTags.MENU))
        waitFor(hasText(text(R.string.play_next)))
        compose.onNode(hasText(text(R.string.details))).assertDoesNotExist()
        // Back in the menu's own window closes it, not the selection.
        Espresso.pressBack()
        compose.waitUntilDoesNotExist(hasText(text(R.string.play_next)), timeoutMillis = 10_000)
        selected(2)
        compose.onAllNodes(songRow("Alpha"))[0].performClick()

        menuEntry(R.string.details)

        waitFor(hasTestTag(DetailsTags.LIST))
        back()
        waitFor(songRow("Alpha"))
        compose.onNode(bar).assertDoesNotExist()
    }

    @Test
    fun onAPlaylistPageSelectedEntriesLoseTheirHandlesAndAreRemovedTogetherWithOneUndo() {
        val playlists = UserData.playlists(context)
        val id = runBlocking {
            val created = playlists.create("Mix")
            Library.playlists(context).addFiles(created, listOf(fileId(1), fileId(2), fileId(1)))
            playlists.add(created, listOf(NewEntry(null, FakeMediaIndex.PRIMARY, "Music/Gone/", "gone.mp3", null, title = "Gone")))
            created
        }
        val before = runBlocking { playlists.entries(id) }
        tab(R.string.tab_playlists)
        tap(hasText("Mix") and hasAnyAncestor(hasTestTag(PlaylistTags.LIST)))
        val entry = { title: String -> hasTestTag(PlaylistTags.ROW) and hasText(title) and hasAnyAncestor(hasTestTag(PlaylistTags.PAGE_LIST)) }
        waitFor(entry("Gone"))

        compose.onAllNodes(entry("Alpha"))[1].performTouchInput { longClick() }
        tap(entry("Bravo"))
        tap(entry("Gone"))
        selected(3)
        for (node in compose.onAllNodes(hasTestTag(PlaylistTags.ROW)).fetchSemanticsNodes()) {
            assertThat(node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()).isEmpty()
        }

        menuEntry(R.string.playlist_remove)

        compose.waitUntil(10_000) { runBlocking { playlists.entries(id) }.size == 1 }
        assertThat(runBlocking { playlists.entries(id) }.single().id).isEqualTo(before[0].id)
        waitFor(hasText(plural(R.plurals.selection_removed_from_playlist, 3)))
        tap(hasText(text(R.string.queue_undo)))
        compose.waitUntil(10_000) { runBlocking { playlists.entries(id) }.map { it.id } == before.map { it.id } }
    }

    private companion object {
        val ROWS = listOf(
            row(1, "Alpha", path = "Music/Ann/Red/", name = "01 Alpha.mp3", artist = "Ann", album = "Red Album", trackNumber = 1),
            row(2, "Bravo", path = "Music/Ann/Red/", name = "02 Bravo.mp3", artist = "Ann", album = "Red Album", trackNumber = 2),
            row(3, "Charlie", path = "Music/Bob/Plain/", name = "01 Charlie.mp3", artist = "Bob", album = "Plain", trackNumber = 1),
        )
    }
}
