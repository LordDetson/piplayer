package io.github.lorddetson.piplayer.playback

import android.util.Log
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * For the M1 acceptance by hand (docs/m1-acceptance.md, Q2): a queue of 20 000 songs plays for a while, so that the
 * owner can use the notification, the lock screen and a Bluetooth controller with it and judge whether they keep up.
 * Opt-in, with `-e bigQueueSeconds <seconds>`. It only holds the queue and checks that it is still whole at the end.
 */
@RunWith(AndroidJUnit4::class)
class BigQueueHoldTest {

    @get:Rule
    val songs = SongSourceRule()

    private val seconds = InstrumentationRegistry.getArguments().getString("bigQueueSeconds")?.toLongOrNull()
    private lateinit var controller: MediaController

    @Before
    fun connect() {
        assumeTrue("Holds a big queue for checks by hand; run with -e bigQueueSeconds <seconds>", seconds != null)
        controller = connectController()
    }

    @After
    fun disconnect() {
        if (::controller.isInitialized) controller.stopAndRelease()
    }

    @Test
    fun aQueueOf20000SongsPlaysWhileTheOwnerChecksTheControls() {
        val template = SongSourceRule.song(0, "Song", LONG_FIXTURE)
        songs.songs = List(SIZE) { index ->
            template.copy(id = index + 1L, title = "Song %05d of a long list".format(index + 1), artist = "Artist ${index % 500}",
                album = "Album ${index % 2_000}")
        }
        SongList.load(TestFiles.targetContext)

        controller.tap(QueueRequest(startSongId = 1, startRow = 0), "Song 00001 of a long list")
        controller.waitUntil("the queue of $SIZE songs plays", timeout = 20.seconds) { isPlaying && mediaItemCount == SIZE }
        Log.i(TAG, "Holding a queue of $SIZE songs for $seconds s")
        Thread.sleep(seconds!! * 1_000)

        assertThat(onMain { controller.mediaItemCount }).isEqualTo(SIZE)
        Log.i(TAG, "Done, now at song ${onMain { controller.currentMediaItemIndex } + 1}")
    }

    private companion object {
        const val TAG = "BigQueueHoldTest"
        const val SIZE = 20_000
    }
}
