package io.github.lorddetson.piplayer.ui.playlists

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.library.LibraryTags
import io.github.lorddetson.piplayer.userdata.NewEntry
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The Playlists tab, the playlist page and "Add to playlist" (docs/library.md §16.3). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlaylistsScreenTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply { volume().rows = BASE_ROWS }

    /** Alpha and Bravo have identities, as after the tag pass; Charlie's tags are not read. The player starts empty. */
    private val setUp = object : ExternalResource() {
        override fun before() {
            runBlocking {
                Library.sync(context).awaitIdle()
                val dao = Library.database(context).library()
                for (id in listOf(1L, 2L)) dao.setContent(fileId(id), key(id), 60_000, DurationSource.HEADER)
                Library.identities(context).attach()
            }
            val cleaner = connectController()
            onMain {
                cleaner.stop()
                cleaner.clearMediaItems()
            }
            cleaner.waitUntil("the player is empty") { mediaItemCount == 0 }
            runBlocking { PlaybackStateStore.get(context).clear() }
            onMain { cleaner.release() }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(setUp).around(compose)

    private val playlists get() = UserData.playlists(context)

    private fun key(n: Long) = "%064x".format(n)

    private fun fileId(mediaStoreId: Long) = runBlocking {
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id
    }

    private fun identity(n: Long) = runBlocking { UserData.database(context).identities().idOfKey(Hex.bytes(key(n))) }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun tap(matcher: SemanticsMatcher) {
        compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private fun tab(id: Int) = tap(hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS)))

    private fun typeName(name: String) {
        val field = hasSetTextAction() and hasTestTag(PlaylistTags.NAME_FIELD)
        compose.waitUntilAtLeastOneExists(field, timeoutMillis = 10_000)
        compose.onNode(field).performTextClearance()
        compose.onNode(field).performTextInput(name)
    }

    private fun inDialog(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(isDialog())

    private fun named(name: String) = runBlocking { playlists.playlists().filter { it.name == name } }

    private fun entryFiles(id: Long) = runBlocking { Library.playlists(context).items(id).map { it.song?.id } }

    /** Runs the accessibility action [label] of the row that shows [title]. */
    private fun action(title: String, label: String) {
        val row = hasTestTag(PlaylistTags.ROW) and hasAnyAncestor(hasTestTag(PlaylistTags.PAGE_LIST)) and hasText(title, substring = true)
        compose.waitUntilAtLeastOneExists(row, timeoutMillis = 10_000)
        val node = compose.onAllNodes(row, useUnmergedTree = false)[0].fetchSemanticsNode()
        val custom = node.config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { custom.action() }
    }

    private fun songsPlaylist(vararg mediaStoreIds: Long): Long = runBlocking {
        val id = playlists.create("Mix")
        Library.playlists(context).addFiles(id, mediaStoreIds.map { fileId(it) })
        id
    }

    @Test
    fun aPlaylistIsCreatedRenamedAndDeletedWithAConfirmation() {
        tab(R.string.tab_playlists)
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.playlists_none)), timeoutMillis = 10_000)
        tap(hasTestTag(PlaylistTags.NEW))
        typeName("Road trip")
        tap(inDialog(hasText(text(R.string.playlist_create))))
        compose.waitUntil(10_000) { named("Road trip").size == 1 }
        compose.waitUntilAtLeastOneExists(hasText("Road trip") and hasAnyAncestor(hasTestTag(PlaylistTags.LIST)), timeoutMillis = 10_000)

        tap(hasContentDescription(text(R.string.song_menu, "Road trip")))
        tap(hasText(text(R.string.playlist_rename)))
        typeName("Gym")
        tap(inDialog(hasText(text(R.string.playlist_save))))
        compose.waitUntil(10_000) { named("Gym").size == 1 && named("Road trip").isEmpty() }

        tap(hasContentDescription(text(R.string.song_menu, "Gym")))
        tap(hasText(text(R.string.playlist_delete)))
        compose.waitUntilAtLeastOneExists(inDialog(hasText(text(R.string.playlist_delete_title, "Gym"))), timeoutMillis = 10_000)
        tap(inDialog(hasText(text(R.string.playlist_delete))))
        compose.waitUntil(10_000) { named("Gym").isEmpty() }
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.playlists_none)), timeoutMillis = 10_000)
    }

    @Test
    fun theSongMenuAddsToANewPlaylistAndToAnExistingOne() {
        tap(hasContentDescription(text(R.string.song_menu, "Alpha")))
        tap(hasText(text(R.string.add_to_playlist)))
        tap(inDialog(hasText(text(R.string.playlist_new))))
        typeName("Mix")
        tap(inDialog(hasText(text(R.string.playlist_create))))
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.playlist_added, "Mix")), timeoutMillis = 10_000)
        val id = named("Mix").single().id
        assertThat(runBlocking { playlists.entries(id) }.map { it.identityId }).containsExactly(identity(1))

        // Charlie's tags are not read: it is added all the same, by its place (docs/library.md §16.2).
        tap(hasContentDescription(text(R.string.song_menu, "Charlie")))
        tap(hasText(text(R.string.add_to_playlist)))
        tap(inDialog(hasText("Mix")))
        compose.waitUntil(10_000) { entryFiles(id).size == 2 }
        assertThat(entryFiles(id)).containsExactly(fileId(1), fileId(3))
    }

    @Test
    fun anAlbumsMenuAddsItsSongsByTrack() {
        val id = runBlocking { playlists.create("Mix") }
        tab(R.string.tab_albums)
        tap(hasContentDescription(text(R.string.song_menu, "Red Album")))
        tap(hasText(text(R.string.add_to_playlist)))
        tap(inDialog(hasText("Mix")))

        compose.waitUntil(10_000) { entryFiles(id).size == 2 }
        assertThat(entryFiles(id)).containsExactly(fileId(1), fileId(2))
        compose.waitUntilAtLeastOneExists(hasText(compose.activity.resources.getQuantityString(R.plurals.playlist_added_count, 2, 2, "Mix")), timeoutMillis = 10_000)
    }

    @Test
    fun thePagePlaysFromAnEntryMovesEntriesAndPutsARemovedOneBack() {
        val id = songsPlaylist(1, 2, 3)
        tab(R.string.tab_playlists)
        tap(hasText("Mix") and hasAnyAncestor(hasTestTag(PlaylistTags.LIST)))

        tap(hasText("Bravo") and hasAnyAncestor(hasTestTag(PlaylistTags.PAGE_LIST)))
        val controller = connectController()
        try {
            controller.awaitState("Bravo plays from the playlist") {
                mediaItemCount == 3 && currentMediaItem?.mediaMetadata?.title?.toString() == "Bravo"
            }
        } finally {
            controller.stopAndRelease()
        }

        action("Charlie", text(R.string.queue_move_up))
        compose.waitUntil(10_000) { entryFiles(id) == listOf(fileId(1), fileId(3), fileId(2)) }

        tap(hasContentDescription(text(R.string.song_menu, "Alpha")) and hasAnyAncestor(hasTestTag(PlaylistTags.PAGE_LIST)))
        tap(hasText(text(R.string.playlist_remove)))
        compose.waitUntil(10_000) { entryFiles(id) == listOf(fileId(3), fileId(2)) }
        tap(hasText(text(R.string.queue_undo)))
        compose.waitUntil(10_000) { entryFiles(id) == listOf(fileId(1), fileId(3), fileId(2)) }
    }

    @Test
    fun deletingAPlaylistFromItsPageClosesThePage() {
        songsPlaylist(1)
        tab(R.string.tab_playlists)
        tap(hasText("Mix") and hasAnyAncestor(hasTestTag(PlaylistTags.LIST)))
        compose.waitUntilAtLeastOneExists(hasTestTag(PlaylistTags.PAGE_LIST), timeoutMillis = 10_000)

        tap(hasContentDescription(text(R.string.page_menu)))
        tap(hasText(text(R.string.playlist_delete)))
        tap(inDialog(hasText(text(R.string.playlist_delete))))

        compose.waitUntil(10_000) { named("Mix").isEmpty() }
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.playlists_none)), timeoutMillis = 10_000)
        compose.waitUntilDoesNotExist(hasTestTag(PlaylistTags.PAGE_LIST), timeoutMillis = 10_000)
    }

    @Test
    fun aLargePlaylistScrollsToItsEndAndMovesItsLastEntry() {
        // 5 000 entries, the size of TI's budget (§10.2), each by its place.
        val id = runBlocking {
            index.volume().rows = BASE_ROWS + (1..LARGE).map { row(100L + it, "Song %05d".format(it), path = "Music/Large/", name = "$it.mp3") }
            Library.sync(context).sync()
            val created = playlists.create("Large")
            playlists.add(created, (1..LARGE).map { NewEntry(null, FakeMediaIndex.PRIMARY, "Music/Large/", "$it.mp3", null) })
            created
        }
        tab(R.string.tab_playlists)
        tap(hasText("Large") and hasAnyAncestor(hasTestTag(PlaylistTags.LIST)))
        val last = "Song %05d".format(LARGE)
        compose.waitUntilAtLeastOneExists(hasText("Song 00001") and hasAnyAncestor(hasTestTag(PlaylistTags.PAGE_LIST)), timeoutMillis = 30_000)

        compose.onNode(hasTestTag(PlaylistTags.PAGE_LIST)).performScrollToIndex(LARGE)
        action(last, text(R.string.queue_move_up))

        compose.waitUntil(30_000) {
            runBlocking { playlists.entries(id) }.let { entries -> entries[LARGE - 2].hintDisplayName == "$LARGE.mp3" }
        }
    }

    private companion object {
        const val LARGE = 5_000

        val BASE_ROWS = listOf(
            row(1, "Alpha", path = "Music/Ann/Red/", name = "01 Alpha.mp3", artist = "Ann", album = "Red Album", trackNumber = 1),
            row(2, "Bravo", path = "Music/Ann/Red/", name = "02 Bravo.mp3", artist = "Ann", album = "Red Album", trackNumber = 2),
            row(3, "Charlie", path = "Music/Bob/Plain/", name = "01 Charlie.mp3", artist = "Bob", album = "Plain", trackNumber = 1),
        )
    }
}
