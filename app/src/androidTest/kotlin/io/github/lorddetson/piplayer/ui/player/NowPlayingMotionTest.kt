package io.github.lorddetson.piplayer.ui.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.roundToInt
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NowPlayingMotionTest {
    private val motion = object : MotionDurationScale {
        @Volatile override var scaleFactor = 1f
    }
    @Volatile private var observedScale: Float? = null

    @get:Rule
    val compose = createComposeRule(effectContext = motion)

    private fun show(): TestPlayer {
        val player = compose.runOnUiThread { TestPlayer(listOf(TestPlayer.TestSong("First"), TestPlayer.TestSong("Second"))) }
        val cover = TestImages.cover()
        compose.setContent {
            LaunchedEffect(Unit) { observedScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor }
            PiPlayerTheme { NowPlaying(player, cover, {}, layout = NowPlayingLayout.EXPANDED) }
        }
        compose.waitUntil(5_000) { observedScale != null }
        assertThat(observedScale).isEqualTo(motion.scaleFactor)
        return player
    }

    private fun artworkLeftAt(y: Int): Int {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        return (0 until pixels.width - 12).first { x ->
            (x until x + 12).all { column ->
                val pixel = pixels[column, y]
                pixel.red < 0.55f && pixel.green < 0.62f && pixel.blue > 0.80f
            }
        }
    }

    @Test
    fun theIncomingCoverSlidesFromTheOppositeSideOfTheSwipe() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            val player = show()
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            val rootTop = compose.onRoot().fetchSemanticsNode().boundsInRoot.top
            val y = (cover.fetchSemanticsNode().boundsInRoot.center.y - rootTop).roundToInt()
            val restingLeft = artworkLeftAt(y)
            compose.mainClock.autoAdvance = false
            try {
                cover.performTouchInput { swipe(Offset(width * 0.9f, center.y), Offset(width * 0.1f, center.y)) }
                assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(1)
                compose.mainClock.advanceTimeBy(64)
                assertThat(artworkLeftAt(y)).isGreaterThan(restingLeft)
                compose.mainClock.advanceTimeBy(320)
                assertThat(artworkLeftAt(y)).isEqualTo(restingLeft)

                cover.performTouchInput { swipe(Offset(width * 0.1f, center.y), Offset(width * 0.9f, center.y)) }
                assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(0)
                compose.mainClock.advanceTimeBy(64)
                assertThat(artworkLeftAt(y)).isLessThan(restingLeft)
                compose.mainClock.advanceTimeBy(320)
                assertThat(artworkLeftAt(y)).isEqualTo(restingLeft)
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }

    @Test
    fun withMotionDisabledTheCurrentTrackChangesInPlace() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            motion.scaleFactor = 0f
            show()
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            val restingLeft = cover.fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.autoAdvance = false
            try {
                cover.performTouchInput { swipe(Offset(width * 0.9f, center.y), Offset(width * 0.1f, center.y)) }
                compose.mainClock.advanceTimeBy(64)
                assertThat(cover.fetchSemanticsNode().boundsInRoot.left).isEqualTo(restingLeft)
                compose.onNodeWithText("Second").assertIsDisplayed()
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
}
