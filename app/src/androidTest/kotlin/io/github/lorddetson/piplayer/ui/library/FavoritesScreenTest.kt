package io.github.lorddetson.piplayer.ui.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.playback.PlaybackStateStore
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.player.PlayerTags
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The favorite toggles and the Favorites chip of the Albums tab (docs/library.md §15.2). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FavoritesScreenTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", path = "Music/Ann/Red/", name = "01 Alpha.mp3", artist = "Ann", album = "Red Album", trackNumber = 1),
            row(2, "Bravo", path = "Music/Ann/Red/", name = "02 Bravo.mp3", artist = "Ann", album = "Red Album", trackNumber = 2),
            row(3, "Charlie", path = "Music/Bob/Plain/", name = "01 Charlie.mp3", artist = "Bob", album = "Plain", trackNumber = 1),
        )
    }

    /** Alpha and Bravo have identities, as after the tag pass; Charlie's tags are not read. The player starts empty. */
    private val setUp = object : ExternalResource() {
        override fun before() {
            runBlocking {
                Library.sync(context).awaitIdle()
                val dao = Library.database(context).library()
                for (id in listOf(1L, 2L)) dao.setContent(fileId(id), key(id), 200_000, DurationSource.HEADER)
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

    private fun key(n: Long) = "%064x".format(n)

    private fun fileId(mediaStoreId: Long) = runBlocking {
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id
    }

    private fun identity(n: Long) = runBlocking { checkNotNull(UserData.database(context).identities().idOfKey(Hex.bytes(key(n)))) }

    private fun albumKey(mediaStoreId: Long) = Library.read(context) { LibraryLists(it).links(fileId(mediaStoreId)) }!!.albumKey!!

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun tap(matcher: SemanticsMatcher) {
        compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private fun tab(id: Int) = tap(hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS)))

    private val inPage = hasAnyAncestor(hasTestTag(LibraryTags.LIST))

    private fun isFavorite(n: Long) = runBlocking { UserData.favorites(context).isFavorite(identity(n)) }

    @Test
    fun theSongMenuAddsAndRemovesAFavorite() {
        tap(hasContentDescription(text(R.string.song_menu, "Alpha")))
        tap(hasText(text(R.string.favorite_add)))
        compose.waitUntil(10_000) { isFavorite(1) }

        tap(hasContentDescription(text(R.string.song_menu, "Alpha")))
        tap(hasText(text(R.string.favorite_remove)))
        compose.waitUntil(10_000) { !isFavorite(1) }
    }

    @Test
    fun aSongWhoseTagsAreNotReadCannotBecomeAFavoriteYet() {
        tap(hasContentDescription(text(R.string.song_menu, "Charlie")))
        tap(hasText(text(R.string.favorite_add)))

        compose.waitUntilAtLeastOneExists(hasText(text(R.string.cover_not_read)), timeoutMillis = 10_000)
    }

    @Test
    fun nowPlayingTogglesTheCurrentSong() {
        tab(R.string.tab_albums)
        tap(hasText("Red Album") and inPage)
        tap(hasText("Bravo") and inPage)
        tap(hasTestTag(PlayerTags.MINI_PLAYER))

        val toggle = hasTestTag(PlayerTags.FAVORITE)
        compose.waitUntilAtLeastOneExists(toggle and hasContentDescription(text(R.string.favorite)), timeoutMillis = 10_000)
        compose.onNode(toggle).assertIsOff()
        compose.onNode(toggle).performClick()

        compose.waitUntil(10_000) { isFavorite(2) }
        compose.waitUntil(10_000) { runCatching { compose.onNode(toggle).assertIsOn() }.isSuccess }
    }

    @Test
    fun theAlbumPageTogglesTheAlbumAndTheChipListsFavoriteAlbums() {
        tab(R.string.tab_albums)
        tap(hasText("Red Album") and inPage)
        val toggle = hasTestTag(LibraryTags.ALBUM_FAVORITE)
        compose.waitUntilAtLeastOneExists(toggle and hasContentDescription(text(R.string.favorite)), timeoutMillis = 10_000)
        compose.onNode(toggle).performClick()
        compose.waitUntil(10_000) { runBlocking { UserData.favorites(context).isFavoriteAlbum(albumKey(1)) } }
        compose.waitUntil(10_000) { runCatching { compose.onNode(toggle).assertIsOn() }.isSuccess }

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap(hasTestTag(LibraryTags.FAVORITES_CHIP))

        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Plain") and inPage).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasText("Red Album") and inPage).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(compose.onAllNodes(hasText("Red Album") and inPage).fetchSemanticsNodes()).hasSize(1)

        // Off again, both albums.
        tap(hasTestTag(LibraryTags.FAVORITES_CHIP))
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Plain") and inPage).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theChipSaysSoWhenThereIsNoFavoriteAlbum() {
        tab(R.string.tab_albums)
        tap(hasTestTag(LibraryTags.FAVORITES_CHIP))

        compose.waitUntilAtLeastOneExists(hasText(text(R.string.favorite_albums_none)), timeoutMillis = 10_000)
    }
}
