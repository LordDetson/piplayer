package io.github.lorddetson.piplayer.macrobenchmark

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import io.github.lorddetson.piplayer.macrobenchmark.TheApp.MINI_PLAYER
import io.github.lorddetson.piplayer.macrobenchmark.TheApp.NOW_PLAYING
import io.github.lorddetson.piplayer.macrobenchmark.TheApp.PACKAGE
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The release build, shrunk and obfuscated by R8, still does what a user does first: it starts, lists the library,
 * plays a tapped song and shows it in the mini player and Now Playing (docs/library.md §2.2). Needs music on the device.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private companion object {
        const val TAG_PROGRESS = "songs_progress"
        const val QUEUE_LIST = "queue_list"
    }

    @Before
    fun start() {
        device.executeShellCommand("am force-stop $PACKAGE")
        TheApp.grantAccess(device)
        val intent = checkNotNull(instrumentation.context.packageManager.getLaunchIntentForPackage(PACKAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.context.startActivity(intent)
    }

    @After
    fun stop() {
        device.executeShellCommand("am force-stop $PACKAGE")
    }

    @Test
    fun theSongsScreenListsTheLibrary() {
        val list = TheApp.songsList(device)

        assertThat(list.childCount).describedAs("rows on screen").isPositive()
    }

    @Test
    fun aTappedSongPlaysAndNowPlayingShowsIt() {
        TheApp.songsList(device)
        TheApp.waitForTheWholeList(device)
        val row = TheApp.songsList(device).children.first()
        // The first text of a row is the song's title.
        val title = checkNotNull(row.findObject(By.text(Pattern.compile(".+")))) { "A row without text" }.text

        row.click()

        val mini = checkNotNull(device.wait(Until.findObject(By.res(MINI_PLAYER)), 10_000)) { "No mini player" }
        assertThat(device.wait({ TheApp.isPlaying(device) }, 10_000)).describedAs("playing").isTrue()
        assertThat(mini.wait(Until.hasObject(By.text(title)), 10_000)).describedAs("the title in the mini player").isTrue()
        mini.click()
        val nowPlaying = checkNotNull(device.wait(Until.findObject(By.res(NOW_PLAYING)), 10_000)) { "No Now Playing" }
        assertThat(nowPlaying.wait(Until.hasObject(By.text(title)), 10_000)).describedAs("the title in Now Playing").isTrue()

        // The queue screen and its drag library under R8 (docs/playback.md §9.2): a drag moves a row and nothing breaks.
        checkNotNull(device.findObject(By.desc(Pattern.compile("Queue|Очередь")))) { "No queue button" }.click()
        val queue = checkNotNull(device.wait(Until.findObject(By.res(QUEUE_LIST)), 10_000)) { "No queue screen" }
        assertThat(queue.wait(Until.hasObject(By.text(title)), 10_000)).describedAs("the title in the queue").isTrue()
        val handles = device.wait(Until.findObjects(By.res(Pattern.compile("queue_handle_.+"))), 10_000).orEmpty()
        assertThat(handles.size).describedAs("drag handles").isGreaterThan(1)
        val target = handles[0].visibleCenter
        handles[1].drag(target, 600)
        assertThat(device.wait(Until.hasObject(By.res(QUEUE_LIST)), 5_000)).describedAs("the queue after a drag").isTrue()
        assertThat(device.executeShellCommand("pidof $PACKAGE").trim()).describedAs("the app process").isNotEmpty()
    }

    /** `:tagger`, its JSON replies and the database writes of the pass under R8 (docs/library.md §5.5). */
    @Test
    fun theTagPassReadsTheLibrary() {
        assumeTrue("Needs the generated library, for a long enough run", TheApp.generatedFiles(device) >= TheApp.GENERATED_FILES)
        // The pass rests while the phone is hot, by design (docs/library.md §5.5): it would show nothing to wait for.
        assumeTrue("Needs a phone that is not hot: ${thermalStatus()}", !isHot())
        // A library already read has nothing for the pass to do.
        device.executeShellCommand("am force-stop $PACKAGE")
        device.executeShellCommand("pm clear $PACKAGE")
        device.executeShellCommand("logcat -c")
        start()

        val line = checkNotNull(device.wait(Until.findObject(By.res(TAG_PROGRESS)), 60_000)) { "No progress line, ${thermalStatus()}" }
        val readSome = device.wait({
            Regex("""\d+""").find(line.contentDescription.orEmpty())?.value?.toInt()?.let { it >= 400 } == true
        }, 120_000)

        assertThat(readSome).describedAs("files read, as the line says: ${line.contentDescription}").isTrue()
        assertThat(device.executeShellCommand("pidof $PACKAGE:tagger").trim()).describedAs("the tagger process").isNotEmpty()
        assertThat(device.executeShellCommand("logcat -d -s TagPass:W")).doesNotContain("The tag pass failed")
    }

    private fun thermalStatus(): String =
        device.executeShellCommand("dumpsys thermalservice").lineSequence().first { "Thermal Status" in it }.trim()

    /** At the status PowerManager calls moderate and above. */
    private fun isHot(): Boolean = thermalStatus().substringAfter(":").trim().toInt() >= 2

    private fun UiDevice.wait(condition: () -> Boolean, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }
}
