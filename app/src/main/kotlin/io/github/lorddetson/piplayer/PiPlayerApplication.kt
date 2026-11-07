package io.github.lorddetson.piplayer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import io.github.lorddetson.piplayer.crash.CrashReports
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.sort.SortKeys
import kotlin.concurrent.thread

/** Tells the tag pass when the app shows (docs/library.md §5.5). */
private object PassGateActivities : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        started++
        Library.passGate.setUiVisible(true)
    }

    override fun onActivityStopped(activity: Activity) {
        started--
        Library.passGate.setUiVisible(started > 0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

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
            registerActivityLifecycleCallbacks(PassGateActivities)
            // Lists sort in the app's language (docs/library.md §11.1); the first sync stores its collation.
            SortKeys.use(languages(resources.configuration))
            warmLetters()
            // The library follows the media index for as long as the process lives (docs/library.md §1.3).
            Library.start(this)
        }
    }

    /** Another language of the app makes every sort key again, by a sync now (docs/library.md §11.1). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ProcessRole.current(this) == ProcessRole.MAIN && SortKeys.use(languages(newConfig))) {
            Library.sync(this).request()
            warmLetters()
        }
    }

    /** Builds the letters of the scroll thumb off the main thread, before a list reads them (docs/library.md §11.2). */
    private fun warmLetters() {
        val collation = SortKeys.current()
        thread(name = "sort-letters", priority = Thread.MIN_PRIORITY) { collation.buckets }
    }

    private fun languages(configuration: Configuration) = configuration.locales.let { list -> (0 until list.size()).map { list[it] } }
}
