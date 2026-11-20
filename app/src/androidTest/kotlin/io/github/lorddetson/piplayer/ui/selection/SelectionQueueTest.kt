package io.github.lorddetson.piplayer.ui.selection

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackStateStore
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.playback.QueueIds
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SelectionQueueTest {

    private val songs = SongSourceRule {
        listOf("Alpha", "Bravo", "Charlie").mapIndexed { index, title ->
            SongSourceRule.song(index + 1L, title, "playback/snippet-60s.mp3", durationMs = 60_000)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules = RuleChain.outerRule(GrantAudioPermission).around(songs).around(compose)

    @Test
    fun appendKeepsPlayingAndReplaceStartsTheFirstSelectedSong() {
        val controller = connectController()
        try {
            onMain {
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_OFF
            }
            tap(songRow("Bravo"))
            controller.waitUntil("Bravo is playing from the original queue") {
                isPlaying && mediaItemCount == 3 && currentMediaItemIndex == 1 && currentMediaItem?.mediaId == "2"
            }
            onMain { controller.seekTo(10_000L) }
            controller.waitUntil("Bravo is ten seconds in") { currentPosition >= 10_000L }
            val position = onMain { controller.currentPosition }

            selectCharlieThenAlpha()
            choose(R.string.add_to_queue)

            controller.waitUntil("the selection extends the up-next run in list order with Bravo still playing") {
                queueIds() == listOf("1", "2", "1/a1", "3/a2", "3") && currentMediaItemIndex == 1 &&
                    currentMediaItem?.mediaId == "2" && isPlaying && currentPosition >= position
            }

            selectCharlieThenAlpha()
            choose(R.string.selection_replace_queue)

            controller.waitUntil("only the selected songs remain, playing Alpha from its start") {
                queueIds().map(QueueIds::fileId) == listOf(1L, 3L) && currentMediaItemIndex == 0 &&
                    isPlaying && currentPosition < 10_000L
            }
        } finally {
            onMain { controller.clearMediaItems() }
            controller.stopAndRelease()
            runBlocking { PlaybackStateStore.get(TestFiles.targetContext).clear() }
        }
    }

    private fun selectCharlieThenAlpha() {
        val charlie = songRow("Charlie")
        compose.waitUntilExactlyOneExists(charlie, timeoutMillis = 10_000)
        compose.onNode(charlie).performTouchInput { longClick() }
        tap(songRow("Alpha"))
    }

    private fun choose(label: Int) {
        tap(hasTestTag(SelectionTags.MENU))
        tap(hasText(compose.activity.getString(label)))
        compose.waitUntilDoesNotExist(hasTestTag(SelectionTags.BAR), timeoutMillis = 10_000)
    }

    private fun songRow(title: String) = hasText(title) and hasClickAction() and hasAnyAncestor(hasTestTag(SongsTags.LIST))

    private fun tap(matcher: SemanticsMatcher) {
        compose.waitUntilExactlyOneExists(matcher, timeoutMillis = 10_000)
        compose.onNode(matcher).performClick()
    }

    private fun Player.queueIds() = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }
}
