package io.github.lorddetson.piplayer.library.identity

import android.util.Log
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.KeyedFileRow
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import io.github.lorddetson.piplayer.library.sync.LibraryStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The attach pass (docs/library.md §8.2): reads the files of the library database while no sync writes, and writes the
 * identities of the user database in one transaction. It writes nothing into the library database, and it does not
 * raise the user database's change counter, as attaching is no decision of the user.
 */
class IdentityAttacher(
    private val library: LibraryDatabase,
    private val user: UserDatabase,
    private val sync: LibrarySync,
    /** The identities that user data refers to; none in M2a, favorites and playlists add theirs from M2b. */
    private val holders: suspend () -> Set<Long> = { emptySet() },
    /** Step 4; null leaves it out. */
    private val fingerprints: FingerprintStep? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether the library knows its files; before its first sync every identity would look missing. */
    private val ready: () -> Boolean = { true },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val idle = MutableStateFlow(true)
    private val lock = Any()
    private var pending = false

    init {
        scope.launch {
            for (request in requests) {
                val run = synchronized(lock) { pending.also { pending = false } }
                if (run) {
                    try {
                        attach()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "The attach pass failed", e)
                    }
                }
                synchronized(lock) { if (!pending) idle.value = true }
            }
        }
    }

    /** Asks for a pass in the background; requests during a pass collapse into one more pass after it. */
    fun request() {
        synchronized(lock) {
            pending = true
            idle.value = false
        }
        requests.trySend(Unit)
    }

    /** Waits until no pass runs and none is asked for. */
    suspend fun awaitIdle() {
        idle.first { it }
    }

    /** Asks for a pass after every sync that changed the files of a library that knows them. */
    fun follow(status: Flow<LibraryStatus>) {
        scope.launch {
            status.filter { it.known }.map { it.revision }.distinctUntilChanged().drop(1).collect { request() }
        }
    }

    fun close() {
        requests.close()
        scope.cancel()
    }

    /** [close], then waits until a pass under way has ended, so that the databases may be closed. */
    suspend fun closeAndJoin() {
        close()
        scope.coroutineContext[Job]?.join()
        running.withLock { }
    }

    /** One pass now, after any that runs; what it wrote. */
    suspend fun attach(): AttachPlan = running.withLock {
        if (!ready()) return@withLock AttachPlan(emptyList(), emptyList(), emptyList())
        val now = clock()
        val files = sync.exclusive { library.library().keyedFiles() }.map(::keyed)
        val dao = user.identities()
        val stored = dao.identities()
        val holding = holders()
        val plan = IdentityMatcher.plan(files, stored.map(::identity), holding, now)
        if (!plan.isEmpty) write(plan, stored.associateBy { it.id }, now)
        fingerprints?.let { step -> takeOver(step, files, holding) }
        dropStaleFingerprints(now)
        plan
    }

    private suspend fun write(plan: AttachPlan, stored: Map<Long, TrackIdentityEntity>, now: Long) {
        val dao = user.identities()
        user.withWriteTransaction {
            // Deleted first: a key an identity gives up may be taken by another in the same pass.
            if (plan.deleted.isNotEmpty()) plan.deleted.chunked(CHUNK).forEach { dao.delete(it) }
            if (plan.updated.isNotEmpty()) {
                // In two steps, so that two identities that swap keys never hold one key at once.
                val moved = plan.updated.map { it.entity(stored.getValue(it.id)) }
                val keysChanged = moved.filter { !it.contentKey.contentEquals(stored.getValue(it.id).contentKey) }
                if (keysChanged.isNotEmpty()) dao.updateAll(keysChanged.map { it.copy(contentKey = placeholder(it.id)) })
                dao.updateAll(moved)
            }
            if (plan.created.isNotEmpty()) dao.addAll(plan.created.map { it.newIdentity(now) })
        }
    }

    /** Step 4: a missing identity that holds data takes over the file of a new identity, which goes. */
    private suspend fun takeOver(step: FingerprintStep, files: List<KeyedFile>, holding: Set<Long>) {
        if (holding.isEmpty()) return
        val dao = user.identities()
        val stored = dao.identities()
        val takeovers = step.takeovers(files, stored.map(::identity), holding)
        if (takeovers.isEmpty()) return
        val byId = stored.associateBy { it.id }
        user.withWriteTransaction {
            dao.delete(takeovers.map { it.replacedId })
            dao.updateAll(takeovers.map { identity(byId.getValue(it.missingId)).following(it.file).entity(byId.getValue(it.missingId)) })
        }
    }

    private suspend fun dropStaleFingerprints(now: Long) {
        val dao = library.library()
        val stale = dao.staleFingerprints(now - IdentityMatcher.KEEP_MISSING_MS)
        if (stale.isEmpty()) return
        val held = user.identities().identities().mapTo(HashSet()) { Hex.text(it.contentKey) }
        stale.filterNot { it in held }.chunked(CHUNK).forEach { dao.deleteFingerprints(it) }
    }

    private fun placeholder(id: Long) = ByteArray(32).also { java.nio.ByteBuffer.wrap(it).putLong(Long.MIN_VALUE + id) }

    private fun keyed(row: KeyedFileRow) = KeyedFile(
        fileId = row.fileId,
        contentKey = row.contentKey,
        place = Place(row.volume, row.relativePath, row.displayName),
        size = row.size,
        durationMs = row.durationMs,
        settled = row.durationSource !in PENDING,
        recordingMbid = Hex.mbidText(Hex.mbidBytes(row.recordingMbid)),
        format = row.format,
        tagsRead = row.tagsRead,
    )

    private fun KeyedFile.newIdentity(now: Long) = TrackIdentityEntity(
        contentKey = Hex.bytes(contentKey),
        durationMs = durationMs,
        recordingMbid = Hex.mbidBytes(recordingMbid),
        hintVolumeName = place.volume,
        hintRelativePath = place.relativePath,
        hintDisplayName = place.displayName,
        hintSize = size,
        createdAt = now,
        missingSince = null,
    )

    private fun Identity.entity(old: TrackIdentityEntity) = old.copy(
        contentKey = Hex.bytes(contentKey),
        durationMs = durationMs,
        recordingMbid = Hex.mbidBytes(recordingMbid),
        hintVolumeName = hint?.volume,
        hintRelativePath = hint?.relativePath,
        hintDisplayName = hint?.displayName,
        hintSize = hintSize,
        missingSince = missingSince,
    )

    companion object {
        /**
         * Raised after every pass that wrote identities, in any library of the process: covers that follow an identity
         * may now belong to another file (docs/library.md §13.3).
         */
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

        private const val TAG = "IdentityAttacher"
        private const val CHUNK = 500
        private val PENDING = setOf(DurationSource.PENDING_WALK, DurationSource.PENDING_FLAC_END)

        fun identity(entity: TrackIdentityEntity): Identity {
            val volume = entity.hintVolumeName
            val path = entity.hintRelativePath
            val name = entity.hintDisplayName
            return Identity(
                id = entity.id,
                contentKey = Hex.text(entity.contentKey),
                durationMs = entity.durationMs,
                recordingMbid = Hex.mbidText(entity.recordingMbid),
                hint = if (volume != null && path != null && name != null) Place(volume, path, name) else null,
                hintSize = entity.hintSize,
                createdAt = entity.createdAt,
                missingSince = entity.missingSince,
                startMs = entity.startMs,
            )
        }
    }
}
