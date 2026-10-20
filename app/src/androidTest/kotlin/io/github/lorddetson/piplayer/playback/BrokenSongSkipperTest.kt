package io.github.lorddetson.piplayer.playback

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The skipper on a player of its own, for errors the fixtures cannot cause: a failing audio output stops playback at
 * once instead of moving on (docs/playback.md §2.4).
 */
@RunWith(AndroidJUnit4::class)
class BrokenSongSkipperTest {

    private lateinit var player: ExoPlayer
    private lateinit var skipper: BrokenSongSkipper
    private val stops = mutableListOf<Pair<Int, FailureKind>>()

    @Before
    fun play() {
        val items = listOf(fileItem(LONG_FIXTURE), fileItem(SHORT_FIXTURE))
        player = onMain {
            ExoPlayer.Builder(TestFiles.targetContext).build().also { player ->
                skipper = BrokenSongSkipper(player) { failures, kind -> stops += failures to kind }
                player.addListener(skipper)
                player.setMediaItems(items)
                player.prepare()
                player.play()
            }
        }
        player.waitUntil("the first song plays") { isPlaying }
    }

    @After
    fun release() = onMain { player.release() }

    @Test
    fun aFailingAudioOutputStopsAtOnce() {
        onMain {
            skipper.onPlayerError(
                PlaybackException("output", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED),
            )
        }

        assertThat(onMain { player.playWhenReady }).isFalse()
        assertThat(onMain { player.currentMediaItemIndex }).isEqualTo(0)
        assertThat(stops).containsExactly(0 to FailureKind.DEVICE)
    }

    @Test
    fun aBrokenSongMovesOn() {
        onMain {
            skipper.onPlayerError(PlaybackException("missing", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        }

        assertThat(onMain { player.currentMediaItemIndex }).isEqualTo(1)
        assertThat(stops).isEmpty()
    }
}
