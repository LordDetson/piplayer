package io.github.lorddetson.piplayer.tagging

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.crash.CrashKind
import io.github.lorddetson.piplayer.crash.CrashReports
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.play
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.tagging.TaggerTesting.ABORT
import io.github.lorddetson.piplayer.tagging.TaggerTesting.readFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A file that crashes the native reader (docs/library.md §4.6). Opt-in, with `-e crashTests true`: Android counts
 * repeated crashes against the app.
 */
@RunWith(AndroidJUnit4::class)
class TaggerCrashTest {

    private val context = TestFiles.targetContext
    private val database = TaggerTesting.freshDatabase("tagger-crash-test.db").first
    private val client = TaggerTesting.client(database, 10.seconds)
    private var controller: MediaController? = null

    @Before
    fun optIn() {
        assumeTrue(
            "Crashes :tagger; run with -e crashTests true",
            InstrumentationRegistry.getArguments().getString("crashTests") == "true",
        )
    }

    @After
    fun close() {
        controller?.stopAndRelease()
        client.close()
        database.close()
    }

    @Test
    fun anAbortIsACrashTheSecondMakesTheFileUnsafeAndTheSongPlaysOn() {
        val reports = CrashReports.of(context)
        // Earlier records count as seen, so that only this crash is new.
        reports.refresh()
        val controller = connectController().also { controller = it }
        controller.play(LONG_FIXTURE)
        val file = TaggerTesting.marked("abort.bin", ABORT)
        val target = TestFiles.open(file).use { outsideTarget(TaggerTesting.uri(file), it) }
        val since = System.currentTimeMillis()

        assertThat(client.readFile(file)).isEqualTo(TaggerResult.Crashed)
        assertThat(runBlocking { LibraryUnsafeCounts(database).count(target) }).isEqualTo(1)

        val record = exitRecord(since)
        assertThat(record.reason).isEqualTo(ApplicationExitInfo.REASON_CRASH_NATIVE)
        reports.refresh()
        assertThat(reports.store.reports().filter { it.pid == record.pid }.map { it.kind })
            .containsExactly(CrashKind.NATIVE)

        assertThat(client.readFile(file)).isEqualTo(TaggerResult.Crashed)
        assertThat(client.readFile(file)).isEqualTo(TaggerResult.Unsafe)
        assertThat(onMain { controller.isPlaying }).isTrue()
    }

    /** Android's record of how `:tagger` ended after [since]. */
    private fun exitRecord(since: Long): ApplicationExitInfo {
        val process = context.packageName + ":tagger"
        val activity = context.getSystemService(ActivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            activity.getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .firstOrNull { it.processName == process && it.timestamp >= since }
                ?.let { return it }
            Thread.sleep(200)
        }
        throw AssertionError("No exit record of $process")
    }
}
