package io.github.lorddetson.piplayer.playback

import android.os.PowerManager
import androidx.media3.session.MediaController
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.shell
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The gapless albums through the service with the screen off (docs/playback.md §8.3): automatic song changes, no pause
 * or stall, one audio output, and on the phone no underrun. Opt-in, with `-e screenOff true`, for the M1 acceptance on
 * the phone: it turns the screen off.
 */
@RunWith(Parameterized::class)
class ScreenOffGaplessTest(private val album: GaplessAlbum) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun albums() = GaplessAlbum.all().filter { it.late.isEmpty() }
    }

    private lateinit var controller: MediaController
    private val power = TestFiles.targetContext.getSystemService(PowerManager::class.java)

    @Before
    fun screenOff() {
        assumeTrue(
            "Turns the screen off; run with -e screenOff true",
            InstrumentationRegistry.getArguments().getString("screenOff") == "true",
        )
        controller = connectController()
        shell("input keyevent KEYCODE_SLEEP")
        eventually("the screen is off") { !power.isInteractive }
    }

    @After
    fun screenOn() {
        if (::controller.isInitialized) controller.stopAndRelease()
        shell("input keyevent KEYCODE_WAKEUP")
    }

    @Test
    fun playsThroughWithTheScreenOff() {
        AudioJournalTest.playsThrough(controller, album)
    }
}
