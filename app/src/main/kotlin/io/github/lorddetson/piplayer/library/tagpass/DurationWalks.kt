package io.github.lorddetson.piplayer.library.tagpass

import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.PendingDuration
import io.github.lorddetson.piplayer.library.db.SongRow
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.values.TextForms
import io.github.lorddetson.piplayer.tagging.TagReadGuard
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a file's stored length came about (TI §3.1); the sync clears it when the file changes. */
object DurationSource {
    /** From the header, final. */
    const val HEADER = "header"

    /** From a walk over the frames, final. */
    const val WALK = "walk"

    /** No header says, and no extractor could read the file. */
    const val UNKNOWN = "unknown"

    /** Waiting for a walk over all frames. */
    const val PENDING_WALK = "pending-walk"

    /** From the header, until the walks have checked FLAC's last frame. */
    const val PENDING_FLAC_END = "pending-flac-end"
}

/** What `audio_file` holds about a file's length. */
data class StoredDuration(val ms: Long?, val source: String) {

    companion object {
        /** After a read: [previous], when a walk has settled it, still holds, because the sync clears a changed file's. */
        fun afterRead(plan: DurationPlan, previous: StoredDuration?): StoredDuration {
            if (plan.check != DurationCheck.NONE && previous != null && previous.source in SETTLED_BY_WALK) return previous
            return when (plan.check) {
                DurationCheck.NONE -> StoredDuration(plan.headerMs, DurationSource.HEADER)
                DurationCheck.FLAC_END -> StoredDuration(plan.headerMs, DurationSource.PENDING_FLAC_END)
                DurationCheck.WALK -> StoredDuration(null, DurationSource.PENDING_WALK)
            }
        }

        /** After a walk that was to carry out [plan] and found [ms], null when no extractor could read the file. */
        fun afterWalk(plan: DurationPlan, ms: Long?): StoredDuration = when {
            plan.check == DurationCheck.FLAC_END && (ms == null || ms == plan.headerMs) ->
                StoredDuration(plan.headerMs, DurationSource.HEADER)
            ms == null -> StoredDuration(null, DurationSource.UNKNOWN)
            else -> StoredDuration(ms, DurationSource.WALK)
        }

        private val SETTLED_BY_WALK = setOf(DurationSource.WALK, DurationSource.UNKNOWN)
    }
}

/** What a walk gave. */
sealed interface WalkAnswer {
    /** [ms] is null when no extractor could read the file. */
    data class Measured(val ms: Long?) : WalkAnswer

    data object OpenFailed : WalkAnswer

    /** `:tagger` crashed or hung on the file. */
    data object Crashed : WalkAnswer

    /** The file has failed too often to be read at all. */
    data object Unsafe : WalkAnswer

    /** `:tagger` could not be reached: nothing was read, and it says nothing about the file. */
    data object Unavailable : WalkAnswer
}

fun interface DurationWalker {
    suspend fun walk(file: PassFile, plan: DurationPlan): WalkAnswer
}

/**
 * Settles the lengths that the tag pass could not take from a header (docs/library.md §7.2). Runs after a pass, while
 * [gate] is open. A walk reads a whole file, so unlike a read it does not wait when the gate closes: after [grace] it is
 * cancelled, which kills `:tagger`, and the file comes again after the others.
 */
class DurationWalks(
    private val database: LibraryDatabase,
    private val sync: LibrarySync,
    private val walker: DurationWalker,
    private val gate: PassGate,
    private val grace: Duration = INTERRUPT_GRACE,
    private val crashBackoff: Duration = TagPass.CRASH_BACKOFF,
    private val onBatch: (List<SongRow>) -> Unit = {},
) {
    private val dao = database.library()
    private val displays = TrackDisplayWriter(database)

    /** Files that failed in this process in a way a later process may get past. */
    private val failedHere = ConcurrentHashMap.newKeySet<Triple<Long, Long, Long>>()

    /**
     * One run over what is waiting; returns how many lengths it settled. [stopWhen] is asked between files: work of the
     * tag pass goes first, and what is left waits for the next run.
     */
    suspend fun run(stopWhen: () -> Boolean = { false }): Int {
        val queue = ArrayDeque(
            sync.exclusive { dao.pendingDurations(TagReadGuard.UNSAFE_COUNT) }.filter { it.file.key() !in failedHere },
        )
        var settled = 0
        val finished = ArrayList<Finished>()
        var oldest = TimeSource.Monotonic.markNow()
        suspend fun flush() {
            if (finished.isEmpty()) return
            settled += apply(finished)
            finished.clear()
        }
        try {
            while (queue.isNotEmpty() && !stopWhen()) {
                gate.awaitOpen()
                val pending = queue.removeFirst()
                when (val answer = walkWhileOpen(pending)) {
                    null -> {
                        flush()
                        queue.addLast(pending)
                    }
                    is WalkAnswer.Measured -> {
                        if (finished.isEmpty()) oldest = TimeSource.Monotonic.markNow()
                        finished += Finished(pending, StoredDuration.afterWalk(pending.plan, answer.ms))
                    }
                    WalkAnswer.Unavailable -> {
                        flush()
                        queue.addFirst(pending)
                        delay(crashBackoff)
                    }
                    WalkAnswer.Crashed -> {
                        failedHere += pending.file.key()
                        delay(crashBackoff)
                    }
                    WalkAnswer.OpenFailed, WalkAnswer.Unsafe -> failedHere += pending.file.key()
                }
                if (finished.size >= BATCH_SIZE || oldest.elapsedNow() >= MAX_BATCH_AGE) flush()
            }
        } finally {
            withContext(NonCancellable) { flush() }
        }
        return settled
    }

    /** The files that failed in this process get another try in the next run. */
    fun forgetFailures() = failedHere.clear()

    /** The answer of a walk, or null when the gate closed and the walk was ended. */
    private suspend fun walkWhileOpen(pending: PendingDuration): WalkAnswer? = coroutineScope {
        val walk = async { walker.walk(pending.file, pending.plan) }
        val closing = launch {
            gate.awaitClosed()
            delay(grace)
            walk.cancel()
        }
        try {
            walk.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } finally {
            closing.cancel()
        }
    }

    private class Finished(val pending: PendingDuration, val stored: StoredDuration)

    /** One transaction, while no sync runs. A file the sync changed since it was walked keeps nothing of the walk. */
    private suspend fun apply(items: List<Finished>): Int {
        var applied = 0
        val rows = sync.exclusive {
            val states = dao.fileStates(items.map { it.pending.fileId }).associateBy { it.id }
            val current = items.filter { item ->
                states[item.pending.fileId]?.let { it.size == item.pending.size && it.mtime == item.pending.mtime } == true
            }
            applied = current.size
            val ids = current.map { it.pending.fileId }
            database.withWriteTransaction {
                current.forEach { dao.setDuration(it.pending.fileId, it.stored.ms, it.stored.source) }
                val inputs = displays.inputsOf(ids)
                with(TrackDisplayWriter) { write(inputs.map { it.fileId }, TextForms().let { forms -> inputs.map { it.resolve(forms) } }) }
            }
            ids.chunked(CHUNK).flatMap { dao.songRowsOf(it) }
        }
        if (rows.isNotEmpty()) onBatch(rows)
        return applied
    }

    private fun PassFile.key() = Triple(fileId, size, mtime)

    companion object {
        /** Long enough for most files to finish, so that `:tagger` is seldom killed. */
        val INTERRUPT_GRACE = 3.seconds

        private const val BATCH_SIZE = 50
        private val MAX_BATCH_AGE = 1.seconds
        private const val CHUNK = 500
    }
}
