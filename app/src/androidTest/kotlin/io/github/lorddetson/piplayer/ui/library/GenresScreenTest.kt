package io.github.lorddetson.piplayer.ui.library

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.sync.MediaKinds
import io.github.lorddetson.piplayer.playback.PlaybackStateStore
import io.github.lorddetson.piplayer.playback.PlaybackTesting.awaitState
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The tabs in their order, the Genres tab and the genre pages (docs/library.md §19.2, §19.3). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GenresScreenTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", artist = "Bob", genre = "Rock; Pop"),
            row(2, "Bravo", artist = "Ann", genre = "Rock"),
            row(3, "Charlie", artist = "Cid", genre = "Jazz"),
            row(4, "Delta", artist = "Dee", genre = null),
            row(5, "Echo", artist = "Eve", genre = "Rock"),
            row(6, "Ring", path = "Ringtones/", artist = "Sys", genre = "Tones", kinds = MediaKinds(music = false, ringtone = true)),
        )
    }

    /** The player starts empty and with shuffle off. */
    private val emptyQueue = object : ExternalResource() {
        override fun before() {
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
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(emptyQueue).around(compose)

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun songs(count: Int) = compose.activity.resources.getQuantityString(R.plurals.songs_count, count, count)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    // The tab row scrolls: Folders, the last of six tabs, may be out of sight (docs/library.md §19.2).
    private fun tab(id: Int) {
        val matcher = hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS))
        waitFor(matcher)
        compose.onAllNodes(matcher)[0].performScrollTo().performClick()
    }

    private val inList = hasAnyAncestor(hasTestTag(LibraryTags.LIST))

    /** The texts of the rows of the list shown, in order, those of [names]. */
    private fun shownOf(names: List<String>): List<String> = compose.onAllNodes(inList and hasTestTag(GenreTags.ROW)).fetchSemanticsNodes()
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.firstOrNull { it in names } }

    @Test
    fun theTabsComeInTheOrderOfTheControllerTreeAndTheChosenOneIsKept() {
        waitFor(isTab and hasText(text(R.string.tab_genres)))
        val tabs = compose.onAllNodes(isTab).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: ""
        }
        assertThat(tabs).containsExactly(
            text(R.string.tab_songs), text(R.string.tab_albums), text(R.string.tab_artists), text(R.string.tab_playlists),
            text(R.string.tab_genres), text(R.string.tab_folders),
        )

        tab(R.string.tab_genres)
        waitFor(hasText("Jazz") and inList)
        compose.activityRule.scenario.recreate()

        waitFor(hasText("Jazz") and inList)
        compose.onNode(isTab and hasText(text(R.string.tab_genres))).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test
    fun theGenresTabListsTheGenresWithTheirSongsAndTheSongsWithoutOneLast() {
        tab(R.string.tab_genres)
        waitFor(hasText("Rock") and hasText(songs(3)) and inList)
        waitFor(hasText(text(R.string.genre_unknown)) and hasText(songs(1)) and inList)
        val names = listOf("Jazz", "Pop", "Rock", text(R.string.genre_unknown))
        assertThat(shownOf(names)).containsExactly("Jazz", "Pop", "Rock", text(R.string.genre_unknown))

        // By number of songs, most first; the songs without a genre stay last.
        tap(hasContentDescription(text(R.string.library_sort)))
        tap(hasText(text(R.string.sort_songs)))
        compose.waitUntil(10_000) { shownOf(names).firstOrNull() == "Rock" }
        assertThat(shownOf(names).last()).isEqualTo(text(R.string.genre_unknown))

        // The search filters by name and leaves the songs without a genre out.
        tap(hasContentDescription(text(R.string.library_search)))
        compose.onNode(hasSetTextAction()).performTextInput("jaz")
        compose.waitUntil(10_000) { shownOf(names) == listOf("Jazz") }
    }

    @Test
    fun theTabFollowsTheHiddenFiles() {
        tab(R.string.tab_genres)
        waitFor(hasText("Jazz") and inList)
        assertThat(compose.onAllNodes(hasText("Tones") and inList).fetchSemanticsNodes()).isEmpty()

        runBlocking { LibrarySettings(context).setShowSystemSounds(true) }

        waitFor(hasText("Tones") and inList)
        waitFor(hasText("Rock") and hasText(songs(3)) and inList)
    }

    @Test
    fun aGenrePagePlaysFromARowAndWithPlayAndShuffle() {
        tab(R.string.tab_genres)
        tap(hasText("Rock") and inList)
        // By artist: Ann's Bravo, Bob's Alpha, Eve's Echo.
        tap(hasText("Echo") and inList)
        val controller = connectController()
        try {
            controller.awaitState("Echo plays from Rock") {
                mediaItemCount == 3 && currentMediaItemIndex == 2 && currentMediaItem?.mediaMetadata?.title?.toString() == "Echo"
            }
            tap(hasTestTag(GenreTags.PLAY))
            controller.awaitState("Rock plays from Bravo") {
                mediaItemCount == 3 && currentMediaItemIndex == 0 && currentMediaItem?.mediaMetadata?.title?.toString() == "Bravo" &&
                    !shuffleModeEnabled
            }
            tap(hasTestTag(GenreTags.SHUFFLE))
            controller.awaitState("Rock plays shuffled") { mediaItemCount == 3 && shuffleModeEnabled }
        } finally {
            controller.stopAndRelease()
        }
    }

    @Test
    fun aGenreRowAddsTheGenresSongs() {
        tab(R.string.tab_genres)
        tap(hasContentDescription(text(R.string.song_menu, "Jazz")))
        tap(hasText(text(R.string.add_to_queue)))
        val controller = connectController()
        try {
            controller.awaitState("Jazz is the queue") { mediaItemCount == 1 && currentMediaItem?.mediaMetadata?.title?.toString() == "Charlie" }
        } finally {
            controller.stopAndRelease()
        }
        tap(hasContentDescription(text(R.string.song_menu, "Rock")))
        waitFor(hasText(text(R.string.add_to_playlist)))
        waitFor(hasText(text(R.string.play_next)))
    }

    @Test
    fun theSongsWithoutAGenreHaveAPageAndAGenreThatIsGoneSaysSo() {
        runBlocking { LibrarySettings(context).setShowSystemSounds(true) }
        tab(R.string.tab_genres)
        tap(hasText(text(R.string.genre_unknown)) and inList)
        waitFor(hasText("Delta") and inList)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tap(hasText("Tones") and inList)
        waitFor(hasText("Ring") and inList)
        runBlocking { LibrarySettings(context).setShowSystemSounds(false) }

        waitFor(hasText(text(R.string.genre_not_found)))
        assertThat(Library.revision()).isNotNegative()
    }
}
