package io.github.lorddetson.piplayer.tagging.write

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.userdata.TagApplyQueue
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import io.github.lorddetson.piplayer.userdata.UserData
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TagUndoPlan(val identityId: Long, val changeId: Long, val uri: Uri, val displayName: String)

internal data class BatchTagUndoGroup(val batchUuid: String, val files: List<TagUndoPlan>)

internal data class ManualTagPlan(
    val fileId: Long,
    val target: TagWriteTarget,
    val properties: Map<String, List<String>>,
    val format: TagFileFormat,
)

internal data class ReviewTagPlan(
    val file: ManualTagPlan,
    val decisionIds: Set<Long>,
    val changes: Map<String, List<String>>,
)

internal sealed interface TagUndoOutcome {
    data class Restored(val conflicts: Set<String>) : TagUndoOutcome
    data class NoChange(val conflicts: Set<String>) : TagUndoOutcome
    data object Deferred : TagUndoOutcome
    data object Unavailable : TagUndoOutcome
    data object Failed : TagUndoOutcome
}

internal class BatchTagWriter(
    context: Context,
    library: LibraryAccess = LibraryAccess.app(context),
    database: UserDatabase = UserData.database(context),
    private val tagger: TaggerClient = Tagger.client(context),
    staging: File = File(context.applicationContext.noBackupFilesDir, "tag-writes"),
) {
    private val app = context.applicationContext
    private val queue = TagApplyQueue(database)
    private val plans = TagApplyPlans(library, database)
    private val planning = Mutex()
    private val waitingWrites = MutableStateFlow<Map<String, Set<Long>>>(emptyMap())
    val waitingByBatch = waitingWrites.asStateFlow()
    private val access = MediaStoreWriteAccess(app)
    private val journal = TagWriteJournal(database)
    private val session = TagWriteSession(
        directory = staging,
        tagger = tagger,
        journal = journal,
        access = access,
        openForRead = ::openRead,
        availableBytes = { path ->
            val storage = app.getSystemService(StorageManager::class.java)
            storage?.getAllocatableBytes(storage.getUuidForPath(path)) ?: path.usableSpace
        },
    )
    private val work = TagApplyWork(
        queue, session,
        target = plans::target,
        playback = playbackTagWriteGate,
        diff = plans::diff,
    )

    suspend fun nextGroup(batchUuid: String): TagApplyGroup = planning.withLock {
        if (queue.results(batchUuid).isEmpty()) queue.save(plans.forBatch(batchUuid))
        work.nextGroup(batchUuid)
    }

    fun request(group: TagApplyGroup): IntentSender? = access.requestAccess(group.files.values.toList())

    suspend fun apply(group: TagApplyGroup) = work.apply(group, consent = true)

    suspend fun applyWhenAvailable(group: TagApplyGroup, onWaiting: (Boolean) -> Unit) {
        work.applyWhenAvailable(group) { blocked ->
            val fileIds = group.files.filterValues { it.toString() in blocked }.keys
            waitingWrites.update { previous ->
                if (fileIds.isEmpty()) previous - group.planId else previous + (group.planId to fileIds)
            }
            onWaiting(fileIds.isNotEmpty())
        }
    }

    suspend fun awaitPlaybackWrite(fileId: Long) {
        waitingByBatch.first { batches -> batches.values.none { fileId in it } }
    }

    suspend fun results(batchUuid: String): Map<String, Int> = queue.results(batchUuid)

    suspend fun historyForTrack(identityId: Long): List<TagWriteHistoryEntry> = session.historyForTrack(identityId)

    suspend fun historyForBatch(batchUuid: String): List<TagWriteHistoryEntry> =
        Hex.mbidBytes(batchUuid)?.let { session.historyForBatch(it) }.orEmpty()

    suspend fun manualPlan(fileId: Long): ManualTagPlan? = withContext(Dispatchers.IO) {
        val target = plans.manualTarget(fileId) ?: return@withContext null
        val report = openRead(target.uri).use { descriptor ->
            (tagger.read(target.taggerTarget, descriptor, target.displayName) as? TaggerResult.Replied)?.value?.report
        } ?: return@withContext null
        val format = report.writeFormat()
        if (report.error != null || report.content?.key != target.contentKey) {
            return@withContext null
        }
        ManualTagPlan(fileId, target, report.properties, format)
    }

    fun request(plan: ManualTagPlan): IntentSender? = access.requestAccess(listOf(plan.target.uri))

    suspend fun reviewPlan(fileId: Long, decisionId: Long, editId: Long?): ReviewTagPlan? {
        val file = manualPlan(fileId) ?: return null
        val ids = setOfNotNull(decisionId, editId)
        val changes = reviewChanges(file, ids) ?: return null
        if (TagDiff.between(file.properties, changes).changes.isEmpty()) return null
        return ReviewTagPlan(file.copy(target = file.target.copy(decisionId = editId ?: decisionId)), ids, changes)
    }

    suspend fun applyReview(plan: ReviewTagPlan, onWaiting: (Boolean) -> Unit): WriteBackResult? {
        val uri = plan.file.target.uri.toString()
        try {
            while (true) {
                val lease = playbackTagWriteGate.tryWrite(uri)
                if (lease != null) return lease.use {
                    onWaiting(false)
                    if (reviewChanges(plan.file, plan.decisionIds) != plan.changes) WriteBackResult.SOURCE_CHANGED
                    else applySelection(plan.file, plan.changes, emptySet())
                }
                onWaiting(true)
                playbackTagWriteGate.awaitAnyWritable(setOf(uri))
            }
        } finally {
            onWaiting(false)
        }
    }

    private suspend fun reviewChanges(file: ManualTagPlan, ids: Set<Long>): Map<String, List<String>>? =
        plans.reviewValues(file.target.identityId, ids)?.let { projectTags(file.format, file.properties, it).properties }

    suspend fun applyManual(
        plan: ManualTagPlan,
        changes: Map<String, List<String>>,
        fillEmptyOnly: Set<String> = emptySet(),
    ): WriteBackResult? {
        val lease = playbackTagWriteGate.tryWrite(plan.target.uri.toString()) ?: return null
        return lease.use { applySelection(plan, changes, fillEmptyOnly) }
    }

    private suspend fun applySelection(
        plan: ManualTagPlan,
        changes: Map<String, List<String>>,
        fillEmptyOnly: Set<String>,
    ): WriteBackResult? {
        if (plan.format == TagFileFormat.UNSUPPORTED) return null
        val intended = TagDiff.between(plan.properties, changes, fillEmptyOnly).changes
        if (intended.isEmpty()) return null
        val live = plans.manualTarget(plan.fileId) ?: return null
        val target = plan.target
        if (live != target.copy(decisionId = null)) return null
        var changed = false
        val queued = session.prepare(target) { report ->
            changed = intended.any { (key, change) -> report.properties[key].orEmpty() != change.before }
            if (changed) TagDiff(report.properties, emptyMap())
            else TagDiff.between(report.properties, intended.mapValues { (_, change) -> change.after })
        }
        if (changed) return WriteBackResult.SOURCE_CHANGED
        val ready = queued as? QueuedTagWrite.Ready ?: return null
        return session.write(ready.id, target, consent = true)
    }

    private fun openRead(uri: Uri): ParcelFileDescriptor =
        app.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())

    suspend fun undoPlan(identityId: Long, changeId: Long): TagUndoPlan? =
        undoTarget(identityId, changeId)?.let { TagUndoPlan(identityId, changeId, it.uri, it.displayName) }

    suspend fun nextUndoGroup(batchUuid: String, tried: Set<Long> = emptySet()): BatchTagUndoGroup {
        val uuid = Hex.mbidBytes(batchUuid) ?: return BatchTagUndoGroup(batchUuid, emptyList())
        val files = ArrayList<TagUndoPlan>()
        val seen = HashSet<Uri>()
        for (entry in session.undoableForBatch(uuid)) {
            if (entry.id in tried) continue
            val target = plans.target(entry) ?: continue
            if (seen.add(target.uri)) files += TagUndoPlan(entry.identityId, entry.id, target.uri, target.displayName)
            if (files.size == MEDIASTORE_WRITE_REQUEST_LIMIT) break
        }
        return BatchTagUndoGroup(batchUuid, files)
    }

    fun request(group: BatchTagUndoGroup): IntentSender? = access.requestAccess(group.files.map(TagUndoPlan::uri))

    suspend fun undo(group: BatchTagUndoGroup): Map<Long, TagUndoOutcome> {
        val uuid = Hex.mbidBytes(group.batchUuid) ?: return emptyMap()
        val eligible = session.undoableForBatch(uuid).mapTo(HashSet()) { it.id }
        return group.files.associate { plan ->
            plan.changeId to if (plan.changeId in eligible) applyUndo(plan) else TagUndoOutcome.Unavailable
        }
    }

    fun request(plan: TagUndoPlan): IntentSender? = access.requestAccess(listOf(plan.uri))

    suspend fun applyUndo(plan: TagUndoPlan): TagUndoOutcome {
        val target = undoTarget(plan.identityId, plan.changeId)?.takeIf { it.uri == plan.uri } ?: return TagUndoOutcome.Unavailable
        val lease = playbackTagWriteGate.tryWrite(target.uri.toString()) ?: return TagUndoOutcome.Deferred
        return lease.use {
            val undo = session.prepareUndo(plan.changeId, target) ?: return@use TagUndoOutcome.Unavailable
            when (val queued = undo.queued) {
                is QueuedTagWrite.Ready -> {
                    if (session.write(queued.id, target, consent = true) == WriteBackResult.VERIFIED) {
                        TagUndoOutcome.Restored(undo.conflicts)
                    } else {
                        TagUndoOutcome.Failed
                    }
                }
                is QueuedTagWrite.Skipped -> {
                    if (queued.reason == PreparationFailure.NO_CHANGES) TagUndoOutcome.NoChange(undo.conflicts)
                    else TagUndoOutcome.Failed
                }
            }
        }
    }

    private suspend fun undoTarget(identityId: Long, changeId: Long): TagWriteTarget? {
        val entry = journal.entry(changeId) ?: return null
        if (entry.identityId != identityId || entry.state != "verified" || entry.undoneAt != null) return null
        return plans.target(entry)
    }
}
