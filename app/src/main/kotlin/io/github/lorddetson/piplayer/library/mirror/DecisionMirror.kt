package io.github.lorddetson.piplayer.library.mirror

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.DecidedMirrorDao
import io.github.lorddetson.piplayer.library.db.DecidedMirrorEntity
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.MirrorDao
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.db.replaceAll
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.lists.MissingInfoDecision
import io.github.lorddetson.piplayer.library.sort.SortKeys
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors the decided values of the user database, and the Keep as-is or Skip of each track, into `decided_mirror` and
 * derives the display rows of the files they change again, in one library database transaction while no sync writes
 * (TI §7.1, §10.3). After each batch of the tag pass it lets lapse the Skips of changed files and compares the files read
 * with their decided values first (TI §1, §7.1). The runs take one lock, and
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

    /** The files each run that raised [version] changed (TI §7.1). */
    private val changes = DecidedChanges()

    /**
     * The files whose decided values the runs after version [seen] up to version [upTo] changed, so that the session
     * compares only their queue items (TI §7.1); null when that is not known.
     */
    fun filesChangedAfter(seen: Long, upTo: Long): Set<Long>? = changes.after(seen, upTo)

    /** Raises [version] for a run that changed [files], once they are kept for [filesChangedAfter]. Whether it changed any. */
    private fun raised(files: List<Long>): Boolean {
        if (files.isEmpty()) return false
        synchronized(changes) {
            val next = mutableVersion.value + 1
            changes.record(next, files)
            mutableVersion.value = next
        }
        return true
    }

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

    /** Called by a whole run once it has compared, before it holds off the sync (tests: the moment the run waits). */
    @VisibleForTesting
    internal var beforeHold: suspend () -> Unit = {}

    /** Called by each range of a whole run's comparison once it holds its reader connection (tests). */
    @VisibleForTesting
    internal var onScanPart: suspend () -> Unit = {}

    /** Called before the checks after a batch of the tag pass (tests: checks that fail). */
    @VisibleForTesting
    internal var beforeChecks: suspend () -> Unit = {}

    /** How long failed checks after a batch wait before they are made again (TI §1). */
    @VisibleForTesting
    internal var retryDelay: Duration = 5.seconds

    private val running = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val idle = MutableStateFlow(true)
    private val lock = Any()
    private var pendingAll = false
    private var pendingUnlessRan = false
    private var pendingIfChanged = false
    private val pendingFiles = LinkedHashSet<Long>()
    private val pendingRead = LinkedHashSet<Long>()
    private val pendingFailed = LinkedHashSet<Long>()

    /** How many more times the checks after a batch have been made for each file whose checks failed (TI §1). */
    private val checkTries = HashMap<Long, Int>()

    /** Whether the background loop is making a run, how many runs after a commit and checks made again are under way. */
    private var working = false
    private var committing = 0
    private var retrying = 0

    /** What the background loop takes at once. */
    private class Asked(
        val all: Boolean,
        val unlessRan: Boolean,
        val ifChanged: Boolean,
        val files: List<Long>,
        val read: List<Long>,
        val failed: List<Long>,
        /** Whether files wait for their checks made again, not asked for yet ([checkAgain]). */
        val awaiting: Boolean,
    )

    init {
        scope.launch {
            for (request in requests) {
                val asked = synchronized(lock) {
                    working = true
                    Asked(
                        pendingAll, pendingUnlessRan, pendingIfChanged, pendingFiles.toList(), pendingRead.toList(), pendingFailed.toList(),
                        awaiting = retrying > 0,
                    ).also {
                        pendingAll = false
                        pendingUnlessRan = false
                        pendingIfChanged = false
                        pendingFiles.clear()
                        pendingRead.clear()
                        pendingFailed.clear()
                    }
                }
                // Before the mirror runs, which replace the rows that name a file's Skip before its read (TI §1). The run of
                // files whose checks are made again waits for them.
                var waiting = emptySet<Long>()
                if (asked.read.isNotEmpty() || asked.failed.isNotEmpty()) {
                    try {
                        afterRead(asked.read, asked.failed)
                        checked(asked.read + asked.failed)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "The checks after a batch of the tag pass failed", e)
                        waiting = checkAgain(asked.read, asked.failed)
                    }
                }
                // A whole run replaces those rows too, so while files wait for their checks it waits as well (TI §1): it is
                // asked for again, and the pass that takes the files' checks made again runs it after them.
                val whole = asked.all || asked.unlessRan || asked.ifChanged
                val deferred = whole && (asked.awaiting || waiting.isNotEmpty())
                if (deferred) {
                    synchronized(lock) {
                        pendingAll = pendingAll || asked.all
                        pendingUnlessRan = pendingUnlessRan || asked.unlessRan
                        pendingIfChanged = pendingIfChanged || asked.ifChanged
                    }
                }
                try {
                    // A whole run covers the files too.
                    when {
                        asked.all && !deferred -> mirrorAll()
                        else -> {
                            if (asked.unlessRan && !deferred) mirrorUnlessRan()
                            if (asked.ifChanged && !deferred) mirrorIfChanged()
                            val files = (asked.files + asked.read + asked.failed).distinct().filter { it !in waiting }
                            if (files.isNotEmpty()) mirrorFiles(files)
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
        if (!working && committing == 0 && retrying == 0 && !pendingAll && !pendingUnlessRan && !pendingIfChanged && pendingFiles.isEmpty() &&
            pendingRead.isEmpty() && pendingFailed.isEmpty()
        ) {
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
        val states = decisions.missingInfoOf(identityIds)
        val changed = sync.exclusive {
            val identityOfKey = keys.entries.associate { (identity, key) -> key to identity }
            val files = keys.values.toList().chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().filesWithKeys(it) }
            val desired = files.associate { file ->
                val identity = file.contentKey?.let(identityOfKey::get)
                file.fileId to rowsOf(file.fileId, identity?.let(decided::get), identity?.let(states::get))
            }
            val fileIds = files.map { it.fileId }
            val existing = fileIds.chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().valuesOf(it) }.groupBy { it.fileId }
            val stored = library.library().mirroredChangeCounter()
            write(differing(fileIds, desired, existing), desired, counter.takeIf { stored == counter - 1 })
        }
        raised(changed)
    }

    /** The run after a batch of the tag pass: the files [fileIds], by the keys they hold now. Whether values changed. */
    suspend fun mirrorFiles(fileIds: Collection<Long>): Boolean = running.withLock {
        if (!ready()) return@withLock false
        val changed = sync.exclusive {
            val ids = fileIds.distinct()
            val existing = ids.chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().valuesOf(it) }.groupBy { it.fileId }
            // A library without decisions, the common case, reads no more.
            if (existing.isEmpty() && !user.decisions().hasMirroredDecisions()) return@exclusive emptyList()
            val keys = ids.chunked(MirrorDao.CHUNK).flatMap { library.mirror().keysOf(it) }
            val identities = keys.mapNotNull { it.contentKey }.distinct().chunked(MirrorDao.CHUNK)
                .flatMap { chunk -> user.identities().withKeys(chunk.map(Hex::bytes)) }
                .associate { Hex.text(it.contentKey) to it.id }
            val decided = decisions.decidedOf(identities.values)
            val states = decisions.missingInfoOf(identities.values)
            val desired = keys.associate { file ->
                val identity = file.contentKey?.let(identities::get)
                file.fileId to rowsOf(file.fileId, identity?.let(decided::get), identity?.let(states::get))
            }
            write(differing(ids, desired, existing), desired, counter = null)
        }
        raised(changed)
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
        // Every decision is read, and compared with `decided_mirror`, before the sync is held off (TI §10.3). Only this
        // mirror writes values into `decided_mirror`, under its lock; the sync only deletes the rows of the files it
        // deletes, whose IDs never come back (library.md §8.4). So under the hold the run reads the files' keys again, which
        // the sync and the tag pass change, and compares again the files whose keys moved meanwhile and the files that
        // should hold no values, whose rows may be gone with their files.
        val snapshot = decisions.snapshot()
        val states = snapshot.missingInfoByKey
        // The keys with decided values or a Keep as-is or Skip (TI §10.1), the latter with no values.
        val byKey = if (states.isEmpty()) snapshot.byKey else snapshot.byKey + (states.keys - snapshot.byKey.keys).associateWith { emptyMap<DecidedField, Decided>() }
        val before = keyedFiles(byKey)
        val comparison = DecidedComparison(before.ids, before.values(byKey), before.keys.map(states::get))
        compareMirrored(comparison)
        beforeHold()
        val changed = sync.exclusive {
            val now = keyedFiles(byKey)
            fun rows(fileId: Long) = now.keyOf(fileId).let { key -> rowsOf(fileId, key?.let(byKey::get), key?.let(states::get)) }
            val found = comparison.differing()
            val again = before.moved(now) + found.filter { now.keyOf(it) == null }
            val existing = again.chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().valuesOf(it) }.groupBy { it.fileId }
            val stale = differing(again, again.associateWith(::rows), existing)
            val differing = (found.filter { it !in again } + stale).sorted()
            write(differing, differing.associateWith(::rows), snapshot.counter)
        }
        // Counted once it has completed: a run that throws leaves the next request to make one (TI §10.3).
        wholeRuns++
        return raised(changed)
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
     * [SCAN_PARTS] ranges of row IDs read on reader connections of their own at once (TI §10.3), one fewer than the pool
     * has, so that the lists and Now Playing still read meanwhile.
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
                        onScanPart()
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

    /**
     * The rows of [decided] for [fileId], and the row of its [missingInfo], the Keep as-is or Skip that counts (TI §10.1);
     * none without either.
     */
    private fun rowsOf(fileId: Long, decided: Map<DecidedField, Decided>?, missingInfo: Decided?): List<DecidedMirrorEntity> {
        val rows = decided.orEmpty().map { (part, value) -> DecidedMirrorEntity(fileId, part.stored, value.value, value.level, value.decisionId) }
        return if (missingInfo == null) {
            rows
        } else {
            rows + DecidedMirrorEntity(fileId, MissingInfoDecision.FIELD, missingInfo.value, missingInfo.level, missingInfo.decisionId)
        }
    }

    /** The files of [fileIds] whose [desired] rows differ from the [existing] ones. */
    private fun differing(
        fileIds: Collection<Long>,
        desired: Map<Long, List<DecidedMirrorEntity>>,
        existing: Map<Long, List<DecidedMirrorEntity>>,
    ): List<Long> = fileIds.filter { desired[it].orEmpty().toSet() != existing[it].orEmpty().toSet() }

    /**
     * Replaces the decided values of [changed] with their [desired] rows and derives their display rows again, in one
     * transaction that also stores [counter] when one is given. The files it changed: [changed].
     */
    private suspend fun write(changed: List<Long>, desired: Map<Long, List<DecidedMirrorEntity>>, counter: Long?): List<Long> {
        if (changed.isEmpty() && counter == null) return changed
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
        return changed
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

    /**
     * Asks for the checks and the run after a batch of the tag pass (TI §1, §7.1) in the background: the files [read] with
     * their tags and those whose read [failed].
     */
    fun requestRead(read: List<Long>, failed: List<Long>) {
        if (read.isEmpty() && failed.isEmpty()) return
        ask {
            pendingRead.addAll(read)
            pendingFailed.addAll(failed)
        }
    }

    /** Forgets the tries of [files], whose checks have been made. */
    private fun checked(files: List<Long>) {
        synchronized(lock) { if (checkTries.isNotEmpty()) files.forEach(checkTries::remove) }
    }

    /**
     * Asks for the failed checks of the files [read] and [failed] again after [retryDelay], those made fewer than
     * [CHECK_RETRIES] more times (TI §1); the files it asks for, whose run waits for their checks, as whole runs do
     * meanwhile. The others are mirrored without them.
     */
    private fun checkAgain(read: List<Long>, failed: List<Long>): Set<Long> {
        val again = synchronized(lock) {
            val files = (read + failed).filterTo(HashSet()) { (checkTries[it] ?: 0) < CHECK_RETRIES }
            for (file in read + failed) if (file in files) checkTries[file] = (checkTries[file] ?: 0) + 1 else checkTries.remove(file)
            if (files.isNotEmpty()) {
                retrying++
                idle.value = false
            }
            files
        }
        if (again.isEmpty()) return again
        scope.launch {
            var asked = false
            try {
                delay(retryDelay)
                // The files and the end of the wait under one lock: a pass that sees no checks waiting takes their files
                // with it or took them before, and one that sees them waiting is followed by the pass that takes them.
                synchronized(lock) {
                    pendingRead.addAll(read.filter { it in again })
                    pendingFailed.addAll(failed.filter { it in again })
                    retrying--
                    asked = true
                    idle.value = false
                }
                requests.trySend(Unit)
            } finally {
                if (!asked) {
                    synchronized(lock) {
                        retrying--
                        settle()
                    }
                }
            }
        }
        return again
    }

    /**
     * The checks after a batch of the tag pass, before its run: the Skips that lapse with the files it tried (TI §1), which
     * are committed and mirrored as a decision is, then the comparison of the files it read with their decided values
     * (TI §7.1).
     */
    @VisibleForTesting
    internal suspend fun afterRead(read: List<Long>, failed: List<Long>) {
        beforeChecks()
        val lapsed = running.withLock { if (ready()) lapseSkips((read + failed).distinct()) else null }
        if (lapsed != null) {
            // As after a decision: a failed run leaves the lapse committed and asks for a whole run (TI §10.3).
            try {
                mirrorIdentities(lapsed.identityIds, lapsed.counter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The mirror of a lapse failed; a whole run follows", e)
                request()
            }
        }
        if (read.isNotEmpty()) running.withLock { if (ready()) compareRead(read.distinct()) }
    }

    /**
     * Lets lapse the Skips of the tracks of [fileIds] whose stamp no file of the track holds any more (TI §1): the Skip of
     * the track a file belonged to before the read, as its row here names it, and of the track that holds the file's key
     * now or whose location hint is the file's place, as after a sync forgot the key of a changed file. A file without a
     * key yet that holds the stamp may be the skipped file, so it keeps the Skip.
     */
    private suspend fun lapseSkips(fileIds: List<Long>): Decisions.Lapsed? {
        if (fileIds.isEmpty() || !decisions.hasLiveSkips()) return null
        val before = fileIds.chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().missingInfoOf(it) }
            .filter { it.value == MissingInfoDecision.SKIPPED_VALUE }.map { it.decisionId }
        val places = fileIds.chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().placesOf(it) }
        val keys = places.mapNotNull { it.contentKey }
        val spots = places.mapTo(HashSet<Triple<String?, String?, String?>>()) { Triple(it.volume, it.relativePath, it.displayName) }
        val beforeIds = before.toSet()
        val keySet = keys.toSet()
        val candidates = decisions.liveSkipsOf(before, keys, places.map { it.displayName }).filter { skip ->
            skip.decisionId in beforeIds || skip.contentKey in keySet ||
                Triple(skip.hintVolume, skip.hintRelativePath, skip.hintDisplayName) in spots
        }
        if (candidates.isEmpty()) return null
        // The files of the candidates' tracks, by the index of the keys, and the files without a key yet with a candidate's
        // size; of them those with a candidate's size and time.
        val bySize = (
            candidates.map { it.contentKey }.distinct().chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().stampsWithKeys(it) } +
                candidates.mapNotNull { it.size }.distinct().chunked(DecidedMirrorDao.CHUNK).flatMap { library.decided().unkeyedStamps(it) }
            ).groupBy { it.size }
        val lapsing = candidates.filter { skip ->
            val seconds = skip.mtimeMs?.let(SkipLapse::mtimeSeconds)
            val holders = bySize[skip.size].orEmpty().filter { it.mtime == seconds }.map { it.contentKey }
            SkipLapse.lapses(skip.contentKey, skip.size, skip.mtimeMs, holders)
        }
        return if (lapsing.isEmpty()) null else decisions.lapse(lapsing.map { it.decisionId })
    }

    /**
     * Compares the files [fileIds] that the tag pass has just read with the decided values of their tracks, at their
     * locations (TI §7.1), all in one user database transaction; only tracks with decided values are looked at.
     */
    private suspend fun compareRead(fileIds: List<Long>) {
        if (!user.decisions().hasLiveValueDecisions()) return
        val keys = fileIds.chunked(MirrorDao.CHUNK).flatMap { library.mirror().keysOf(it) }.associate { it.fileId to it.contentKey }
        val identityOfKey = keys.values.filterNotNull().distinct().chunked(MirrorDao.CHUNK)
            .flatMap { chunk -> user.identities().withKeys(chunk.map(Hex::bytes)) }
            .associate { Hex.text(it.contentKey) to it.id }
        val decided = decisions.withLiveValues(identityOfKey.values)
        if (decided.isEmpty()) return
        // The tags only of the files whose tracks have decided values.
        val identityOf = keys.mapNotNull { (file, key) -> key?.let(identityOfKey::get)?.takeIf { it in decided }?.let { file to it } }.toMap()
        val items = identityOf.keys.toList().chunked(DecidedMirrorDao.CHUNK).flatMap { library.library().readFilesOf(it) }.map { read ->
            identityOf.getValue(read.fileId) to
                Decisions.LocationValues(read.volume, read.relativePath, read.displayName, DecisionContent.ofTags(TrackEntity.tagsOf(read)))
        }
        decisions.filesChanged(items)
    }


    /** Asks for a run of [fileIds] in the background, without the checks of a tag pass batch ([requestRead]). */
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

    internal companion object {
        private const val TAG = "DecisionMirror"

        /** How many more times failed checks after a batch are made (TI §1). */
        @VisibleForTesting
        internal const val CHECK_RETRIES = 3
        private const val DECIDED_TABLE = "decided_mirror"

        // In file order, which the comparison's search needs.
        private const val KEYED_FILES = "SELECT id, content_key FROM audio_file WHERE content_key IS NOT NULL ORDER BY id"

        private const val ROW_RANGE = "SELECT min(rowid), max(rowid) FROM decided_mirror"

        // A range of row IDs in the order the table holds them: the comparison takes any order, and sorting half a million
        // rows would cost.
        private const val MIRRORED_ROWS = "SELECT file_id, field, value, level, decision_id FROM decided_mirror WHERE rowid BETWEEN ? AND ?"

        /** The ranges a whole run reads `decided_mirror` in at once: every reader of the pool but one. */
        @VisibleForTesting
        internal const val SCAN_PARTS = LibraryDatabase.READERS - 1

        // The columns of DecidedMirrorEntity, in the order the bulk write gives them.
        private val DECIDED_COLUMNS = listOf("file_id", "field", "value", "level", "decision_id")
    }
}
