package io.github.lorddetson.piplayer.crash

import java.util.concurrent.atomic.AtomicBoolean

/**
 * PiPlayer's handler of Kotlin and Java crashes (docs/playback.md §7.1): it writes the report, then hands the crash to
 * the [previous] handler, Android's, which records it and ends the process; without one it ends the process with
 * [kill] itself. Writing uses plain files only, nothing that needs another thread or the main looper, and nothing it
 * throws stops the crash from being handed on.
 */
class CrashCapture(
    private val store: ReportStore,
    private val header: String,
    private val process: String,
    private val pid: Int,
    internal val previous: Thread.UncaughtExceptionHandler?,
    private val kill: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : Thread.UncaughtExceptionHandler {

    /** A crash on another thread while the first is handled goes straight on: the process is ending anyway. */
    private val handling = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (handling.compareAndSet(false, true)) {
                val time = now()
                store.write(CrashKind.JAVA, time, pid, CrashText.java(header, process, pid, thread.name, time, error))
                store.prune()
            }
        } catch (_: Throwable) {
            // The report is lost, the crash must still reach Android.
        } finally {
            previous?.uncaughtException(thread, error) ?: kill()
        }
    }
}
