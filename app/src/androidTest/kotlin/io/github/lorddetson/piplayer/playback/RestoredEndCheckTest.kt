package io.github.lorddetson.piplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.expect
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The check of a restored start against the length the player reads from the file (docs/playback.md §4.3.1), on a
 * paused player of its own, as a restore at app start leaves it: the restored items carry no length, so only the player
 * knows where the long fixture ends.
 */
@RunWith(AndroidJUnit4::class)
class RestoredEndCheckTest {

    private lateinit var player: ExoPlayer
    private var replays = 0
    private val check = RestoredEndCheck { replays++ }
    private val long = item("long", LONG_FIXTURE)
    private val short = item("short", SHORT_FIXTURE)

    @Before
    fun create() {
        player = onMain { ExoPlayer.Builder(TestFiles.targetContext).build() }
    }

    @After
    fun release() = onMain {
        check.cancel()
        player.release()
    }

    @Test
    fun aPositionAtTheEndMovesOnToTheStartOfTheNextSongAndStaysPaused() {
        restore(listOf(long, short), positionMs = SongSourceRule.LONG_FIXTURE_MS)
        val moved = player.expect("the next song is ready", capture = { currentPosition }) {
            currentMediaItemIndex == 1 && playbackState == Player.STATE_READY
        }
        onMain { player.prepare() }

        assertThat(moved.await()).isLessThan(500L)
        assertThat(onMain { player.playWhenReady }).isFalse()
        assertThat(onMain { replays }).isZero()
    }

    @Test
    fun aPositionAtTheEndOfTheOnlySongReplaysItFromItsStart() {
        restore(listOf(long), positionMs = SongSourceRule.LONG_FIXTURE_MS)
        val replayed = player.expect("the song is ready at its start", capture = { currentPosition }) {
            playbackState == Player.STATE_READY && currentPosition < 500
        }
        onMain { player.prepare() }

        replayed.await()
        assertThat(onMain { player.currentMediaItemIndex }).isEqualTo(0)
        assertThat(onMain { replays }).isEqualTo(1)
    }

    @Test
    fun aSeekBeforeTheLengthIsKnownDropsTheCheck() {
        restore(listOf(long, short), positionMs = SongSourceRule.LONG_FIXTURE_MS)
        // The user seeks while the player has not read the file yet.
        onMain { player.seekTo(0, 3_000) }
        val ready = player.expect("the player is ready", capture = { currentMediaItemIndex to currentPosition }) {
            playbackState == Player.STATE_READY
        }
        onMain { player.prepare() }

        val (index, position) = ready.await()
        assertThat(index).isEqualTo(0)
        assertThat(position).isBetween(2_800L, 3_300L)
        assertThat(onMain { replays }).isZero()
    }

    @Test
    fun anotherQueueBeforeTheLengthIsKnownDropsTheCheck() {
        var current = true
        restore(listOf(long, short), positionMs = SongSourceRule.LONG_FIXTURE_MS) { current }
        // A newer queue of the same songs, which starts in the middle of the first one.
        onMain {
            current = false
            player.setMediaItems(listOf(long, short), 0, 3_000)
        }
        val ready = player.expect("the player is ready", capture = { currentMediaItemIndex to currentPosition }) {
            playbackState == Player.STATE_READY
        }
        onMain { player.prepare() }

        val (index, position) = ready.await()
        assertThat(index).isEqualTo(0)
        assertThat(position).isBetween(2_800L, 3_300L)
    }

    /** Sets [items] paused at the first one's [positionMs] and watches that start, as a restore does. */
    private fun restore(items: List<MediaItem>, positionMs: Long, current: () -> Boolean = { true }) = onMain {
        player.pause()
        player.setMediaItems(items, 0, positionMs)
        check.watch(player, items[0].mediaId, 0, positionMs, current)
    }

    private fun item(mediaId: String, asset: String) = fileItem(asset).buildUpon().setMediaId(mediaId).build()
}
