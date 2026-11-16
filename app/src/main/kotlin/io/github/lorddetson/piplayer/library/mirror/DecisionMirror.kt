package io.github.lorddetson.piplayer.library.mirror

import android.util.Log
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.DecidedMirrorDao
import io.github.lorddetson.piplayer.library.db.DecidedMirrorEntity
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.MirrorDao
import io.github.lorddetson.piplayer.library.db.replaceAll
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.sort.SortKeys
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    /**
     * The whole runs this mirror has completed, also those that wrote nothing; a start makes one (TI §10.3). A run that
     * fails does not count, so the first attach pass still asks for one after a failed start-up run.
     */
    @Volatile
    var wholeRuns = 0
        private set

    /** The whole runs made because the change counters differed (TI §10.3, the start's own run). */
    @Volatile
    var changedRuns = 0
        private set

    private val running = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val idle = MutableStateFlow(true)
    private val lock = Any()
    private var pendingAll = false
    private var pendingUnlessRan = false
    private var pendingIfChanged = false
    private val pendingFiles = LinkedHashSet<Long>()

    /** Whether the background loop is making a run, and how many runs after a commit are under way. */
    private var working = false
    private var committing = 0

    /** What the background loop takes at once. */
    private class Asked(val all: Boolean, val unlessRan: Boolean, val ifChanged: Boolean, val files: List<Long>)

    init {
        scope.launch {
            for (request in requests) {
                val asked = synchronized(lock) {
                    working = true
                    Asked(pendingAll, pendingUnlessRan, pendingIfChanged, pendingFiles.toList()).also {
                        pendingAll = false
                        pendingUnlessRan = false
                        pendingIfChanged = false
                        pendingFiles.clear()
                    }
                }
                try {
                    // A whole run covers the files too.
                    when {
                        asked.all -> mirrorAll()
                        else -> {
                            if (asked.unlessRan) mirrorUnlessRan()
                            if (asked.ifChanged) mirrorIfChanged()
                            if (asked.files.isNotEmpty()) mirrorFiles(asked.files)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "The mirror of decisions failed", e)
                }
                synchronized(lock) {
                    working = false
                    settle()
                }
            }
        }
    }

    /** Idle once nothing runs and nothing is asked for; called under [lock]. */
    private fun settle() {
        if (!working && committing == 0 && !pendingAll && !pendingUnlessRan && !pendingIfChanged && pendingFiles.isEmpty()) {
            idle.value = true
        }
    }

    /** A whole run: every decision, compared with what is mirrored; stores the change counter it read. Whether values changed. */
    suspend fun mirrorAll(): Boolean = running.withLock { allRun() }

    /** A whole run unless this mirror has made one (TI §10.3, after the first attach pass of a process); whether it ran. */
    private suspend fun mirrorUnlessRan(): Boolean = running.withLock {
        if (!ready() || wholeRuns > 0) return@withLock false
        allRun()
        true
    }

    /** A whole run when the user database's change counter differs from the mirrored one (TI §10.3, at start-up); whether it ran. */
    suspend fun mirrorIfChanged(): Boolean = running.withLock {
        if (!ready()) return@withLock false
        if (user.user().changeCounter() == library.library().mirroredChangeCounter()) return@withLock false
        allRun()
        changedRuns++
        true
    }

    /**
     * The run after a commit that raised the change counter to [counter]: the files of [identityIds] only, read from the
     * user database under the lock. The stored counter moves to [counter] only from one below, since favorites and plays
     * raise the same counter without a decision (TI §10.3). Whether values changed.
     */
    suspend fun mirrorIdentities(identityIds: Collection<Long>, counter: Long): Boolean = running.withLock {
        if (!ready()) return@withLock false
        // The user database first, under the lock; the sync waits only for the library database's part.
        val keys = decisions.keysOf(identityIds)
        val decided = decisions.decidedOf(identityIds)
        val changed = sync.exclusive {
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

    /**
     * Mirrors [committed] in this mirror's own scope (TI §10.3): the caller waits for the run, but its cancellation does
     * not stop it. When the run fails, the decision stays committed and a whole run is asked for, so the library database
     * catches up within the session. Whether values changed.
     */
    suspend fun mirrorCommitted(committed: Decisions.Committed): Boolean {
        val run = synchronized(lock) {
            committing++
            idle.value = false
            scope.async {
                try {
                    mirrorIdentities(listOf(committed.identityId), committed.counter)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "The mirror of a decision failed; a whole run follows", e)
                    request()
                    false
                }
            }
        }
        run.invokeOnCompletion {
            synchronized(lock) {
                committing--
                settle()
            }
        }
        return run.await()
    }

    private suspend fun allRun(): Boolean {
        if (!ready()) return false
        // Every decision is read, and compared with `decided_mirror`, before the sync is held off (TI §10.3): only this
        // mirror writes `decided_mirror`, under its lock, so its rows stay as read. The files' keys, which the sync and the
        // tag pass change, are read again under the hold, and the files whose keys moved meanwhile are compared again.
        val snapshot = decisions.snapshot()
        val byKey = snapshot.byKey
        val before = keyedFiles(byKey)
        val comparison = DecidedComparison(before.ids, before.values(byKey))
        compareMirrored(comparison)
        val changed = sync.exclusive {
            val now = keyedFiles(byKey)
            val moved = before.moved(now)
            val existing = moved.chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().valuesOf(it) }.groupBy { it.fileId }
            val stale = differing(moved, moved.associateWith { rowsOf(it, now.valuesOf(it, byKey)) }, existing)
            val differing = (comparison.differing().filter { it !in moved } + stale).sorted()
            write(differing, differing.associateWith { rowsOf(it, now.valuesOf(it, byKey)) }, snapshot.counter)
        }
        // Counted once it has completed: a run that throws leaves the next request to make one (TI §10.3).
        wholeRuns++
        return changed.also { if (it) mutableVersion.value++ }
    }

    /** The files whose keys have decided values in [byKey], read in one statement. */
    private suspend fun keyedFiles(byKey: Map<String, Map<DecidedField, Decided>>): KeyedFiles {
        if (byKey.isEmpty()) return KeyedFiles(LongArray(0), emptyArray())
        val ids = LongList()
        val keys = ArrayList<String>()
        library.useReaderConnection { connection ->
            connection.usePrepared(KEYED_FILES) { statement ->
                while (statement.step()) {
                    val key = statement.getText(1)
                    if (key in byKey) {
                        ids += statement.getLong(0)
                        keys += key
                    }
                }
            }
        }
        return KeyedFiles(ids.toArray(), keys.toTypedArray())
    }

    /**
     * Streams every row of `decided_mirror` through [comparison], so that nothing is built for a file that matches, in
     * [SCAN_PARTS] ranges of row IDs read on reader connections of their own at once (TI §10.3).
     */
    private suspend fun compareMirrored(comparison: DecidedComparison) {
        val range = library.useReaderConnection { connection ->
            connection.usePrepared(ROW_RANGE) { statement ->
                statement.step()
                if (statement.isNull(0)) null else statement.getLong(0)..statement.getLong(1)
            }
        } ?: return
        val span = (range.last - range.first) / SCAN_PARTS + 1
        coroutineScope {
            for (p in 0 until SCAN_PARTS) {
                val from = range.first + p * span
                if (from > range.last) break
                val part = comparison.part()
                launch(Dispatchers.IO) {
                    library.useReaderConnection { connection ->
                        connection.usePrepared(MIRRORED_ROWS) { statement ->
                            statement.bindLong(1, from)
                            statement.bindLong(2, minOf(range.last, from + span - 1))
                            while (statement.step()) {
                                part.row(
                                    fileId = statement.getLong(0),
                                    field = statement.getText(1),
                                    value = if (statement.isNull(2)) null else statement.getText(2),
                                    level = statement.getInt(3),
                                    decisionId = statement.getLong(4),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** A growing list of file IDs without a box for each. */
    private class LongList {
        private var items = LongArray(1_024)
        private var size = 0

        operator fun plusAssign(item: Long) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = item
        }

        fun toArray(): LongArray = items.copyOf(size)
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
                val rows = changed.flatMap { desired[it].orEmpty() }
                // The display of each file joins the rows written here (TI §7.1), given as they are written rather than read
                // back, and the cores resolve the displays while the rows are written.
                val inputs = displays.inputsOf(changed, decided = TrackDisplayWriter.overridesOf(rows))
                coroutineScope {
                    val resolving = with(TrackDisplayWriter) { resolving(inputs, SortKeys.current()) }
                    for (chunk in changed.chunked(DecidedMirrorDao.CHUNK)) library.decided().deleteFiles(chunk)
                    // In bulk: a whole run writes some 27 rows for each decided file.
                    replaceAll(DECIDED_TABLE, DECIDED_COLUMNS, rows) { row ->
                        long(row.fileId)
                        text(row.field)
                        text(row.value)
                        long(row.level.toLong())
                        long(row.decisionId)
                    }
                    with(TrackDisplayWriter) { writeChunks(inputs.map { it.fileId }, resolving) }
                }
            }
            if (counter != null) library.library().setMirroredChangeCounter(counter)
        }
        return changed.isNotEmpty()
    }

    /** Asks for a whole run in the background; requests during a run collapse into one more run. */
    fun request() = ask { pendingAll = true }

    /**
     * Asks for a whole run in the background unless this mirror has made one (TI §10.3): the first attach pass of a
     * process, which wrote no identities, needs none after the start-up run.
     */
    fun requestUnlessRan() = ask { pendingUnlessRan = true }

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

        // In file order, which the comparison's search needs.
        const val KEYED_FILES = "SELECT id, content_key FROM audio_file WHERE content_key IS NOT NULL ORDER BY id"

        const val ROW_RANGE = "SELECT min(rowid), max(rowid) FROM decided_mirror"

        // A range of row IDs in the order the table holds them: the comparison takes any order, and sorting half a million
        // rows would cost.
        const val MIRRORED_ROWS = "SELECT file_id, field, value, level, decision_id FROM decided_mirror WHERE rowid BETWEEN ? AND ?"

        /** The ranges a whole run reads `decided_mirror` in at once, within the readers of the connection pool. */
        const val SCAN_PARTS = 4

        // The columns of DecidedMirrorEntity, in the order the bulk write gives them.
        val DECIDED_COLUMNS = listOf("file_id", "field", "value", "level", "decision_id")
    }
}
