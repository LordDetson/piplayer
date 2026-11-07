package io.github.lorddetson.piplayer.ui.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
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
import io.github.lorddetson.piplayer.covers.CoverImport
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.covers.PlaceholderColors
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.playback.PlaybackStateStore
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.player.PlayerTags
import io.github.lorddetson.piplayer.ui.player.TestImages
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import io.github.lorddetson.piplayer.userdata.UserData
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Covers on the library's screens and in the player (docs/library.md §13.5). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CoversScreenTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", path = "Music/Ann/Red/", name = "01 Alpha.mp3", artist = "Ann", album = "Red Album", trackNumber = 1),
            row(2, "Bravo", path = "Music/Ann/Red/", name = "02 Bravo.mp3", artist = "Ann", album = "Red Album", trackNumber = 2),
            row(3, "Charlie", path = "Music/Bob/Plain/", name = "01 Charlie.mp3", artist = "Bob", album = "Plain", trackNumber = 1),
        )
    }

    /** The red album gets an own cover before the activity starts; the player starts empty. */
    private val setUp = object : ExternalResource() {
        override fun before() {
            runBlocking { Library.sync(context).awaitIdle() }
            runBlocking { Covers.own(context).setAlbum(albumKey(1), copy(RED)) }
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

    private fun fileId(mediaStoreId: Long) = runBlocking {
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id
    }

    private fun albumKey(mediaStoreId: Long) = Library.read(context) { LibraryLists(it).links(fileId(mediaStoreId)) }!!.albumKey!!

    /** A copy of an image of one [color], as a choice from the photo picker gives it. */
    private fun copy(color: Int) = run {
        val file = File(context.cacheDir, "chosen-${System.nanoTime()}.png")
        file.outputStream().use { TestImages.bitmap(300, color = color).compress(Bitmap.CompressFormat.PNG, 100, it) }
        checkNotNull(CoverImport.copy(context, Uri.fromFile(file), Covers.own(context).dir))
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher, count: Int = 1) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size >= count
    }

    private fun tap(matcher: SemanticsMatcher) {
        compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private fun tab(id: Int) = tap(hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS)))

    private val inSongs = hasAnyAncestor(hasTestTag(SongsTags.LIST))
    private val inPage = hasAnyAncestor(hasTestTag(LibraryTags.LIST))
    private val cover = hasTestTag(PlayerTags.COVER)
    private val placeholder = hasTestTag(PlayerTags.COVER_PLACEHOLDER)

    /** The colors at the middle of each picture [matcher] finds. */
    private fun colors(matcher: SemanticsMatcher): List<Int> = compose.onAllNodes(matcher, useUnmergedTree = true)
        .fetchSemanticsNodes().indices.map { i ->
            val image = compose.onAllNodes(matcher, useUnmergedTree = true)[i].captureToImage().toPixelMap()
            image[image.width / 2, image.height / 2].let { (it.alpha * 255).toInt() shl 24 or ((it.red * 255).toInt() shl 16) or ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt() }
        }

    private fun near(color: Int, expected: Int) =
        listOf(16, 8, 0).all { shift -> kotlin.math.abs((color shr shift and 0xFF) - (expected shr shift and 0xFF)) <= 12 }

    @Test
    fun anAlbumsOwnCoverShowsOnItsSongs() {
        waitFor(cover and inSongs, count = 2)
        waitFor(placeholder and inSongs)

        val shown = colors(cover and inSongs)
        assertThat(shown).hasSize(2)
        assertThat(shown).allMatch { near(it, RED) }
    }

    @Test
    fun anAlbumsOwnCoverShowsOnItsRowItsTileAndItsPage() {
        tab(R.string.tab_albums)
        waitFor(cover and inPage)
        waitFor(placeholder and inPage)
        assertThat(colors(cover and inPage)).allMatch { near(it, RED) }

        compose.onNode(hasContentDescription(text(R.string.library_sort))).performClick()
        tap(hasText(text(R.string.sort_show_as_grid)))
        waitFor(cover and hasAnyAncestor(hasTestTag(LibraryTags.GRID)))

        tap(hasText("Red Album"))
        // The page's header and its two songs.
        waitFor(cover and inPage, count = 3)
        assertThat(colors(cover and inPage)).allMatch { near(it, RED) }
    }

    @Test
    fun aSongsOwnCoverWinsOverItsAlbums() {
        val id = fileId(1)
        runBlocking {
            Library.database(context).library().setContent(id, "%064x".format(id), 60_000, DurationSource.HEADER)
            Library.identities(context).attach()
            val identity = UserData.database(context).identities().identities().single().id
            Covers.own(context).setTrack(identity, copy(GREEN))
        }

        compose.waitUntil(10_000) { colors(cover and inSongs).count { near(it, GREEN) } == 1 }
        assertThat(colors(cover and inSongs).count { near(it, RED) }).isEqualTo(1)
    }

    @Test
    fun withoutACoverTheAlbumsColorShows() {
        waitFor(placeholder and inSongs)

        val node = compose.onAllNodes(placeholder and inSongs, useUnmergedTree = true)[0].captureToImage().toPixelMap()
        val pixel = node[node.width / 2, node.height / 10]
        val argb = (0xFF shl 24) or ((pixel.red * 255).toInt() shl 16) or ((pixel.green * 255).toInt() shl 8) or (pixel.blue * 255).toInt()
        val light = PlaceholderColors.of("Plain", dark = false).background
        val dark = PlaceholderColors.of("Plain", dark = true).background
        assertThat(near(argb, light) || near(argb, dark)).describedAs("%08x", argb).isTrue()
    }

    @Test
    fun theMiniPlayerAndNowPlayingShowTheCurrentSongsCover() {
        tab(R.string.tab_albums)
        tap(hasText("Red Album") and inPage)
        tap(hasText("Alpha") and inPage)

        val inMiniPlayer = hasAnyAncestor(hasTestTag(PlayerTags.MINI_PLAYER))
        waitFor(cover and inMiniPlayer)
        assertThat(colors(cover and inMiniPlayer)).allMatch { near(it, RED) }

        tap(hasTestTag(PlayerTags.MINI_PLAYER))
        compose.waitUntil(10_000) { colors(cover).any { near(it, RED) } && compose.onAllNodes(inMiniPlayer).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theAlbumMenuRemovesItsOwnCover() {
        tab(R.string.tab_albums)
        waitFor(cover and inPage)
        tap(hasContentDescription(text(R.string.song_menu, "Red Album")))
        tap(hasText(text(R.string.cover_remove)))

        compose.waitUntil(10_000) { compose.onAllNodes(cover and inPage, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        waitFor(placeholder and inPage, count = 2)
        assertThat(runBlocking { Covers.own(context).album(albumKey(1)) }).isNull()
    }

    @Test
    fun aSongWhoseTagsAreNotReadCannotTakeACoverYet() {
        tap(hasContentDescription(text(R.string.song_menu, "Charlie")))
        tap(hasText(text(R.string.cover_choose)))

        compose.waitUntilAtLeastOneExists(hasText(text(R.string.cover_not_read)), timeoutMillis = 10_000)
    }

    private companion object {
        const val RED = 0xFFE53935.toInt()
        const val GREEN = 0xFF43A047.toInt()
    }
}
