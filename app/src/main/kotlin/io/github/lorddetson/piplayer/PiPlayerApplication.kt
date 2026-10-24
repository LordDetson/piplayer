package io.github.lorddetson.piplayer

import android.app.Application
import android.content.Context
import io.github.lorddetson.piplayer.crash.CrashReports
import kotlin.concurrent.thread

/** PiPlayer's application object, in every process of the app. */
class PiPlayerApplication : Application() {

    val crashReports: CrashReports by lazy { CrashReports.forApp(this) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // First, so that a crash anywhere later in the process is reported (docs/playback.md §7.1).
        CrashReports.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // How earlier processes ended is read in the main process only, and off its main thread: it takes binder calls
        // and disk reads.
        if (ProcessRole.current(this) == ProcessRole.MAIN) {
            thread(name = "crash-reports", priority = Thread.MIN_PRIORITY) { crashReports.refresh() }
        }
    }
}
