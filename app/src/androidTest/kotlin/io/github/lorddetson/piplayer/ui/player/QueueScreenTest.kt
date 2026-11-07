package io.github.lorddetson.piplayer.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.playback.PlaybackTesting
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.playback.QueueRequest
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The queue screen: the play order, the current song, a tap, removal with undo and a drag (docs/playback.md §9.2). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class QueueScreenTest {

    private val titles = listOf("Alpha", "Bravo", "Charlie", "Delta")
    private val songs = SongSourceRule {
        titles.mapIndexed { index, title -> SongSourceRule.song(index + 1L, title, PlaybackTesting.LONG_FIXTURE) }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(songs).around(compose)

    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
        onMain { controller.shuffleModeEnabled = false }
    }

    @After
    fun disconnect() {
        onMain { controller.shuffleModeEnabled = false }
        controller.stopAndRelease()
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private val inQueue = hasAnyAncestor(hasTestTag(QueueTags.LIST))

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun tapOn(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private fun ids(): List<String> = onMain { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } }

    private fun played(): List<String> = onMain {
        val timeline = controller.currentTimeline
        val shuffle = controller.shuffleModeEnabled
        val window = Timeline.Window()
        buildList {
            var index = timeline.getFirstWindowIndex(shuffle)
            while (index != C.INDEX_UNSET) {
                add(timeline.getWindow(index, window).mediaItem.mediaId)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
            }
        }
    }

    /** The titles the queue screen shows, top to bottom. */
    private fun shownTitles(): List<String> = compose.onAllNodes(hasTestTag(QueueTags.ROW) and inQueue)
        .fetchSemanticsNodes()
        .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text.orEmpty() }

    private fun openQueue() {
        controller.tap(QueueRequest(startSongId = 1, startRow = 0), "Alpha")
        controller.waitUntil("Alpha plays") { isPlaying && mediaItemCount == 4 && currentMediaItem?.mediaId == "1" }
        // Paused, Now Playing's seek bar stops moving and the screen can become idle for the waits.
        onMain { controller.pause() }
        tapOn(hasTestTag(PlayerTags.MINI_PLAYER))
        tapOn(hasContentDescription(text(R.string.queue_open)))
        waitFor(hasText("Delta") and inQueue)
    }

    @Test
    fun theQueueShowsThePlayOrderWithTheCurrentSongMarked() {
        controller.tap(QueueRequest(startSongId = 1, startRow = 0), "Alpha")
        controller.waitUntil("Alpha plays") { isPlaying && mediaItemCount == 4 }
        // A play order that differs from the list, so that the screen must show the shuffled one.
        var tries = 0
        do {
            onMain { controller.shuffleModeEnabled = false }
            controller.waitUntil("shuffle is off") { !shuffleModeEnabled }
            onMain { controller.shuffleModeEnabled = true }
            controller.waitUntil("shuffle is on") { shuffleModeEnabled }
        } while (played() == ids() && ++tries < 20)
        assertThat(played()).isNotEqualTo(ids())
        // Paused, Alpha stays the current song however long the screen takes.
        onMain { controller.pause() }
        val order = played().map { titles[it.toInt() - 1] }

        tapOn(hasTestTag(PlayerTags.MINI_PLAYER))
        tapOn(hasContentDescription(text(R.string.queue_open)))
        waitFor(hasText("Delta") and inQueue)

        compose.waitUntil(10_000) { shownTitles() == order }
        waitFor(hasText("Alpha") and inQueue and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.song_playing)))
    }

    @Test
    fun aTapPlaysTheEntry() {
        openQueue()

        tapOn(hasText("Charlie") and inQueue)

        controller.waitUntil("Charlie plays") { currentMediaItem?.mediaId == "3" }
    }

    @Test
    fun aRemovalCanBeUndone() {
        openQueue()

        tapOn(hasContentDescription(text(R.string.song_menu, "Bravo")) and inQueue)
        tapOn(hasText(text(R.string.queue_remove)))

        controller.waitUntil("Bravo is gone") { mediaItemCount == 3 }
        tapOn(hasText(text(R.string.queue_undo)))
        controller.waitUntil("Bravo is back") { mediaItemCount == 4 }
        assertThat(ids()).containsExactly("1", "2", "3", "4")
        waitFor(hasText("Bravo") and inQueue)
    }

    @Test
    fun aSwipeRemovesTheEntry() {
        openQueue()

        compose.onNode(hasText("Charlie") and inQueue).performTouchInput { swipeLeft() }

        controller.waitUntil("Charlie is gone") { mediaItemCount == 3 }
        assertThat(ids()).containsExactly("1", "2", "4")
        waitFor(hasText(text(R.string.queue_removed, "Charlie")))
    }

    @Test
    fun aDragMovesTheEntry() {
        openQueue()
        val handle = compose.onNode(hasTestTag(QueueTags.handle("4")), useUnmergedTree = true)
        val rowHeight = compose.onAllNodes(hasTestTag(QueueTags.ROW) and inQueue)[0].fetchSemanticsNode().size.height.toFloat()

        handle.performTouchInput {
            down(center)
            repeat(30) { moveBy(Offset(0f, -rowHeight * 2.5f / 30)) }
            up()
        }

        controller.waitUntil("Delta moved up two places") { mediaItemCount == 4 && getMediaItemAt(1).mediaId == "4" }
        assertThat(ids()).containsExactly("1", "4", "2", "3")
        compose.onNodeWithTag(QueueTags.LIST).assertExists()
    }

    @Test
    fun aSwipedRowThatIsUndoneComesBackAndStays() {
        openQueue()

        compose.onNode(hasText("Charlie") and inQueue).performTouchInput { swipeLeft() }
        controller.waitUntil("Charlie is gone") { mediaItemCount == 3 }
        tapOn(hasText(text(R.string.queue_undo)))

        controller.waitUntil("Charlie is back") { mediaItemCount == 4 }
        waitFor(hasText("Charlie") and inQueue)
        PlaybackTesting.throughout("Charlie stays", kotlin.time.Duration.parse("2s")) { onMain { controller.mediaItemCount } == 4 }
        assertThat(ids()).containsExactly("1", "2", "3", "4")
    }

    @Test
    fun anEntryMovesDownAndUpWithoutDragging() {
        openQueue()

        compose.onNode(hasTestTag(QueueTags.ROW) and hasText("Bravo") and inQueue)
            .performCustomAccessibilityActionWithLabel(text(R.string.queue_move_down))
        controller.waitUntil("Bravo moved down") { mediaItemCount == 4 && getMediaItemAt(2).mediaId == "2" }

        compose.onNode(hasTestTag(QueueTags.ROW) and hasText("Delta") and inQueue)
            .performCustomAccessibilityActionWithLabel(text(R.string.queue_move_up))
        controller.waitUntil("Delta moved up") { getMediaItemAt(2).mediaId == "4" }
        assertThat(ids()).containsExactly("1", "3", "4", "2")
    }
}
