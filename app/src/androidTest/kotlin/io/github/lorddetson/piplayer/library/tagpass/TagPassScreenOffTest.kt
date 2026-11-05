package io.github.lorddetson.piplayer.library.tagpass

import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.playback.AudioJournal
import io.github.lorddetson.piplayer.playback.AudioJournal.Kind
import io.github.lorddetson.piplayer.playback.GaplessAlbum
import io.github.lorddetson.piplayer.playback.PlaybackService
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.shell
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The criterion of M2a step 5 (docs/library.md §5.5): a pass over the whole library while music plays with the screen off
 * and no charger: no underrun, no stall, one audio output. The FLAC album, because the generated files are mono at 8 kHz
 * and would prove little. Opt-in, with `-e screenOff true`, for the phone: it turns the screen off and takes a long time.
 */
@RunWith(AndroidJUnit4::class)
class TagPassScreenOffTest {

    @get:Rule
    val permission = GrantAudioPermission

    private val context = TestFiles.targetContext
    private val power = context.getSystemService(PowerManager::class.java)
    private lateinit var controller: MediaController

    @Before
    fun optIn() {
        assumeTrue(
            "Takes long and turns the screen off; run with -e screenOff true",
            InstrumentationRegistry.getArguments().getString("screenOff") == "true",
        )
    }

    @After
    fun restore() {
        if (::controller.isInitialized) controller.stopAndRelease()
        shell("dumpsys battery reset")
        shell("input keyevent KEYCODE_WAKEUP")
    }

    /** How late a message of the looper of the playback thread comes at worst, over [run]. */
    private fun worstLatenessMs(run: () -> Unit): Long {
        val handler = Handler(PlaybackService.playbackLooperForTests!!)
        var worst = 0L
        var active = true
        fun tick(expected: Long) {
            if (!active) return
            worst = maxOf(worst, SystemClock.uptimeMillis() - expected)
            val next = SystemClock.uptimeMillis() + TICK_MS
            handler.postAtTime({ tick(next) }, next)
        }
        val first = SystemClock.uptimeMillis() + TICK_MS
        handler.postAtTime({ tick(first) }, first)
        run()
        active = false
        return worst
    }

    @Test
    fun aPassOverTheLibraryWithTheScreenOffLeavesPlaybackAlone() {
        val album = GaplessAlbum.all().single { it.name == "flac" }
        runBlocking { Library.reopen(context, empty = true) }
        Library.tagPassEnabled = true
        controller = connectController()
        onMain {
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.setMediaItems(album.files.map(::fileItem))
            controller.prepare()
            controller.play()
        }
        eventually("the album plays") { onMain { controller.isPlaying } }
        shell("dumpsys battery unplug")
        shell("input keyevent KEYCODE_SLEEP")
        eventually("the screen is off") { !power.isInteractive }
        AudioJournal.reset()

        val control = worstLatenessMs { Thread.sleep(CONTROL.inWholeMilliseconds) }
        Log.i(TAG, "Worst lateness without the pass: $control ms")

        Library.start(context)
        Library.sync(context).request()
        val startedAt = SystemClock.elapsedRealtime()
        val during = worstLatenessMs {
            eventually("the pass has started", 5.minutes) { TagPassState.progress.value.running }
            eventually("the pass has read the library", LIMIT) { !TagPassState.progress.value.running }
        }
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000.0
        val progress = TagPassState.progress.value
        Log.i(TAG, "Worst lateness during the pass: $during ms; ${progress.done} files in $seconds s, ${progress.done / seconds} a second")

        val events = AudioJournal.events
        assertThat(progress.done).describedAs("files read").isGreaterThan(MIN_FILES)
        assertThat(power.isInteractive).describedAs("the screen stayed off").isFalse()
        assertThat(onMain { controller.isPlaying }).isTrue()
        assertThat(events.filter { it.kind == Kind.UNDERRUN }).describedAs("underruns").isEmpty()
        assertThat(events.filter { it.kind == Kind.BUFFERING || it.kind == Kind.PAUSE }).describedAs("stalls").isEmpty()
        assertThat(events.filter { it.kind == Kind.OUTPUT }).describedAs("new audio outputs").isEmpty()
    }

    private companion object {
        const val TAG = "TagPassScreenOff"
        const val TICK_MS = 20L
        const val MIN_FILES = 20_000
        val CONTROL = 60.seconds
        val LIMIT = 4.hours
    }
}
