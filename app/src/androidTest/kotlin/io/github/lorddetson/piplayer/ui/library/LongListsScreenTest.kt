package io.github.lorddetson.piplayer.ui.library

import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.ListSorts
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The scroll thumb on long lists and the grids of the Albums and Artists tabs (docs/library.md §11.2, §11.3). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LongListsScreenTest {

    /** Twelve songs for each letter from A to Z, each on an album and by an artist of its own. */
    private val rows = ('A'..'Z').flatMapIndexed { letter, char ->
        (1..12).map { n ->
            val id = letter * 12L + n
            row(id, "$char song $n", path = "Music/$char/", name = "$char$n.mp3", artist = "$char artist $n", album = "$char album $n")
        }
    }
    private val index = FakeMediaIndex().apply { volume().rows = rows }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    private fun text(id: Int) = compose.activity.getString(id)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private fun tab(id: Int) = tap(hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS)))

    private fun showAsGrid() {
        compose.onNode(hasContentDescription(text(R.string.library_sort))).performClick()
        tap(hasText(text(R.string.sort_show_as_grid)))
    }

    @Test
    fun theThumbShowsWhileTheListScrollsAndDraggingItShowsTheLetterAndMovesTheList() {
        waitFor(hasText("A song 1") and hasAnyAncestor(hasTestTag(SongsTags.LIST)))
        compose.onNode(hasTestTag(ScrollThumbTags.THUMB)).assertDoesNotExist()

        compose.onNode(hasTestTag(SongsTags.LIST)).performTouchInput { swipeUp() }
        waitFor(hasTestTag(ScrollThumbTags.THUMB))

        val listHeight = compose.onNode(hasTestTag(SongsTags.LIST)).fetchSemanticsNode().size.height.toFloat()
        compose.onNode(hasTestTag(ScrollThumbTags.THUMB)).performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + listHeight))
        }
        waitFor(hasTestTag(ScrollThumbTags.BUBBLE) and hasText("Z"))
        compose.onNode(hasTestTag(ScrollThumbTags.THUMB)).performTouchInput { up() }

        waitFor(hasText("Z song 12") and hasAnyAncestor(hasTestTag(SongsTags.LIST)))
        compose.onNode(hasText("A song 1") and hasAnyAncestor(hasTestTag(SongsTags.LIST))).assertDoesNotExist()
    }

    @Test
    fun theThumbWorksOverAGridToo() {
        tab(R.string.tab_albums)
        showAsGrid()
        waitFor(hasTestTag(LibraryTags.GRID))

        compose.onNode(hasTestTag(LibraryTags.GRID)).performTouchInput { swipeUp() }
        waitFor(hasTestTag(ScrollThumbTags.THUMB))
        val height = compose.onNode(hasTestTag(LibraryTags.GRID)).fetchSemanticsNode().size.height.toFloat()
        compose.onNode(hasTestTag(ScrollThumbTags.THUMB)).performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + height))
        }
        waitFor(hasTestTag(ScrollThumbTags.BUBBLE) and hasText("Z"))
        compose.onNode(hasTestTag(ScrollThumbTags.THUMB)).performTouchInput { up() }
    }

    @Test
    fun albumsAndArtistsChooseListOrGridEachOnTheirOwnAndKeepTheChoice() {
        tab(R.string.tab_albums)
        waitFor(hasText("A album 1") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        showAsGrid()
        waitFor(hasText("A album 1") and hasAnyAncestor(hasTestTag(LibraryTags.GRID)))
        compose.onNode(hasTestTag(LibraryTags.LIST)).assertDoesNotExist()

        tab(R.string.tab_artists)
        waitFor(hasText("A artist 1") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        showAsGrid()
        waitFor(hasText("A artist 1") and hasAnyAncestor(hasTestTag(LibraryTags.GRID)))

        val sorts = ListSorts(TestFiles.targetContext)
        assertThat(runBlocking { sorts.grid(ListName.ALBUMS).first() }).isTrue()
        assertThat(runBlocking { sorts.grid(ListName.ARTISTS).first() }).isTrue()
        assertThat(runBlocking { sorts.grid(ListName.SONGS).first() }).isFalse()

        compose.activityRule.scenario.recreate()
        waitFor(hasText("A artist 1") and hasAnyAncestor(hasTestTag(LibraryTags.GRID)))
        tab(R.string.tab_albums)
        waitFor(hasText("A album 1") and hasAnyAncestor(hasTestTag(LibraryTags.GRID)))

        showAsGrid()
        waitFor(hasText("A album 1") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        assertThat(runBlocking { sorts.grid(ListName.ALBUMS).first() }).isFalse()
    }

    @Test
    fun theMenuSaysWhetherTheListShowsAsAGrid() {
        tab(R.string.tab_albums)
        waitFor(hasText("A album 1") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        compose.onNode(hasContentDescription(text(R.string.library_sort))).performClick()
        waitFor(hasText(text(R.string.sort_show_as_grid)) and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        tap(hasText(text(R.string.sort_show_as_grid)))
        waitFor(hasText("A album 1") and hasAnyAncestor(hasTestTag(LibraryTags.GRID)))

        compose.onNode(hasContentDescription(text(R.string.library_sort))).performClick()
        waitFor(hasText(text(R.string.sort_show_as_grid)) and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        waitFor(hasText(text(R.string.sort_reverse)) and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test
    fun anArtistPageShowsItsAlbumsAsTheAlbumsTabDoes() {
        tab(R.string.tab_albums)
        showAsGrid()
        waitFor(hasTestTag(LibraryTags.GRID))
        tab(R.string.tab_artists)
        tap(hasText("A artist 1") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))

        waitFor(hasText("A album 1") and hasAnyAncestor(hasTestTag(LibraryTags.GRID)))
    }
}
