package io.github.lorddetson.piplayer.ui.player

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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.ui.player.TestPlayer.Call
import io.github.lorddetson.piplayer.ui.player.TestPlayer.TestSong
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.util.Locale
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
    ): TestPlayer {
        val player = compose.runOnUiThread { TestPlayer(songs, playing).also { it.positionMs = positionMs } }
        val image = if (cover) TestImages.cover() else null
        compose.setContent {
            PiPlayerTheme { NowPlaying(player, image, onClose = { closed = true }) }
        }
        return player
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    fun showsTheSongItsFormatAndItsTimes() {
        show()

        compose.onNodeWithText("Halo").assertIsDisplayed().assert(isHeading())
        compose.onNodeWithText("Beyoncé").assertIsDisplayed()
        compose.onNodeWithText("I Am... Sasha Fierce").assertIsDisplayed()
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
