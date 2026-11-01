package io.github.lorddetson.piplayer.macrobenchmark

import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import io.github.lorddetson.piplayer.macrobenchmark.TheApp.PACKAGE
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scroll of Songs over the generated library of 20 000 files (docs/library.md §2.2): flings down and up, frame by
 * frame. FrameTimingMetric goes to the benchmark report; the check is the system's own count of janky frames
 * (`dumpsys gfxinfo`), which on Android 12 and later counts frames that missed their refresh and on Android 11 frames
 * longer than one refresh period. At most 5 % may be janky. Runs on devices only, with the generated library on them.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val benchmark = MacrobenchmarkRule()

    // An emulator draws through the host's graphics, which misses most refreshes whatever the app does (50 to 77 % on
    // the API 30 emulator, 71 % even with every method compiled ahead of time), so the check runs on devices only.
    @RequiresDevice
    @Test
    fun scrollingTheGeneratedLibraryIsSmooth() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(TheApp.generatedFiles(device) >= TheApp.GENERATED_FILES) {
            "The generated library must be on the device: python scripts/generate_library.py --push"
        }
        var frames = 0
        var janky = 0
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = {
                TheApp.grantAccess(device)
                startActivityAndWait()
                TheApp.songsList(device)
                TheApp.waitForTheWholeList(device)
                device.executeShellCommand("dumpsys gfxinfo $PACKAGE reset")
            },
        ) {
            // Found again for every fling: the list's node is replaced when the whole list follows its first page.
            fun flingList(direction: Direction) {
                val list = TheApp.songsList(device)
                list.setGestureMargin(device.displayWidth / 5)
                list.fling(direction)
            }
            repeat(FLINGS) { flingList(Direction.DOWN) }
            repeat(FLINGS / 2) { flingList(Direction.UP) }
            device.waitForIdle()
            val stats = device.executeShellCommand("dumpsys gfxinfo $PACKAGE")
            frames += Regex("""Total frames rendered: (\d+)""").find(stats)!!.groupValues[1].toInt()
            janky += Regex("""(?m)^Janky frames: (\d+)""").find(stats)!!.groupValues[1].toInt()
        }
        val share = janky * 100.0 / frames
        Log.i(TAG, "Scroll: $janky janky of $frames frames (%.2f %%)".format(share))

        assertThat(frames).describedAs("frames rendered").isPositive()
        assertThat(share).describedAs("janky frames, %").isLessThanOrEqualTo(MAX_JANKY_PERCENT)
    }

    private companion object {
        const val TAG = "ScrollBenchmark"
        const val ITERATIONS = 5
        const val FLINGS = 8
        const val MAX_JANKY_PERCENT = 5.0
    }
}
