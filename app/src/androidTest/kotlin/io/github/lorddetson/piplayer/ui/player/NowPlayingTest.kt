package io.github.lorddetson.piplayer.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.ui.player.TestPlayer.Call
import io.github.lorddetson.piplayer.ui.player.TestPlayer.TestSong
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Now Playing on a test player: what it shows, what its controls ask of the player, and its labels (§3.3, §3.8). */
@RunWith(AndroidJUnit4::class)
class NowPlayingTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val flac = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_FLAC)
        .setPcmEncoding(C.ENCODING_PCM_24BIT)
        .setSampleRate(96_000)
        .setChannelCount(2)
        .build()

    private val halo = TestSong("Halo", "Beyoncé", "I Am... Sasha Fierce", durationMs = 225_000, format = flac)
    private val other = TestSong("If I Were a Boy", "Beyoncé", durationMs = 249_000)

    private var closed = false

    private fun show(
        songs: List<TestSong> = listOf(halo),
        positionMs: Long = 62_000,
        playing: Boolean = false,
        cover: Boolean = false,
        layout: NowPlayingLayout = NowPlayingLayout.STANDARD,
        onOpenQueue: (() -> Unit)? = null,
    ): TestPlayer {
        val player = compose.runOnUiThread { TestPlayer(songs, playing).also { it.positionMs = positionMs } }
        val image = if (cover) TestImages.cover() else null
        compose.setContent {
            PiPlayerTheme {
                NowPlaying(player, image, onClose = { closed = true }, onOpenQueue = onOpenQueue, layout = layout)
            }
        }
        return player
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    fun editingInPlaceAndSavingOneChangeKeepTheOtherControlsWhereTheyWere() =
        withStockPlayerTheme(context, "now_playing_panel_", "ui_canvas_") {
            show(playing = true, cover = true, layout = NowPlayingLayout.EXPANDED)
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            val pause = compose.onNodeWithContentDescription(text(R.string.player_pause))
            val originalCover = cover.fetchSemanticsNode().boundsInRoot
            val originalPause = pause.fetchSemanticsNode().boundsInRoot

            cover.performTouchInput { longClick() }
            compose.onNodeWithTag("ui_edit_mode").performClick()
            val editCover = compose.onNodeWithTag("ui_component_now:cover").fetchSemanticsNode().boundsInRoot
            val editPause = compose.onNodeWithTag("ui_component_now:play_pause").fetchSemanticsNode().boundsInRoot
            assertThat((editCover.center - originalCover.center).getDistance()).isLessThan(2f)
            assertThat((editPause.center - originalPause.center).getDistance()).isLessThan(2f)

            compose.onNodeWithTag("ui_resize_now:cover").performTouchInput {
                swipe(center, center + Offset(65f, 65f), durationMillis = 420)
            }
            compose.onNodeWithTag("ui_edit_done").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ui_edit_done")).fetchSemanticsNodes().isEmpty() }
            assertThat(cover.fetchSemanticsNode().boundsInRoot.width).isGreaterThan(originalCover.width)
            assertThat(pause.fetchSemanticsNode().boundsInRoot).isEqualTo(originalPause)
        }

    @Test
    fun theInlineEditorSavesChangesToTheCurrentThemeDuringPlayback() =
        withStockPlayerTheme(context, "now_playing_panel_", "ui_canvas_") {
            val player = show(playing = true, cover = true, layout = NowPlayingLayout.EXPANDED)
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            cover.performTouchInput { longClick() }
            compose.onNodeWithTag("ui_edit_mode").performClick()
            compose.onNodeWithTag("ui_canvas").assertIsDisplayed()
            compose.onNodeWithTag("ui_component_now:cover").performClick()
            compose.onNodeWithTag("ui_edit_hide").performClick()
            compose.onNodeWithTag("ui_edit_done").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ui_edit_done")).fetchSemanticsNodes().isEmpty() }
            cover.assertDoesNotExist()
            compose.onNodeWithContentDescription(text(R.string.player_close_now_playing)).assertIsDisplayed()

            val appearance = AppearanceSettings(context)
            runBlocking { appearance.setTheme(ThemeMode.DARK) }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(PlayerTags.COVER), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { appearance.setTheme(ThemeMode.SYSTEM) }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(PlayerTags.COVER), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("ui_canvas").assertIsDisplayed()
            assertThat(compose.runOnUiThread { player.playWhenReady }).isTrue()
            assertThat(compose.runOnUiThread { player.calls }).isEmpty()
        }

    @Test
    fun buttonsCanMoveBetweenRowsAndBeHiddenAndRestoredDuringPlayback() = withStockPlayerTheme(context, "now_playing_panel_") {
        var queueOpened = false
        val player = show(playing = true, onOpenQueue = { queueOpened = true })
        val queue = compose.onNodeWithContentDescription(text(R.string.queue_open))
        queue.performTouchInput { longClick() }
        compose.onNodeWithTag("player_element_move").performClick()
        compose.onNodeWithTag("player_element_move_to_previous").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(text(R.string.queue_open)) and hasAnyAncestor(hasTestTag("now_playing_transport")))
                .fetchSemanticsNodes().size == 1
        }
        queue.performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("player_element_hide").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(text(R.string.queue_open))).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("now_playing_restore").performScrollTo().performClick()
        compose.onNodeWithTag("player_element_restore_queue").performClick()
        queue.performScrollTo().performClick()
        assertThat(queueOpened).isTrue()
        assertThat(compose.runOnUiThread { player.playWhenReady }).isTrue()
        assertThat(compose.runOnUiThread { player.calls }).isEmpty()
    }

    @Test
    fun draggingSeeksAndHoldingOpensTheProgressMenuWithoutSeeking() = withStockPlayerTheme(context, "now_playing_panel_") {
        val player = show(playing = true, layout = NowPlayingLayout.EXPANDED)
        val seekBar = compose.onNodeWithTag(PlayerTags.SEEK_BAR).performScrollTo()
        seekBar.performTouchInput {
            swipe(Offset(width * 0.28f, center.y), Offset(width * 0.65f, center.y), durationMillis = 300)
        }
        val callsAfterDrag = compose.runOnUiThread { player.calls.toList() }
        val seek = callsAfterDrag.single() as Call.Seek
        assertThat(seek.positionMs).isBetween(120_000L, 170_000L)

        seekBar.performTouchInput { longClick() }
        compose.onNodeWithTag("player_element_move").assertIsDisplayed()
        assertThat(compose.runOnUiThread { player.calls.toList() }).isEqualTo(callsAfterDrag)
    }

    @Test
    fun theCoverVisibilityReturnsWithTheSelectedTheme() = withStockPlayerTheme(context, "now_playing_panel_") {
        show(playing = true, cover = true)
        val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
        cover.performTouchInput { longClick() }
        compose.onNodeWithTag("player_element_hide").performClick()
        fun shown() = compose.onAllNodes(hasTestTag(PlayerTags.COVER), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        compose.waitUntil(5_000) { !shown() }
        runBlocking { AppearanceSettings(context).setTheme(ThemeMode.DARK) }
        compose.waitUntil(5_000) { shown() }
        runBlocking { AppearanceSettings(context).setTheme(ThemeMode.SYSTEM) }
        compose.waitUntil(5_000) { !shown() }
    }

    @Test
    fun minimalLayoutKeepsMainControlsAndRevealsOtherActionsOnTheSameScreen() {
        show(layout = NowPlayingLayout.MINIMAL, onOpenQueue = {})

        compose.onNodeWithContentDescription(text(R.string.player_play)).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTags.SEEK_BAR).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.player_shuffle)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.queue_open)).assertDoesNotExist()

        compose.onNodeWithTag("now-playing-show-controls").performClick()

        compose.onNodeWithContentDescription(text(R.string.player_shuffle)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.queue_open)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.player_sleep_timer)).assertExists()

        compose.onNodeWithTag("now-playing-show-controls").performClick()
        compose.onNodeWithContentDescription(text(R.string.player_shuffle)).assertDoesNotExist()
    }

    @Test
    fun expandedLayoutPutsCompactArtworkBesideTheTitleAndKeepsActionsInView() {
        show(layout = NowPlayingLayout.EXPANDED, onOpenQueue = {})

        val artwork = compose.onNodeWithTag(PlayerTags.COVER_PLACEHOLDER).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Halo").fetchSemanticsNode().boundsInRoot
        assertThat(artwork.center.y).isBetween(title.top - artwork.height / 2f, title.bottom + artwork.height / 2f)
        assertThat(artwork.right <= title.left || title.right <= artwork.left).isTrue()
        compose.onNodeWithContentDescription(text(R.string.queue_open)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.player_shuffle)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.player_sleep_timer)).assertIsDisplayed()
    }

    @Test
    fun expandedLayoutKeepsControlsReachableInAShortNarrowWindowWithLargeText() {
        val player = compose.runOnUiThread { TestPlayer(listOf(halo, other), playing = false) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                Box(Modifier.size(width = 288.dp, height = 398.dp)) {
                    PiPlayerTheme {
                        NowPlaying(player, null, onClose = {}, onOpenQueue = {}, layout = NowPlayingLayout.EXPANDED)
                    }
                }
            }
        }

        for (label in listOf(R.string.player_play, R.string.player_repeat, R.string.player_sleep_timer)) {
            compose.onNodeWithTag(PlayerTags.NOW_PLAYING).performScrollToNode(hasContentDescription(text(label)))
            compose.onNodeWithContentDescription(text(label)).assertIsDisplayed()
        }
    }

    @Test
    fun showsTheSongItsFormatAndItsTimes() {
        show()

        compose.onNodeWithText("Halo").assertIsDisplayed().assert(isHeading())
        compose.onNodeWithText("Beyoncé").assertIsDisplayed()
        compose.onNodeWithText("I Am... Sasha Fierce").assertIsDisplayed()
        val titleCenter = compose.onNodeWithText("Halo").fetchSemanticsNode().boundsInRoot.center.x
        for (label in listOf("Beyoncé", "I Am... Sasha Fierce")) {
            val labelCenter = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.center.x
            assertThat(kotlin.math.abs(labelCenter - titleCenter)).isLessThanOrEqualTo(1.5f)
        }
        compose.onNodeWithText("FLAC · 24 bit · 96 kHz").assertIsDisplayed()
        compose.onNodeWithText("1:02").assertIsDisplayed()
        compose.onNodeWithText("-2:43").assertIsDisplayed()
        compose.onNodeWithTag(PlayerTags.NOW_PLAYING)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, text(R.string.player_now_playing)))
    }

    @Test
    fun aSongWithoutArtistAlbumOrLengthSaysSo() {
        show(listOf(TestSong("Untitled")), positionMs = 1_000)

        compose.onNodeWithText("Untitled").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.songs_unknown_artist)).assertIsDisplayed()
        compose.onNodeWithText("--:--").assertIsDisplayed()
        compose.onNodeWithTag(PlayerTags.SEEK_BAR).assertIsNotEnabled()
    }

    @Test
    fun playAndPauseFollowThePlayer() {
        val player = show()

        compose.onNodeWithContentDescription(text(R.string.player_play)).performClick()

        assertThat(compose.runOnUiThread { player.calls.toList() }).containsExactly(Call.PlayWhenReady(true))
        compose.onNodeWithContentDescription(text(R.string.player_pause)).performClick()
        assertThat(compose.runOnUiThread { player.calls.toList() })
            .containsExactly(Call.PlayWhenReady(true), Call.PlayWhenReady(false))
    }

    @Test
    fun previousRestartsTheSongAfterItsFirstSecondsAndNextSkips() {
        val player = show(listOf(halo, other), positionMs = 5_000)

        compose.onNodeWithContentDescription(text(R.string.player_previous)).performClick()
        compose.onNodeWithContentDescription(text(R.string.player_next)).performClick()

        assertThat(compose.runOnUiThread { player.calls.toList() }).containsExactly(
            Call.Seek(0, 0, Player.COMMAND_SEEK_TO_PREVIOUS),
            Call.Seek(1, C.TIME_UNSET, Player.COMMAND_SEEK_TO_NEXT),
        )
    }

    @Test
    fun previousEarlyInTheSongGoesToThePreviousOne() {
        val player = show(listOf(halo, other), positionMs = 1_000)
        compose.runOnUiThread { player.update { setCurrentMediaItemIndex(1) } }

        compose.onNodeWithContentDescription(text(R.string.player_previous)).performClick()

        assertThat(compose.runOnUiThread { player.calls.toList() })
            .containsExactly(Call.Seek(0, C.TIME_UNSET, Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun shuffleIsAToggleThatStatesItsMode() {
        val player = show()
        val shuffle = compose.onNodeWithContentDescription(text(R.string.player_shuffle))

        shuffle.assertIsOff().assert(hasStateDescription(text(R.string.player_state_off)))
        shuffle.performClick()

        shuffle.assertIsOn().assert(hasStateDescription(text(R.string.player_state_on)))
        assertThat(compose.runOnUiThread { player.calls.toList() }).containsExactly(Call.Shuffle(true))
    }

    @Test
    fun repeatGoesOffAllOneAndStatesItsMode() {
        val player = show()
        val repeat = compose.onNodeWithContentDescription(text(R.string.player_repeat))

        repeat.assert(hasStateDescription(text(R.string.player_state_off)))
            .assert(hasClickLabel(text(R.string.player_change_repeat_mode)))
        // As an accessibility service clicks: the label must not cost the button its action.
        repeat.performSemanticsAction(SemanticsActions.OnClick)
        repeat.assert(hasStateDescription(text(R.string.player_repeat_state_all)))
        repeat.performClick()
        repeat.assert(hasStateDescription(text(R.string.player_repeat_state_one)))
        repeat.performClick()
        repeat.assert(hasStateDescription(text(R.string.player_state_off)))

        assertThat(compose.runOnUiThread { player.calls.toList() }).containsExactly(
            Call.Repeat(Player.REPEAT_MODE_ALL),
            Call.Repeat(Player.REPEAT_MODE_ONE),
            Call.Repeat(Player.REPEAT_MODE_OFF),
        )
    }

    @Test
    fun theSeekBarStatesThePositionInWordsAndSeeks() {
        val player = show()
        val seekBar = compose.onNodeWithTag(PlayerTags.SEEK_BAR)
        val spoken = text(R.string.player_position_state).format(
            PlayerTime.spoken(62_000, Locale.getDefault()),
            PlayerTime.spoken(225_000, Locale.getDefault()),
        )

        seekBar.assertIsEnabled()
            .assertContentDescriptionEquals(text(R.string.player_position))
            .assert(hasStateDescription(spoken))
        val range = seekBar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range
        seekBar.performSemanticsAction(SemanticsActions.SetProgress) { it((range.start + range.endInclusive) / 2) }

        val seek = compose.runOnUiThread { player.calls.single() } as Call.Seek
        assertThat(seek.command).isEqualTo(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        assertThat(seek.positionMs).isBetween(112_000L, 113_000L)
    }

    @Test
    fun positionsInWordsReadAsTheyAreSpoken() {
        assertThat(PlayerTime.spoken(62_000, Locale.US)).isEqualTo("1 minute, 2 seconds")
        assertThat(PlayerTime.spoken(225_000, Locale.US)).isEqualTo("3 minutes, 45 seconds")
        assertThat(PlayerTime.spoken(3_605_000, Locale.US)).isEqualTo("1 hour, 5 seconds")
        assertThat(PlayerTime.spoken(400, Locale.US)).isEqualTo("0 seconds")
    }

    @Test
    fun theCoverIsShownAndDecorative() {
        show(cover = true)

        compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true).assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        compose.onNodeWithTag(PlayerTags.COVER_PLACEHOLDER, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun withoutACoverAPlaceholderStandsIn() {
        show(cover = false)

        compose.onNodeWithTag(PlayerTags.COVER_PLACEHOLDER, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theCollapseButtonClosesNowPlaying() {
        show()

        compose.onNodeWithContentDescription(text(R.string.player_close_now_playing)).performClick()

        assertThat(closed).isTrue()
    }

    @Test
    fun everyControlHasALabel() {
        show(listOf(halo, other))

        val controls = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertThat(controls).hasSizeGreaterThanOrEqualTo(6)
        controls.forEach { node ->
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertThat(label).describedAs("the labels of $node").isNotEmpty()
        }
        compose.onNode(hasContentDescription(text(R.string.player_shuffle))).assertIsDisplayed()
    }
}
