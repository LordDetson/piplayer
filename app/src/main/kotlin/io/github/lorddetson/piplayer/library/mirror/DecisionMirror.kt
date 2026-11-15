package io.github.lorddetson.piplayer.library.mirror

import android.util.Log
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.DecidedMirrorDao
import io.github.lorddetson.piplayer.library.db.DecidedMirrorEntity
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.MirrorDao
import io.github.lorddetson.piplayer.library.db.replaceAll
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors the decided values of the user database into `decided_mirror` and derives the display rows of the files they
 * change again, in one library database transaction while no sync writes (TI §7.1, §10.3). The runs take one lock, and
 * each reads the user database under it, so an older run never writes over a newer decision.
 */
class DecisionMirror(
    private val library: LibraryDatabase,
    private val user: UserDatabase,
    private val sync: LibrarySync,
    /** Whether the library knows its files; before its first sync there is nothing to match. */
    private val ready: () -> Boolean = { true },
) {
    private val decisions = Decisions(user)
    private val displays = TrackDisplayWriter(library)
    private val mutableVersion = MutableStateFlow(0L)

    /** Raised after every run that changed decided values, so that lists, Now Playing and the session read again. */
    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    private val running = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val idle = MutableStateFlow(true)
    private val lock = Any()
    private var pendingAll = false
    private var pendingIfChanged = false
    private val pendingFiles = LinkedHashSet<Long>()

    init {
        scope.launch {
            for (request in requests) {
                val (all, ifChanged, files) = synchronized(lock) {
                    Triple(pendingAll, pendingIfChanged, pendingFiles.toList()).also {
                        pendingAll = false
                        pendingIfChanged = false
                        pendingFiles.clear()
                    }
                }
                try {
                    // A whole run covers the files too.
                    when {
                        all -> mirrorAll()
                        else -> {
                            if (ifChanged) mirrorIfChanged()
                            if (files.isNotEmpty()) mirrorFiles(files)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "The mirror of decisions failed", e)
                }
                synchronized(lock) { if (!pendingAll && !pendingIfChanged && pendingFiles.isEmpty()) idle.value = true }
            }
        }
    }

    /** A whole run: every decision, compared with what is mirrored; stores the change counter it read. Whether values changed. */
    suspend fun mirrorAll(): Boolean = running.withLock { allRun() }

    /** A whole run when the user database's change counter differs from the mirrored one (TI §10.3, at start-up); whether it ran. */
    suspend fun mirrorIfChanged(): Boolean = running.withLock {
        if (!ready()) return@withLock false
        if (user.user().changeCounter() == library.library().mirroredChangeCounter()) return@withLock false
        allRun()
        true
    }

    /**
     * The run after a commit that raised the change counter to [counter]: the files of [identityIds] only, read from the
     * user database under the lock. The stored counter moves to [counter] only from one below, since favorites and plays
     * raise the same counter without a decision (TI §10.3). Whether values changed.
     */
    suspend fun mirrorIdentities(identityIds: Collection<Long>, counter: Long): Boolean = running.withLock {
        if (!ready()) return@withLock false
        val changed = sync.exclusive {
            val keys = decisions.keysOf(identityIds)
            val decided = decisions.decidedOf(identityIds)
            val identityOfKey = keys.entries.associate { (identity, key) -> key to identity }
            val files = keys.values.toList().chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().filesWithKeys(it) }
            val desired = files.associate { file ->
                file.fileId to rowsOf(file.fileId, file.contentKey?.let(identityOfKey::get)?.let(decided::get))
            }
            val fileIds = files.map { it.fileId }
            val existing = fileIds.chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().valuesOf(it) }.groupBy { it.fileId }
            val stored = library.library().mirroredChangeCounter()
            write(differing(fileIds, desired, existing), desired, counter.takeIf { stored == counter - 1 })
        }
        changed.also { if (it) mutableVersion.value++ }
    }

    /** The run after a batch of the tag pass: the files [fileIds], by the keys they hold now. Whether values changed. */
    suspend fun mirrorFiles(fileIds: Collection<Long>): Boolean = running.withLock {
        if (!ready()) return@withLock false
        val changed = sync.exclusive {
            val ids = fileIds.distinct()
            val existing = ids.chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().valuesOf(it) }.groupBy { it.fileId }
            // A library without decisions, the common case, reads no more.
            if (existing.isEmpty() && !user.decisions().hasLiveValueDecisions()) return@exclusive false
            val keys = ids.chunked(MirrorDao.CHUNK).flatMap { library.mirror().keysOf(it) }
            val identities = keys.mapNotNull { it.contentKey }.distinct().chunked(MirrorDao.CHUNK)
                .flatMap { chunk -> user.identities().withKeys(chunk.map(Hex::bytes)) }
                .associate { Hex.text(it.contentKey) to it.id }
            val decided = decisions.decidedOf(identities.values)
            val desired = keys.associate { file ->
                file.fileId to rowsOf(file.fileId, file.contentKey?.let(identities::get)?.let(decided::get))
            }
            write(differing(ids, desired, existing), desired, counter = null)
        }
        changed.also { if (it) mutableVersion.value++ }
    }

    private suspend fun allRun(): Boolean {
        if (!ready()) return false
        val changed = sync.exclusive {
            val snapshot = decisions.snapshot()
            val desired = HashMap<Long, List<DecidedMirrorEntity>>()
            if (snapshot.byKey.isNotEmpty()) {
                for (file in library.decided().keyedFiles()) {
                    snapshot.byKey[file.contentKey]?.let { desired[file.fileId] = rowsOf(file.fileId, it) }
                }
            }
            val existing = library.decided().all().groupBy { it.fileId }
            write(differing(desired.keys + existing.keys, desired, existing), desired, snapshot.counter)
        }
        return changed.also { if (it) mutableVersion.value++ }
    }

    /** The rows of [decided] for [fileId]; none without decided values. */
    private fun rowsOf(fileId: Long, decided: Map<DecidedField, Decided>?): List<DecidedMirrorEntity> =
        decided.orEmpty().map { (part, value) -> DecidedMirrorEntity(fileId, part.stored, value.value, value.level, value.decisionId) }

    /** The files of [fileIds] whose [desired] rows differ from the [existing] ones. */
    private fun differing(
        fileIds: Collection<Long>,
        desired: Map<Long, List<DecidedMirrorEntity>>,
        existing: Map<Long, List<DecidedMirrorEntity>>,
    ): List<Long> = fileIds.filter { desired[it].orEmpty().toSet() != existing[it].orEmpty().toSet() }

    /**
     * Replaces the decided values of [changed] with their [desired] rows and derives their display rows again, in one
     * transaction that also stores [counter] when one is given. Whether any file changed.
     */
    private suspend fun write(changed: List<Long>, desired: Map<Long, List<DecidedMirrorEntity>>, counter: Long?): Boolean {
        if (changed.isEmpty() && counter == null) return false
        library.withWriteTransaction {
            if (changed.isNotEmpty()) {
                for (chunk in changed.chunked(DecidedMirrorDao.CHUNK)) library.decided().deleteFiles(chunk)
                // In bulk: a whole run writes some 27 rows for each decided file.
                replaceAll(DECIDED_TABLE, DECIDED_COLUMNS, changed.flatMap { desired[it].orEmpty() }) { row ->
                    long(row.fileId)
                    text(row.field)
                    text(row.value)
                    long(row.level.toLong())
                    long(row.decisionId)
                }
                // Read again here: the display of each file joins the rows just written (TI §7.1).
                val inputs = displays.inputsOf(changed)
                val values = TrackDisplayWriter.resolve(inputs)
                with(TrackDisplayWriter) { write(inputs.map { it.fileId }, values) }
            }
            if (counter != null) library.library().setMirroredChangeCounter(counter)
        }
        return changed.isNotEmpty()
    }

    /** Asks for a whole run in the background; requests during a run collapse into one more run. */
    fun request() = ask { pendingAll = true }

    /** Asks for a whole run in the background when the counters differ (TI §10.3). */
    fun requestIfChanged() = ask { pendingIfChanged = true }

    /** Asks for a run of [fileIds] in the background, which the tag pass has just read. */
    fun requestFiles(fileIds: List<Long>) {
        if (fileIds.isNotEmpty()) ask { pendingFiles.addAll(fileIds) }
    }

    private fun ask(what: () -> Unit) {
        synchronized(lock) {
            what()
            idle.value = false
        }
        requests.trySend(Unit)
    }

    /** Waits until no run runs and none is asked for. */
    suspend fun awaitIdle() {
        idle.first { it }
        running.withLock { }
    }

    fun close() {
        requests.close()
        scope.cancel()
    }

    /** [close], then waits until a run under way has ended, so that the databases may be closed. */
    suspend fun closeAndJoin() {
        close()
        scope.coroutineContext[Job]?.join()
        running.withLock { }
    }

    private companion object {
        const val TAG = "DecisionMirror"
        const val DECIDED_TABLE = "decided_mirror"

        // The columns of DecidedMirrorEntity, in the order the bulk write gives them.
        val DECIDED_COLUMNS = listOf("file_id", "field", "value", "level", "decision_id")
    }
}
