package io.github.lorddetson.piplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The skipper on a player of its own, for errors the fixtures cannot cause: a failing audio output stops playback at
 * once instead of moving on (docs/playback.md §2.4); and for a new shuffle order in the middle of a streak (§3.6).
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

    /** Shuffle turned on sets a new shuffle order, which Media3 reports as a change of the playlist. */
    @OptIn(UnstableApi::class)
    @Test
    fun aNewShuffleOrderKeepsTheCount() {
        val missing = List(6) { MediaItem.fromUri(Uri.fromFile(File(TestFiles.targetContext.cacheDir, "missing-$it.mp3"))) }
        val ownStops = mutableListOf<Pair<Int, FailureKind>>()
        val own = onMain {
            ExoPlayer.Builder(TestFiles.targetContext).build().also { own ->
                own.addListener(BrokenSongSkipper(own) { failures, kind -> ownStops += failures to kind })
                own.addListener(object : Player.Listener {
                    private var errors = 0

                    override fun onPlayerError(error: PlaybackException) {
                        if (++errors == 2) own.setShuffleOrder(DefaultShuffleOrder(own.mediaItemCount))
                    }
                })
                own.setMediaItems(missing + fileItem(LONG_FIXTURE))
                own.prepare()
                own.play()
            }
        }
        try {
            eventually("playback stops or reaches the good song") { onMain { ownStops.isNotEmpty() || own.isPlaying } }

            assertThat(onMain { ownStops.toList() }).containsExactly(5 to FailureKind.SONG)
            assertThat(onMain { own.currentMediaItemIndex }).isEqualTo(4)
        } finally {
            onMain { own.release() }
        }
    }
}
