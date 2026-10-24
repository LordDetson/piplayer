package io.github.lorddetson.piplayer.crash

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.PiPlayerApplication
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PiPlayer's crash reports (docs/playback.md §7): the handler in every process, and in the main process the reports
 * made of Android's records, offered to the user once.
 */
class CrashReports(
    val store: ReportStore,
    header: String,
    private val exits: () -> List<CrashCollector.Exit>,
) {

    /** Reports offered together, with their text as one, newest first. */
    data class Pending(val reports: List<ReportStore.Report>, val text: String)

    private val collector = CrashCollector(store, header)
    private val offered = MutableStateFlow<Pending?>(null)

    /** What the dialog offers: null when there is nothing new, or once the user has closed it. */
    val pending: StateFlow<Pending?> = offered.asStateFlow()

    /** Makes reports of new exit records, then offers the reports not shown yet. Binder calls and disk work. */
    @Synchronized
    fun refresh() {
        try {
            collector.collect(exits())
        } catch (e: Exception) {
            Log.w(TAG, "Could not make reports of how earlier processes ended", e)
        }
        try {
            val unshown = store.unshown()
            offered.value = if (unshown.isEmpty()) null else Pending(unshown, CrashText.combine(unshown.map { it.text }))
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the crash reports", e)
        }
    }

    /** The dialog has appeared: it does not come back for these reports. Disk work. */
    fun markShown(pending: Pending) {
        try {
            store.markShown(pending.reports)
        } catch (e: IOException) {
            Log.w(TAG, "Could not mark the crash reports as shown", e)
        }
    }

    fun close() {
        offered.value = null
    }

    companion object {
        private const val TAG = "CrashReports"

        /** Off in the instrumented tests: reports left by earlier runs would put the dialog over the screens. */
        @Volatile
        var promptEnabled = true

        /** The handler of this process, once installed. */
        @Volatile
        var handler: CrashCapture? = null
            private set

        /** The app and the device, the same in every report of this process. */
        private val header: String by lazy {
            CrashText.header(
                DeviceInfo(
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    buildType = BuildConfig.BUILD_TYPE,
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    device = Build.DEVICE,
                    release = Build.VERSION.RELEASE,
                    sdk = Build.VERSION.SDK_INT,
                    fingerprint = Build.FINGERPRINT,
                    abis = Build.SUPPORTED_ABIS.toList(),
                ),
            )
        }

        /** Puts PiPlayer's handler in front of Android's, which it hands every crash on to (§7.1). */
        fun install(context: Context) {
            val capture = CrashCapture(
                store = ReportStore(dir(context)),
                header = header,
                process = Application.getProcessName(),
                pid = Process.myPid(),
                previous = Thread.getDefaultUncaughtExceptionHandler(),
                kill = {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                },
            )
            Thread.setDefaultUncaughtExceptionHandler(capture)
            handler = capture
        }

        fun forApp(context: Context): CrashReports {
            val app = context.applicationContext
            return CrashReports(ReportStore(dir(app)), header) { exits(app) }
        }

        fun of(context: Context): CrashReports = (context.applicationContext as PiPlayerApplication).crashReports

        /** Private storage, out of backups (§7.2). */
        fun dir(context: Context) = File(context.filesDir, "crashes")

        private fun exits(context: Context): List<CrashCollector.Exit> = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .map { info ->
                val record = ExitRecord(
                    pid = info.pid,
                    processName = info.processName,
                    reason = info.reason,
                    status = info.status,
                    description = info.description,
                    importance = info.importance,
                    timestampMs = info.timestamp,
                    pss = info.pss,
                    rss = info.rss,
                )
                CrashCollector.Exit(record) { info.traceInputStream }
            }
    }
}
