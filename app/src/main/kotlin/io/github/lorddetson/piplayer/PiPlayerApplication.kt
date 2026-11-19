package io.github.lorddetson.piplayer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import io.github.lorddetson.piplayer.crash.CrashReports
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.sort.SortKeys
import io.github.lorddetson.piplayer.net.AndroidConnectivity
import io.github.lorddetson.piplayer.net.UserAgent
import io.github.lorddetson.piplayer.notifications.BatchNotifier
import io.github.lorddetson.piplayer.notifications.TagWriteNotifications
import io.github.lorddetson.piplayer.tagging.write.BatchTagApply
import io.github.lorddetson.piplayer.userdata.TagApplyQueue
import io.github.lorddetson.piplayer.settings.DataStoreOnlineGate
import io.github.lorddetson.piplayer.settings.IdentificationSettings
import io.github.lorddetson.piplayer.settings.SettingsFiles
import io.github.lorddetson.piplayer.tagging.write.BatchTagWriter
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import io.github.lorddetson.piplayer.userdata.UserData
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull

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

    internal val batchTagWriter by lazy {
        ProcessRole.current(this).requireMain("The batch tag writer")
        BatchTagWriter(this, onLibraryTagsStored = { rows -> Library.verifiedTagsStored(this, rows) })
    }
    internal val batchTagApply by lazy {
        ProcessRole.current(this).requireMain("The foreground batch apply")
        BatchTagApply(this)
    }

    /**
     * The identification network services of the main process (TI §8, §9), or the ones a test installed. Their online
     * gate is [onlineGate], closed until the user enables online lookups.
     */
    val identificationServices: IdentificationServices
        get() = installedIdentificationServices ?: ownIdentificationServices

    private val ownIdentificationServices: IdentificationServices by lazy {
        ProcessRole.current(this).requireMain("The identification services")
        // The key ships in every build and is empty without one (TI §9); it goes nowhere but the services.
        IdentificationServices.production(
            userAgent = UserAgent.forBuild(),
            connectivity = AndroidConnectivity(this),
            acoustIdApiKey = BuildConfig.ACOUSTID_API_KEY,
            gate = onlineGate,
            library = LibraryAccess.app(this),
            deviceState = SettingsFiles.deviceState(this),
        )
    }

    /**
     * The online gate of the main process (TI §9): the user's opt-in in the `settings` file. It starts reading the file
     * when the process starts, so that it knows the choice before the first lookup; until then it is closed.
     */
    val onlineGate: DataStoreOnlineGate by lazy {
        ProcessRole.current(this).requireMain("The online gate")
        DataStoreOnlineGate(IdentificationSettings(this), CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

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
            // Starts reading the online-lookup choice, off the main thread, so that it is known before the first lookup.
            onlineGate
            // Lists sort in the app's language (docs/library.md §11.1); the first sync stores its collation.
            SortKeys.use(languages(resources.configuration))
            warmLetters()
            // The library follows the media index for as long as the process lives (docs/library.md §1.3).
            Library.start(this)
            // A batch that outlived the process gets its notification back (TI §8, PIM-21), after the library starts.
            BatchNotifier.of(this).resume()
            // And its jobs: a force stop cancels WorkManager's queue, so its group is enqueued again (TI §8, PIM-16).
            val app = this
            thread(name = "batch-resume", priority = Thread.MIN_PRIORITY) {
                runCatching { kotlinx.coroutines.runBlocking { io.github.lorddetson.piplayer.identification.batch.BatchResume.resume(app) } }
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        val userDatabase = UserData.database(app)
                        val journal = TagWriteJournal(userDatabase)
                        val interrupted = journal.interrupted()
                        TagWriteNotifications.showRecovery(app, interrupted.any { it.batchUuid == null })
                        TagWriteNotifications.showBatchRecovery(app, TagApplyQueue(userDatabase).hasStartedWorkRemaining())
                        if (Library.mayRead(app) && journal.pendingLibraryRefresh().isNotEmpty()) {
                            Library.awaitReady(app, timeoutMs = 10_000)
                            app.batchTagWriter.refreshVerifiedLibrary()
                        }
                    }
                }
            }
        }
        started.complete(Unit)
    }

    companion object {
        private val started = CompletableDeferred<Unit>()

        /** Services a test puts in place of the app's own (like [CrashReports.promptEnabled]); null for the app's. */
        @Volatile
        @VisibleForTesting
        internal var installedIdentificationServices: IdentificationServices? = null

        /**
         * Waits until [onCreate] has run, at most [timeoutMs]; whether it has. Android publishes content providers before
         * it, so a cover asked for by a controller that started the process may come first (docs/library.md §13.8.7).
         */
        suspend fun awaitStarted(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { started.await() } != null
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
