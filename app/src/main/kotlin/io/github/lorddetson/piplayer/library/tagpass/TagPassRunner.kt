package io.github.lorddetson.piplayer.library.tagpass

import android.os.Process
import android.util.Log
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sync.LibraryStatus
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the tag pass when there is something for it to do (docs/library.md §5.5): after a sync that gave the library files,
 * when the process starts and when asked, one run at a time on a thread of background priority.
 */
class TagPassRunner(
    private val pass: TagPass,
    private val status: Flow<LibraryStatus>,
    private val gate: PassGate,
    private val startDelay: Duration = START_DELAY,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "tag-pass")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requests = MutableStateFlow(0L)
    private var job: Job? = null

    /** Once; later calls do nothing. */
    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { pass.progress.collect { TagPassState.progress.value = it } }
            // Not at once: the first lists come before everything else.
            delay(startDelay)
            combine(status.filter { it.known }.map { it.revision }.distinctUntilChanged(), requests) { revision, request ->
                revision to request
            }.conflate().collect { runOnce() }
        }
    }

    /** One more run: a full read asks for this, which no sync would. */
    fun runAgain() = requests.update { it + 1 }

    /** One more run over every file, those that failed in this process too. */
    fun readAllAgain() {
        pass.forgetFailures()
        runAgain()
    }

    /** Ends what runs. A read under way is cancelled, which kills `:tagger`. */
    suspend fun stopAndJoin() {
        val running = synchronized(this) { job }
        running?.cancelAndJoin()
        scope.coroutineContext[Job]?.cancelAndJoin()
        dispatcher.close()
    }

    private suspend fun runOnce() {
        gate.awaitOpen()
        SongList.passStarted()
        try {
            val result = pass.run()
            Log.i(TAG, "The tag pass read ${result.read} files, ${result.failed} failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The database or the reader gave up: what is committed stays, and the next run goes on from there.
            Log.w(TAG, "The tag pass failed", e)
        } finally {
            SongList.passFinished()
        }
    }

    private companion object {
        const val TAG = "TagPass"
        val START_DELAY = 3.seconds
    }
}
