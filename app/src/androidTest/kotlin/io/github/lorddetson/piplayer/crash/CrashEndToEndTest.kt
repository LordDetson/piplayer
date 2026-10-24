package io.github.lorddetson.piplayer.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real crashes in debug-only processes of their own (docs/playback.md §7.4). Opt-in, with `-e crashTests true`:
 * Android counts repeated crashes against the app.
 */
@RunWith(AndroidJUnit4::class)
class CrashEndToEndTest {

    private val context = TestFiles.targetContext
    private val reports = CrashReports.of(context)

    @Before
    fun optIn() {
        assumeTrue(
            "Crashes test processes; run with -e crashTests true",
            InstrumentationRegistry.getArguments().getString("crashTests") == "true",
        )
    }

    @Test
    fun aJavaCrashGivesOneReportWithAndroidsRecord() {
        val text = crash(JavaCrashService::class.java, ":crashtest_java", CrashKind.JAVA)

        assertThat(text).contains(JavaCrashService.MESSAGE, "Thread: main", "Android's record")
    }

    @Test
    fun aNativeCrashGivesOneReportWithItsTombstone() {
        val text = crash(NativeCrashService::class.java, ":crashtest_native", CrashKind.NATIVE)

        assertThat(text).contains("Android's record")
        if (Build.VERSION.SDK_INT >= 31) {
            assertThat(text).contains("signal 6 (SIGABRT)", "backtrace:")
        } else {
            assertThat(text).contains("No tombstone")
        }
    }

    /** Crashes [service]'s process and returns the one report made of it. */
    private fun crash(service: Class<out Service>, suffix: String, kind: CrashKind): String {
        // Earlier records count as seen, so that only this crash is new.
        reports.refresh()
        val since = System.currentTimeMillis()
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) = connected.countDown()
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertThat(context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE)).isTrue
        assertThat(connected.await(10, TimeUnit.SECONDS)).isTrue
        // Unbound before it crashes, so that Android does not start it again.
        context.unbindService(connection)

        val process = context.packageName + suffix
        val activity = context.getSystemService(ActivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var record: ApplicationExitInfo? = null
        while (record == null && SystemClock.elapsedRealtime() < deadline) {
            record = activity.getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .firstOrNull { it.processName == process && it.timestamp >= since }
            if (record == null) Thread.sleep(200)
        }
        assertThat(record).describedAs("the exit record of $process").isNotNull

        reports.refresh()

        val made = reports.store.reports().filter { it.pid == record!!.pid }
        assertThat(made.map { it.kind }).containsExactly(kind)
        return made.single().text
    }
}
