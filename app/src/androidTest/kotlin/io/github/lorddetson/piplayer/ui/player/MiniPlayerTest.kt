package io.github.lorddetson.piplayer.ui.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.MiniPlayerElement
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.miniPlayerPanels
import io.github.lorddetson.piplayer.ui.player.TestPlayer.Call
import io.github.lorddetson.piplayer.ui.player.TestPlayer.TestSong
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The mini player on a test player (§3.2, §3.8). */
@RunWith(AndroidJUnit4::class)
class MiniPlayerTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private var opened = false

    private fun show(songs: List<TestSong>, cover: Boolean = false): TestPlayer {
        val player = compose.runOnUiThread { TestPlayer(songs).also { it.positionMs = 30_000 } }
        val image = if (cover) TestImages.cover() else null
        compose.setContent {
            PiPlayerTheme { MiniPlayer(player, image, onOpen = { opened = true }) }
        }
        return player
    }

    private val halo = TestSong("Halo", "Beyoncé", durationMs = 225_000)

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    fun editorKeepsMiniPlayerTitleArtistAndAlbumIndependent() =
        withStockPlayerTheme(context, "mini_player_panel_", "ui_canvas_") {
            show(listOf(halo.copy(album = "Sasha Fierce")), cover = true)
            compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true).performTouchInput { longClick() }
            compose.onNodeWithTag("ui_edit_mode").performClick()

            for (id in listOf("title", "artist", "album")) {
                compose.onNodeWithTag("ui_component_mini:$id").assertIsDisplayed()
            }
            compose.onNodeWithTag("ui_component_mini:track_info").assertDoesNotExist()
            compose.onNodeWithTag("ui_component_mini:album").performClick()
            compose.onNodeWithTag("ui_edit_hide").performClick()
            compose.onNodeWithTag("ui_edit_done").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ui_edit_done")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Sasha Fierce", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Halo", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Beyoncé", useUnmergedTree = true).assertIsDisplayed()
            assertThat(opened).isFalse()
        }

    @Test
    fun theInlineEditorKeepsItsOwnLayoutAndTheTransportControlStillWorks() =
        withStockPlayerTheme(context, "mini_player_panel_", "ui_canvas_") {
            val player = show(listOf(halo), cover = true)
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            val play = compose.onNodeWithContentDescription(text(R.string.player_play))
            val originalCover = cover.fetchSemanticsNode().boundsInRoot
            val originalPlay = play.fetchSemanticsNode().boundsInRoot
            cover.performTouchInput { longClick() }
            compose.onNodeWithTag("ui_edit_mode").performClick()
            val editingCover = compose.onNodeWithTag("ui_component_mini:cover")
            val editingPlay = compose.onNodeWithTag("ui_component_mini:play_pause")
            assertThat((editingCover.fetchSemanticsNode().boundsInRoot.center - originalCover.center).getDistance()).isLessThan(2f)
            assertThat((editingPlay.fetchSemanticsNode().boundsInRoot.center - originalPlay.center).getDistance()).isLessThan(2f)
            editingCover.performClick()
            compose.onNodeWithTag("ui_edit_hide").performClick()
            compose.onNodeWithTag("ui_edit_done").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ui_edit_done")).fetchSemanticsNodes().isEmpty() }
            cover.assertDoesNotExist()
            assertThat(play.fetchSemanticsNode().boundsInRoot).isEqualTo(originalPlay)
            assertThat(compose.runOnUiThread { player.calls }).isEmpty()

            val appearance = AppearanceSettings(context)
            runBlocking { appearance.setTheme(ThemeMode.DARK) }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(PlayerTags.COVER), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { appearance.setTheme(ThemeMode.SYSTEM) }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(PlayerTags.COVER), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("ui_canvas").assertIsDisplayed()
            play.performClick()
            assertThat(compose.runOnUiThread { player.calls }).containsExactly(Call.PlayWhenReady(true))
        }

    @Test
    fun movingHidingAndRestoringMiniPlayerElementsKeepsTheirTapActions() = withStockPlayerTheme(context, "mini_player_panel_") {
        val player = show(listOf(halo), cover = true)
        val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
        val title = compose.onNodeWithText("Halo", useUnmergedTree = true)
        cover.performTouchInput { longClick() }
        compose.onNodeWithTag("mini_element_move").performClick()
        compose.onNodeWithTag("mini_element_move_to_track_info").performClick()
        compose.waitUntil(5_000) { cover.fetchSemanticsNode().boundsInRoot.center.x > title.fetchSemanticsNode().boundsInRoot.center.x }
        assertThat(opened).isFalse()

        val play = compose.onNodeWithContentDescription(text(R.string.player_play))
        play.performTouchInput { longClick() }
        compose.onNodeWithTag("mini_element_hide").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(text(R.string.player_play))).fetchSemanticsNodes().isEmpty() }
        assertThat(compose.runOnUiThread { player.calls }).isEmpty()
        compose.onNodeWithTag("mini_player_restore").performClick()
        compose.onNodeWithTag("mini_element_restore_play_pause").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(text(R.string.player_play))).fetchSemanticsNodes().isNotEmpty() }
        play.performClick()
        assertThat(compose.runOnUiThread { player.calls.toList() }).containsExactly(Call.PlayWhenReady(true))
        assertThat(opened).isFalse()
        title.performTouchInput { click() }
        assertThat(opened).isTrue()
    }

    @Test
    fun theBarStillOpensAndCanRestoreAComponentWhenEverythingWasHidden() = withStockPlayerTheme(context, "mini_player_panel_") {
        val hidden = MiniPlayerElement.entries.toSet()
        runBlocking { miniPlayerPanels.forContext(context).edit(ThemeMode.SYSTEM.stored) { it.copy(hidden = hidden) } }
        val player = show(listOf(halo), cover = true)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("mini_player_restore")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Halo", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("mini_player_progress", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasClickLabel(text(R.string.player_open_now_playing))).performClick()
        assertThat(opened).isTrue()
        opened = false
        compose.onNodeWithTag("mini_player_restore").performClick()
        compose.onNodeWithTag("mini_element_restore_track_info").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Halo"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertThat(compose.runOnUiThread { player.calls }).isEmpty()
        assertThat(opened).isFalse()
    }

    @Test
    fun switchingThemesRestoresProgressPlacementAndVisibility() = withStockPlayerTheme(context, "mini_player_panel_") {
        show(listOf(halo), cover = true)
        val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
        cover.performTouchInput { longClick() }
        compose.onNodeWithTag("mini_element_hide").performClick()

        val progress = compose.onNodeWithTag("mini_player_progress", useUnmergedTree = true)
        val title = compose.onNodeWithText("Halo", useUnmergedTree = true)
        fun belowTitle() = progress.fetchSemanticsNode().boundsInRoot.center.y > title.fetchSemanticsNode().boundsInRoot.center.y
        progress.performTouchInput { longClick() }
        compose.onNodeWithTag("mini_element_move").performClick()
        compose.onNodeWithTag("mini_progress_bottom").performClick()
        compose.waitUntil(5_000) { belowTitle() }
        cover.assertDoesNotExist()

        val appearance = AppearanceSettings(context)
        runBlocking { appearance.setTheme(ThemeMode.DARK) }
        compose.waitUntil(5_000) { !belowTitle() }
        cover.assertExists()
        runBlocking { appearance.setTheme(ThemeMode.SYSTEM) }
        compose.waitUntil(5_000) { belowTitle() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(PlayerTags.COVER), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun showsTitleArtistAndPlayPause() {
        show(listOf(halo), cover = true)

        compose.onNodeWithTag(PlayerTags.MINI_PLAYER).assertIsDisplayed()
        compose.onNode(hasText("Halo"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("Beyoncé"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.player_play)).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun tappingTheBarOpensNowPlaying() {
        show(listOf(halo))

        compose.onNode(hasClickLabel(text(R.string.player_open_now_playing)))
            .assert(hasText("Halo"))
            .performClick()

        assertThat(opened).isTrue()
    }

    @Test
    fun playPauseIsAControlOfItsOwn() {
        val player = show(listOf(halo))

        compose.onNodeWithContentDescription(text(R.string.player_play)).performClick()

        assertThat(compose.runOnUiThread { player.calls.toList() }).containsExactly(Call.PlayWhenReady(true))
        assertThat(opened).isFalse()
    }

    @Test
    fun theProgressLineIsHiddenFromAccessibilityServices() {
        show(listOf(halo))

        compose.onNodeWithTag(PlayerTags.MINI_PLAYER).assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun anEmptyQueueShowsNoBar() {
        show(emptyList())

        compose.onNodeWithTag(PlayerTags.MINI_PLAYER).assertDoesNotExist()
    }

    @Test
    fun noBarBeforeTheControllerConnects() {
        compose.setContent { PiPlayerTheme { MiniPlayer(null, null, onOpen = {}) } }

        compose.onNodeWithTag(PlayerTags.MINI_PLAYER).assertDoesNotExist()
    }
}
