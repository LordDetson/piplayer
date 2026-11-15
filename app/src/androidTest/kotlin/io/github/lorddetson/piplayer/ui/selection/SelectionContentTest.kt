package io.github.lorddetson.piplayer.ui.selection

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.SongLinks
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.ui.songs.SongMenu
import io.github.lorddetson.piplayer.ui.songs.SongSelection
import io.github.lorddetson.piplayer.ui.songs.SongsContent
import io.github.lorddetson.piplayer.ui.songs.SongsState
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A song row while selecting, and the selection bar (docs/library.md §20.2). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SelectionContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun count(n: Int) = context.resources.getQuantityString(R.plurals.selection_count, n, n)

    private fun song(id: Long, title: String, disc: Int? = null) =
        Song(id, "file:///$id.mp3", title, "Artist", "Album", 60_000, discNumber = disc)

    private val songs = listOf(song(1, "Alpha", disc = 1), song(2, "Bravo", disc = 1), song(3, "Charlie", disc = 2))
    private val menu = SongMenu(
        links = { SongLinks(null, emptyList(), FolderKey("external_primary", "Music/")) },
        openAlbum = {},
        openArtist = {},
        openFolder = { _, _ -> },
    )

    private var played = mutableListOf<Long>()
    private lateinit var selected: () -> Set<Long>

    /** The songs with a selection held here, as a state holder holds it; a header item before them. */
    private fun show(discHeaders: Boolean = false) {
        compose.setContent {
            var keys by remember { mutableStateOf(emptySet<Long>()) }
            selected = { keys }
            val selection = SongSelection(
                selecting = keys.isNotEmpty(),
                isSelected = { it in keys },
                toggle = { id -> keys = if (id in keys) keys - id else keys + id },
            )
            SongsContent(
                SongsState.Loaded(songs),
                {},
                {},
                onSongClick = { _, song -> played += song.id },
                menu = menu,
                discHeaders = discHeaders,
                headerItems = 1,
                selection = selection,
            ) {
                item(key = "header") { Text("Header item") }
            }
        }
    }

    private fun row(title: String) = compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))

    @Test
    fun aLongPressStartsSelectionAndATapTogglesWithoutPlaying() {
        show()

        row("Bravo").performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(selected()).containsExactly(2L)

        row("Alpha").performClick()
        row("Bravo").performClick()
        compose.waitForIdle()

        assertThat(selected()).containsExactly(1L)
        assertThat(played).isEmpty()
        // Deselecting the last row ends selection: a tap plays again.
        row("Alpha").performClick()
        row("Charlie").performClick()
        compose.waitForIdle()
        assertThat(selected()).isEmpty()
        assertThat(played).containsExactly(3L)
    }

    @Test
    fun whileSelectingARowHasACheckBoxAndNoMenuButton() {
        show()
        compose.onNode(hasContentDescription(text(R.string.song_menu, "Alpha"))).assertExists()

        row("Alpha").performTouchInput { longClick() }

        compose.waitUntilDoesNotExist(hasContentDescription(text(R.string.song_menu, "Alpha")), timeoutMillis = 5_000)
        compose.onNode(hasContentDescription(text(R.string.song_menu, "Bravo"))).assertDoesNotExist()
        val alpha = row("Alpha").fetchSemanticsNode().config
        assertThat(alpha.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Checkbox)
        assertThat(alpha.getOrNull(SemanticsProperties.ToggleableState)).isEqualTo(ToggleableState.On)
        assertThat(row("Bravo").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)).isEqualTo(ToggleableState.Off)
    }

    @Test
    fun outsideSelectionARowOffersSelectAsItsLongPressAndAsAnAccessibilityAction() {
        show()
        val bravo = row("Bravo").fetchSemanticsNode().config
        assertThat(bravo.getOrNull(SemanticsActions.OnLongClick)?.label).isEqualTo(text(R.string.selection_select))

        row("Bravo").performCustomAccessibilityActionWithLabel(text(R.string.selection_select))
        compose.waitForIdle()

        assertThat(selected()).containsExactly(2L)
    }

    @Test
    fun discHeadersAndHeaderItemsAreNotSelected() {
        show(discHeaders = true)
        compose.onNodeWithText(text(R.string.album_disc, 2)).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.album_disc, 2)).performTouchInput { longClick() }
        compose.onNodeWithText("Header item").performTouchInput { longClick() }
        compose.waitForIdle()

        assertThat(selected()).isEmpty()
    }

    @Test
    fun theBarCountsSaysItLiveAndTurnsSelectAllIntoDeselectAll() {
        var state by mutableStateOf(SelectionUi(Selection.Only(setOf(1L)), count = 1, everything = false))
        var closed = 0
        var all = 0
        var none = 0
        var acted = false
        compose.setContent {
            SelectionBar(state, onClose = { closed++ }, onSelectAll = { all++ }, onDeselectAll = { none++ }) { close ->
                DropdownMenuItem(text = { Text("An action") }, onClick = { close(); acted = true })
            }
        }

        val title = compose.onNodeWithTag(SelectionTags.TITLE, useUnmergedTree = true)
        title.assertIsDisplayed()
        compose.onNode(hasText(count(1))).assertIsDisplayed()
        assertThat(title.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)).isEqualTo(LiveRegionMode.Polite)
        compose.onNode(hasText(text(R.string.selection_select_all))).performClick()
        assertThat(all).isEqualTo(1)

        state = SelectionUi(selectAll<Long>(""), count = 3, everything = true)
        compose.onNode(hasText(count(3))).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.selection_deselect_all))).performClick()
        assertThat(none).isEqualTo(1)

        compose.onNode(hasContentDescription(text(R.string.selection_close))).performClick()
        assertThat(closed).isEqualTo(1)
        compose.onNode(hasTestTag(SelectionTags.MENU)).performClick()
        compose.onNode(hasText("An action")).performClick()
        assertThat(acted).isTrue()
    }
}
