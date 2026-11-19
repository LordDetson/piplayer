package io.github.lorddetson.piplayer.tagging.write

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.storage.StorageManager
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.userdata.TagApplyQueue
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import io.github.lorddetson.piplayer.userdata.UserData
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TagUndoPlan(val identityId: Long, val changeId: Long, val uri: Uri)

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
    tagger: TaggerClient = Tagger.client(context),
    staging: File = File(context.applicationContext.noBackupFilesDir, "tag-writes"),
) {
    private val app = context.applicationContext
    private val queue = TagApplyQueue(database)
    private val plans = TagApplyPlans(library, database)
    private val planning = Mutex()
    private val access = MediaStoreWriteAccess(app)
    private val journal = TagWriteJournal(database)
    private val session = TagWriteSession(
        directory = staging,
        tagger = tagger,
        journal = journal,
        access = access,
        openForRead = { uri -> app.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString()) },
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

    suspend fun results(batchUuid: String): Map<String, Int> = queue.results(batchUuid)

    suspend fun historyForTrack(identityId: Long): List<TagWriteHistoryEntry> = session.historyForTrack(identityId)

    suspend fun undoPlan(identityId: Long, changeId: Long): TagUndoPlan? =
        undoTarget(identityId, changeId)?.let { TagUndoPlan(identityId, changeId, it.uri) }

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
