package io.github.lorddetson.piplayer.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.library.db.SongRow
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** A batch of the tag pass reaches the session and the mini player while the song plays on (docs/playback.md §2.6). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlayingSongFollowsThePassTest {

    private val songs = SongSourceRule { listOf(SongSourceRule.song(1, "Alpha", LONG_FIXTURE)) }
    private val compose = createAndroidComposeRule<MainActivity>()
    private var controller: MediaController? = null

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(songs).around(compose)

    @After
    fun release() {
        controller?.stopAndRelease()
    }

    @Test
    fun theSessionAndTheMiniPlayerShowTheNewValuesAndPlaybackGoesOn() {
        // A queue saved by an earlier test may show the song in the mini player too.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Alpha")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Alpha"))[0].performClick()
        val connected = connectController().also { controller = it }
        // The file is short for a loaded machine, and its end is not what this is about.
        onMain { connected.repeatMode = Player.REPEAT_MODE_ONE }
        compose.waitUntil(10_000) { onMain { connected.isPlaying } }
        // The list row and the mini player.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Alpha")).fetchSemanticsNodes().size == 2 }

        val states = CopyOnWriteArrayList<Int>()
        onMain {
            connected.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    states += playbackState
                }
            })
        }
        SongList.applyInPlace(listOf(SongRow(1, 1, "Changed", "New artist", "New album", 60_000)))

        compose.waitUntil(10_000) { onMain { connected.mediaMetadata.title?.toString() == "Changed" } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Changed")).fetchSemanticsNodes().size == 2 }
        onMain {
            assertThat(connected.mediaMetadata.artist.toString()).isEqualTo("New artist")
            assertThat(connected.isPlaying).isTrue()
            assertThat(states).describedAs("no new preparation").isEmpty()
        }
    }
}
