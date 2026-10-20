package io.github.lorddetson.piplayer.ui.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.ui.player.TestPlayer.Call
import io.github.lorddetson.piplayer.ui.player.TestPlayer.TestSong
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
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
