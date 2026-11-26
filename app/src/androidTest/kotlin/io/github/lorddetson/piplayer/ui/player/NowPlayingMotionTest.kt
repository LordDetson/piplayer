package io.github.lorddetson.piplayer.ui.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureEnvironment
import io.github.lorddetson.piplayer.settings.UiGestureMotionPreset
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiGestureTarget
import io.github.lorddetson.piplayer.settings.UiGestureTimeline
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiSurfaceGestures
import io.github.lorddetson.piplayer.ui.customization.stockGesture
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NowPlayingMotionTest {
    private val motion = object : MotionDurationScale {
        @Volatile override var scaleFactor = 1f
    }
    @Volatile private var observedScale: Float? = null

    @get:Rule
    val compose = createComposeRule(effectContext = motion)

    private fun show(
        onOpenQueue: (() -> Unit)? = null,
        followCurrentCover: Boolean = false,
        secondCover: (() -> ImageBitmap?)? = null,
        forScreen: (TestPlayer) -> Player = { it },
    ): TestPlayer {
        val player = compose.runOnUiThread {
            TestPlayer(listOf(
                TestPlayer.TestSong("First", artwork = TestImages.png(64)),
                TestPlayer.TestSong("Second", artwork = TestImages.png(64, color = 0xFFEA201A.toInt())),
                TestPlayer.TestSong("Third", artwork = TestImages.png(64, color = 0xFF1CE529.toInt())),
            ))
        }
        val screenPlayer = compose.runOnUiThread { forScreen(player) }
        val blue = TestImages.cover()
        val red = TestImages.bitmap(64, color = 0xFFEA201A.toInt()).asImageBitmap()
        compose.setContent {
            LaunchedEffect(Unit) { observedScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor }
            PiPlayerTheme {
                val current = if (followCurrentCover) rememberCurrentItemState(screenPlayer).mediaItem else null
                val artwork = when {
                    current?.mediaId != "1" -> blue
                    secondCover != null -> secondCover()
                    else -> red
                }
                NowPlaying(screenPlayer, artwork, {},
                    layout = NowPlayingLayout.EXPANDED, onOpenQueue = onOpenQueue)
            }
        }
        compose.waitUntil(5_000) { observedScale != null }
        assertThat(observedScale).isEqualTo(motion.scaleFactor)
        return player
    }

    @Test
    fun cancellingOrChangingYourMindBeforeTheThresholdKeepsTheCurrentTrack() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            val player = show()
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            cover.performTouchInput {
                val start = Offset(width * 0.90f, center.y)
                down(start)
                moveTo(Offset(width * 0.05f, center.y))
                moveTo(start)
                up()
                down(start)
                moveTo(Offset(width * 0.05f, center.y))
                cancel()
            }
            assertThat(compose.runOnUiThread { player.calls }).isEmpty()
            compose.onNodeWithText("First").assertIsDisplayed()
        }

    @Test
    fun aQueueReplacementCannotCommitTheStalePreviewEvenWithDuplicateMediaIds() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            val player = show()
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            compose.runOnUiThread {
                player.replaceQueue(listOf(TestPlayer.TestSong("First", mediaId = "same"), TestPlayer.TestSong("Second", mediaId = "same")))
            }
            cover.performTouchInput {
                down(Offset(width * 0.90f, center.y))
                moveTo(Offset(width * 0.1f, center.y))
            }
            compose.runOnUiThread {
                player.replaceQueue(listOf(TestPlayer.TestSong("First", mediaId = "same"), TestPlayer.TestSong("Replacement", mediaId = "same")))
            }
            cover.performTouchInput { up() }
            assertThat(compose.runOnUiThread { player.calls }).isEmpty()
            compose.onNodeWithText("First").assertIsDisplayed()
        }

    @Test
    fun repeatAllAllowsWrappingToTheOtherEndOfTheQueue() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            val player = show()
            compose.runOnUiThread { player.repeatMode = Player.REPEAT_MODE_ALL }
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            cover.performTouchInput { swipe(Offset(width * 0.1f, center.y), Offset(width * 0.9f, center.y)) }
            assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(2)
            compose.onNodeWithText("Third").assertIsDisplayed()
        }

    @Test
    fun thePreviewAndReleaseFollowThePlayersShuffleOrder() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            val player = show(forScreen = { delegate ->
                delegate.shuffleModeEnabled = true
                object : ForwardingPlayer(delegate) {
                    override fun getNextMediaItemIndex(): Int = if (currentMediaItemIndex == 0) 2 else 1

                    override fun seekToNextMediaItem() = delegate.seekToDefaultPosition(nextMediaItemIndex)
                }
            })
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            cover.performTouchInput {
                down(Offset(width * 0.90f, center.y))
                moveTo(Offset(width * 0.10f, center.y))
            }
            assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isZero()
            val y = cover.fetchSemanticsNode().boundsInRoot.center.y.roundToInt()
            compose.waitUntil(5_000) {
                val pixels = compose.onRoot().captureToImage().toPixelMap()
                (0 until pixels.width).count { x ->
                    val pixel = pixels[x, y]
                    pixel.red < 0.25f && pixel.green > 0.85f && pixel.blue < 0.25f
                } > 12
            }
            cover.performTouchInput { up() }
            assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(2)
        }

    @Test
    fun consecutiveEntriesWithTheSameIdStillSwitchToTheExactEntryOnce() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            val player = show()
            compose.runOnUiThread {
                player.replaceQueue(listOf(TestPlayer.TestSong("First", mediaId = "same"), TestPlayer.TestSong("Second", mediaId = "same")))
            }
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            cover.performTouchInput { swipe(Offset(width * 0.90f, center.y), Offset(width * 0.10f, center.y)) }
            assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(1)
            assertThat(compose.runOnUiThread { player.calls }).hasSize(1)
            compose.onNodeWithText("Second").assertIsDisplayed()
        }

    @Test
    fun rebindingNextTrackToAnUpwardSwipeBringsInItsArtworkFromBelow() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            bindUpwardCoverGesture(UiGestureActionId.NEXT_TRACK)
            val player = show()
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            val bounds = cover.fetchSemanticsNode().boundsInRoot
            try {
                cover.performTouchInput {
                    down(Offset(center.x, height * 0.9f))
                    moveTo(Offset(center.x, height * 0.12f))
                }
                assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isZero()
                val pixels = compose.onRoot().captureToImage().toPixelMap()
                val redBelow = pixels[bounds.center.x.roundToInt(), (bounds.bottom + bounds.height * 0.10f).roundToInt()]
                assertThat(redBelow.red).isGreaterThan(0.85f)
                assertThat(redBelow.green).isLessThan(0.25f)
            } finally {
                cover.performTouchInput { up() }
            }
            assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(1)
        }

    @Test
    fun aReboundNonPreviewActionStillReachesPlaybackOnRelease() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            bindUpwardCoverGesture(UiGestureActionId.TOGGLE_SHUFFLE)
            val player = show()
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            cover.performTouchInput {
                down(Offset(center.x, height * 0.9f))
                moveTo(Offset(center.x, height * 0.12f))
            }
            assertThat(compose.runOnUiThread { player.calls }).isEmpty()
            cover.performTouchInput { up() }
            assertThat(compose.runOnUiThread { player.calls }).containsExactly(TestPlayer.Call.Shuffle(true))
        }

    private fun bindUpwardCoverGesture(action: UiGestureActionId) {
        val surface = UiSurface.NOW_PLAYING
        val binding = stockGesture(NOW_PLAYING_COVER_ID, UiBuiltInGesture.SWIPE_UP, UiGestureAction(action))
        val profile = UiGestureProfile().editSurface(surface, UiSurfaceGestures().assign(binding))
        val environment = UiGestureEnvironment(setOf(NOW_PLAYING_COVER_ID), emptyList(), listOf(previousCoverGesture, nextCoverGesture))
        val settings = UiCanvasSettings(TestFiles.targetContext)
        assertThat(runBlocking { settings.saveGestures(ThemeMode.SYSTEM.stored, surface, null, profile, environment) }).isTrue()
    }

    @Test
    fun explicitNoneLeavesTheCoverWithoutCardMotion() =
        assertNoCardMotion(UiGestureMotionPreset.NONE.timeline(UiGestureTarget.component(NOW_PLAYING_COVER_ID)))

    @Test
    fun anAuthoredGroupEffectDoesNotMoveTheCoverTwice() =
        assertNoCardMotion(UiGestureMotionPreset.CARDS.timeline(UiGestureTarget.group(setOf(NOW_PLAYING_COVER_ID, "now:title"))))

    private fun assertNoCardMotion(timeline: UiGestureTimeline) {
        compose.runOnUiThread {
            val player = TestPlayer(listOf(TestPlayer.TestSong("First"), TestPlayer.TestSong("Second")))
            val motion = CoverGestureMotion(NowPlayingTrackSwipe(player), CoroutineScope(Dispatchers.Main.immediate))
            val binding = stockGesture(
                NOW_PLAYING_COVER_ID, UiBuiltInGesture.SWIPE_UP,
                UiGestureAction(UiGestureActionId.NEXT_TRACK), listOf(timeline),
            )
            motion.preview(NOW_PLAYING_COVER_ID, binding, 0.6f)
            assertThat(motion.track).isNull()
        }
    }

    @Test
    fun adjacentArtworkPeeksDuringTheDragBeforePlaybackChanges() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            val player = show()
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            cover.performTouchInput {
                down(Offset(width * 0.9f, center.y))
                moveTo(Offset(width * 0.12f, center.y))
            }
            assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(0)
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val y = cover.fetchSemanticsNode().boundsInRoot.center.y.roundToInt()
            assertThat((0 until pixels.width).count { x ->
                val pixel = pixels[x, y]
                pixel.red > 0.85f && pixel.green < 0.25f && pixel.blue < 0.25f
            }).isGreaterThan(12)
            cover.performTouchInput { up() }
            assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(1)
        }

    @Test
    fun thePrefetchedArtworkStaysVisibleUntilTheDestinationCoverFinishesLoading() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            val live = mutableStateOf<ImageBitmap?>(null)
            show(followCurrentCover = true, secondCover = { live.value })
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            val center = cover.fetchSemanticsNode().boundsInRoot.center
            cover.performTouchInput {
                down(Offset(width * 0.9f, centerY))
                moveTo(Offset(width * 0.10f, centerY))
            }
            compose.waitUntil(5_000) {
                artworkLeftOrNull(center.y.roundToInt(), red = true)?.let { it > center.x.roundToInt() / 2 } == true
            }
            cover.performTouchInput { up() }
            compose.onNodeWithText("Second").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(320)
            compose.onNodeWithTag(PlayerTags.COVER_PLACEHOLDER, useUnmergedTree = true).assertDoesNotExist()
            val pixel = compose.onRoot().captureToImage().toPixelMap()[center.x.roundToInt(), center.y.roundToInt()]
            assertThat(pixel.red).isGreaterThan(0.85f)
            assertThat(pixel.green).isLessThan(0.25f)
            compose.runOnUiThread { live.value = TestImages.cover() }
            val loaded = compose.onRoot().captureToImage().toPixelMap()[center.x.roundToInt(), center.y.roundToInt()]
            assertThat(loaded.blue).isGreaterThan(0.80f)
        }

    @Test
    fun downwardDragPreviewsTheQueueAndOpensItOnlyOnRelease() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            var opened = 0
            val player = show(onOpenQueue = { opened++ })
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            compose.onNodeWithText("Second").assertDoesNotExist()
            cover.performTouchInput {
                down(center)
                moveTo(center + Offset(0f, height * 1.7f))
            }
            assertThat(compose.runOnUiThread { opened }).isZero()
            compose.onNodeWithText("Second", useUnmergedTree = true).assertIsDisplayed()
            cover.performTouchInput { up() }
            compose.waitUntil(5_000) { compose.runOnUiThread { opened == 1 } }
            assertThat(compose.runOnUiThread { player.calls }).isEmpty()
        }

    @Test
    fun aCancelledOrShortQueueDragLeavesNowPlayingOpen() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            var opened = 0
            show(onOpenQueue = { opened++ })
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            cover.performTouchInput {
                down(center)
                moveTo(center + Offset(0f, height * 1.7f))
                cancel()
                down(center)
                moveTo(center + Offset(0f, height * 0.30f))
                up()
            }
            assertThat(compose.runOnUiThread { opened }).isZero()
            compose.onNodeWithText("Second").assertDoesNotExist()
        }

    @Test
    fun reducedMotionOpensTheQueueDirectlyOnRelease() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            motion.scaleFactor = 0f
            var opened = 0
            show(onOpenQueue = { opened++ })
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            cover.performTouchInput {
                down(center)
                moveTo(center + Offset(0f, height * 1.7f))
            }
            assertThat(compose.runOnUiThread { opened }).isZero()
            compose.onNodeWithText("Second").assertDoesNotExist()
            cover.performTouchInput { up() }
            assertThat(compose.runOnUiThread { opened }).isEqualTo(1)
        }

    private fun artworkLeftAt(y: Int, red: Boolean = false): Int = checkNotNull(artworkLeftOrNull(y, red))

    private fun artworkLeftOrNull(y: Int, red: Boolean): Int? {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        return (0 until pixels.width - 12).firstOrNull { x ->
            (x until x + 12).all { column ->
                val pixel = pixels[column, y]
                if (red) pixel.red > 0.85f && pixel.green < 0.25f && pixel.blue < 0.25f
                else pixel.red < 0.55f && pixel.green < 0.62f && pixel.blue > 0.80f
            }
        }
    }

    @Test
    fun theIncomingCoverSlidesFromTheOppositeSideOfTheSwipe() =
        withStockPlayerTheme(TestFiles.targetContext, "now_playing_panel_", "ui_canvas_") {
            val player = show(followCurrentCover = true)
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            val rootTop = compose.onRoot().fetchSemanticsNode().boundsInRoot.top
            val y = (cover.fetchSemanticsNode().boundsInRoot.center.y - rootTop).roundToInt()
            val restingLeft = artworkLeftAt(y)
            compose.mainClock.autoAdvance = false
            try {
                cover.performTouchInput { swipe(Offset(width * 0.9f, center.y), Offset(width * 0.1f, center.y)) }
                assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(1)
                compose.mainClock.advanceTimeBy(64)
                assertThat(artworkLeftAt(y, red = true)).isGreaterThan(restingLeft)
                compose.mainClock.advanceTimeBy(320)
                assertThat(artworkLeftAt(y, red = true)).isEqualTo(restingLeft)

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
            val player = show()
            val cover = compose.onNodeWithTag(PlayerTags.COVER, useUnmergedTree = true)
            val restingLeft = cover.fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.autoAdvance = false
            try {
                cover.performTouchInput { swipe(Offset(width * 0.9f, center.y), Offset(width * 0.1f, center.y)) }
                assertThat(compose.runOnUiThread { player.currentMediaItemIndex }).isEqualTo(1)
                compose.mainClock.advanceTimeBy(64)
                assertThat(cover.fetchSemanticsNode().boundsInRoot.left).isEqualTo(restingLeft)
                compose.onNodeWithText("Second").assertIsDisplayed()
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
}
