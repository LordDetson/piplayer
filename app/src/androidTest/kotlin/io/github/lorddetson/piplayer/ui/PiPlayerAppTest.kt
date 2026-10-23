package io.github.lorddetson.piplayer.ui

import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.playback.PlaybackTesting
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.ui.player.PlayerTags
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The app with the playback service: the mini player, Now Playing, back and predictive back, edge-to-edge, and a cover
 * from the file (docs/playback.md §3.1 to §3.4).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PiPlayerAppTest {

    private val songs = SongSourceRule {
        listOf(
            SongSourceRule.song(1, "Alpha", PlaybackTesting.LONG_FIXTURE),
            SongSourceRule.song(2, "Bravo", "playback/cover-front.mp3"),
        )
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(songs).around(compose)

    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private val inMiniPlayer = hasAnyAncestor(hasTestTag(PlayerTags.MINI_PLAYER))
    private val inSongList = hasAnyAncestor(hasTestTag(SongsTags.LIST))

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    /** Taps [title] in the list and pauses once it plays, so that nothing moves on screen. */
    private fun playAndPause(title: String) {
        /*
         * A restored mini player can show the same title as the list (docs/playback.md §4.4); only the list row is
         * the tap target here.
         */
        compose.waitUntilExactlyOneExists(hasText(title) and inSongList, timeoutMillis = 10_000)
        compose.onNode(hasText(title) and inSongList).performClick()
        controller.waitUntil("$title plays") { isPlaying && mediaMetadata.title == title }
        onMain { controller.pause() }
        compose.waitUntilExactlyOneExists(hasTestTag(PlayerTags.MINI_PLAYER), timeoutMillis = 10_000)
    }

    private fun openNowPlaying() {
        compose.onNode(hasClickLabel(text(R.string.player_open_now_playing))).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(PlayerTags.NOW_PLAYING), timeoutMillis = 10_000)
    }

    private fun nowPlayingBounds(): Rect =
        compose.onNodeWithTag(PlayerTags.NOW_PLAYING).getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    private fun back(event: (dispatcher: androidx.activity.OnBackPressedDispatcher) -> Unit) =
        compose.runOnUiThread { event(compose.activity.onBackPressedDispatcher) }

    private fun gesture(progress: Float) = BackEventCompat(10f, 800f, progress, BackEventCompat.EDGE_LEFT)

    @Test
    fun aTappedSongBringsUpTheMiniPlayerWhichOpensNowPlaying() {
        playAndPause("Alpha")

        compose.onNode(hasClickLabel(text(R.string.player_open_now_playing))).assert(hasText("Alpha"))
        openNowPlaying()

        compose.onNodeWithText("Album 1").assertIsDisplayed()
        compose.waitUntilExactlyOneExists(hasText("MP3 · 128 kbit/s · 44.1 kHz"), timeoutMillis = 10_000)
        back { it.onBackPressed() }
        compose.waitUntilDoesNotExist(hasTestTag(PlayerTags.NOW_PLAYING), timeoutMillis = 5_000)
        compose.onNodeWithTag(PlayerTags.MINI_PLAYER).assertIsDisplayed()
    }

    @Test
    fun thePredictiveBackGestureMovesNowPlayingAndCancellingKeepsIt() {
        playAndPause("Alpha")
        openNowPlaying()
        compose.waitForIdle()
        val full = nowPlayingBounds()

        back {
            it.dispatchOnBackStarted(gesture(0f))
            it.dispatchOnBackProgressed(gesture(0.5f))
        }
        compose.waitForIdle()
        assertThat(nowPlayingBounds()).isNotEqualTo(full)

        back { it.dispatchOnBackCancelled() }
        compose.waitUntil(timeoutMillis = 5_000) { nowPlayingBounds() == full }

        back {
            it.dispatchOnBackStarted(gesture(0f))
            it.dispatchOnBackProgressed(gesture(0.5f))
            it.onBackPressed()
        }
        compose.waitUntilDoesNotExist(hasTestTag(PlayerTags.NOW_PLAYING), timeoutMillis = 5_000)
        compose.onNodeWithTag(PlayerTags.MINI_PLAYER).assertIsDisplayed()
    }

    @Test
    fun backOnTheListIsLeftToTheSystem() {
        playAndPause("Alpha")
        openNowPlaying()
        assertThat(compose.runOnUiThread { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }).isTrue()

        compose.onNode(hasContentDescription(text(R.string.player_close_now_playing))).performClick()
        compose.waitUntilDoesNotExist(hasTestTag(PlayerTags.NOW_PLAYING), timeoutMillis = 5_000)

        assertThat(compose.runOnUiThread { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }).isFalse()
    }

    @Test
    fun theMiniPlayerReachesTheBottomEdgeWithItsControlsAboveTheNavigationBar() {
        playAndPause("Alpha")
        val insets = compose.runOnUiThread { ViewCompat.getRootWindowInsets(compose.activity.window.decorView) }!!
        val navigationBarPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        assumeTrue("this device has a navigation bar at the bottom", navigationBarPx > 0)
        val navigationBar: Dp = with(compose.density) { navigationBarPx.toDp() }

        val window = compose.onRoot().getBoundsInRoot()
        val bar = compose.onNodeWithTag(PlayerTags.MINI_PLAYER).getBoundsInRoot()
        val play = compose.onNode(hasContentDescription(text(R.string.player_play)) and inMiniPlayer).getBoundsInRoot()

        assertThat(bar.bottom.value).isCloseTo(window.bottom.value, org.assertj.core.data.Offset.offset(1f))
        assertThat(play.bottom.value).isLessThanOrEqualTo((window.bottom - navigationBar + 0.5.dp).value)
    }

    @Test
    fun aCoverFromTheFileShowsOnNowPlaying() {
        playAndPause("Bravo")

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag(PlayerTags.COVER) and inMiniPlayer, useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
        openNowPlaying()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag(PlayerTags.COVER), useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
    }
}
