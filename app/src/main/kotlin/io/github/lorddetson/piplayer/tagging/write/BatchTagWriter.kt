package io.github.lorddetson.piplayer.tagging.write

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.util.Log
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.SongRow
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.library.values.ManualTagValues
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.notifications.TagWriteNotifications
import io.github.lorddetson.piplayer.settings.TagWritingOptions
import io.github.lorddetson.piplayer.settings.TagWritingSettings
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.usesCyrillicTagLanguage
import io.github.lorddetson.piplayer.tagging.TagArtwork
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.fingerprints
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.userdata.AppliedChangeEntity
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.TagApplyQueue
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import io.github.lorddetson.piplayer.userdata.TrackLegacyEncoding
import io.github.lorddetson.piplayer.userdata.UserData
import io.github.lorddetson.piplayer.userdata.UserDatabase
import io.github.lorddetson.piplayer.userdata.decode
import java.io.File
import java.io.FileNotFoundException
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    val options: TagWritingOptions = TagWritingOptions(),
    val legacyId3: TagReport.LegacyId3? = null,
    val legacyDisplay: ManualLegacyTags? = null,
    val savedProperties: Map<String, List<String>> = emptyMap(),
) {
    val displayProperties: Map<String, List<String>> = (legacyDisplay?.displayed ?: properties) + savedProperties
    val removesId3v1: Boolean get() = legacyId3 != null && options.id3v1 == Id3v1WritePolicy.REMOVE
    val withoutRemovedId3v1: Map<String, List<String>>? get() =
        legacyId3?.otherProperties.takeIf { options.id3v1 == Id3v1WritePolicy.REMOVE }
}

internal data class ManualLegacyTags(
    val marked: Map<String, List<String>>,
    val choice: TrackLegacyEncoding?,
    val displayed: Map<String, List<String>>,
)

internal data class ReviewTagPlan(
    val file: ManualTagPlan,
    val decisionIds: Set<Long>,
    val selection: TagFileSelection,
    val expectedCovers: List<String>?,
)

internal data class TagWriteRecovery(
    val id: Long,
    val fileId: Long,
    val target: TagWriteTarget,
    val failed: Boolean,
    val changes: Map<String, TagChange>,
)

internal sealed interface TagUndoOutcome {
    data class Restored(val conflicts: Set<String>) : TagUndoOutcome
    data class NoChange(val conflicts: Set<String>) : TagUndoOutcome
    data object Unavailable : TagUndoOutcome
    data object Failed : TagUndoOutcome
}

internal class BatchTagWriter(
    context: Context,
    library: LibraryAccess = LibraryAccess.app(context),
    private val database: UserDatabase = UserData.database(context),
    private val tagger: TaggerClient = Tagger.client(context),
    staging: File = File(context.applicationContext.noBackupFilesDir, "tag-writes"),
    onLibraryTagsStored: (List<SongRow>) -> Unit = {},
    loadCover: suspend (Long, TagCoverPreset) -> TagArtwork? =
        { id, preset -> Covers.saved(context).file(id)?.let { encodeFrontCover(it, preset) } },
) {
    private val app = context.applicationContext
    private val settings = TagWritingSettings(app)
    private val librarySettings = LibrarySettings(app)
    private val legacyEncodings = database.trackLegacyEncodings()
    private val queue = TagApplyQueue(database)
    private val plans = TagApplyPlans(library, database, loadCover)
    private val planning = Mutex()
    private val waitingWrites = MutableStateFlow<Map<Any, Set<Long>>>(emptyMap())
    val waitingFiles = waitingWrites.map { waiting -> waiting.values.flatten().toSet() }.distinctUntilChanged()
    private val activeSingles = HashSet<Long>()
    private val access = MediaStoreWriteAccess(app)
    private val journal = TagWriteJournal(database)
    private val libraryRefresh = TagWriteLibrary(app, library, journal, legacyEncodings, onLibraryTagsStored)
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
        onLibraryWrite = libraryRefresh::store,
    )
    private val work = TagApplyWork(
        queue, session,
        target = plans::target,
        playback = playbackTagWriteGate,
        diff = plans::diff,
    )

    suspend fun refreshVerifiedLibrary() = withContext(Dispatchers.IO) {
        for (id in journal.pendingLibraryRefresh()) {
            try {
                val entry = journal.entry(id) ?: continue
                val target = plans.target(entry) ?: continue
                playbackTagWriteGate.beginRead(target.uri.toString()).use {
                    val read = session.readForLibrary(target) ?: return@use
                    libraryRefresh.store(id, target, read)
                    access.onVerified(target.uri)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("BatchTagWriter", "Could not refresh the library for a verified write", error)
            }
        }
    }

    suspend fun nextGroup(batchUuid: String): TagApplyGroup = planning.withLock {
        if (queue.results(batchUuid).isEmpty()) queue.save(plans.forBatch(batchUuid))
        work.nextGroup(batchUuid, settings.options.first())
    }

    fun request(group: TagApplyGroup): IntentSender? = access.requestAccess(group.files.values.toList())

    suspend fun apply(group: TagApplyGroup) = work.apply(group, consent = true)

    suspend fun applyWhenAvailable(group: TagApplyGroup, stop: Deferred<Unit>? = null, onWaiting: (Boolean) -> Unit) {
        val operation = Any()
        work.applyWhenAvailable(group, stop) { blocked ->
            val fileIds = group.files.filterValues { it.toString() in blocked }.keys
            updateWaiting(operation, fileIds)
            onWaiting(fileIds.isNotEmpty())
        }
    }

    suspend fun markApplyStarted(planId: String) = queue.markStarted(planId)

    suspend fun hasStartedApplyRemaining(): Boolean = queue.hasStartedWorkRemaining()

    private fun updateWaiting(operation: Any, files: Set<Long>) {
        waitingWrites.update { previous ->
            if (files.isEmpty()) previous - operation else previous + (operation to files)
        }
    }

    suspend fun awaitPlaybackWrite(fileId: Long) {
        waitingFiles.first { fileId !in it }
    }

    fun isWaitingForPlayback(fileId: Long): Boolean = waitingWrites.value.values.any { fileId in it }

    suspend fun results(batchUuid: String): Map<String, Int> = queue.results(batchUuid)

    suspend fun historyForTrack(identityId: Long): List<TagWriteHistoryEntry> = session.historyForTrack(identityId)

    suspend fun historyForBatch(batchUuid: String): List<TagWriteHistoryEntry> =
        Hex.mbidBytes(batchUuid)?.let { session.historyForBatch(it) }.orEmpty()

    suspend fun refreshRecoveryNotification() {
        val pending = journal.interrupted().any { entry -> synchronized(activeSingles) { entry.id !in activeSingles } }
        TagWriteNotifications.showRecovery(app, pending)
    }

    suspend fun recoverableWrites(identityId: Long? = null): List<TagWriteRecovery> =
        journal.resumableSingles(identityId).mapNotNull { entry ->
            if (synchronized(activeSingles) { entry.id in activeSingles }) null else recoveryPlan(entry)
        }

    private suspend fun recoveryPlan(entry: AppliedChangeEntity): TagWriteRecovery? {
        if (entry.batchUuid != null || entry.state !in setOf("pending", "streaming", "streamed", "failed")) return null
        val target = plans.target(entry) ?: return null
        val file = target.taggerTarget as? TagTarget.LibraryFile ?: return null
        val record = TagWriteRecord.decode(entry.diff) ?: return null
        return TagWriteRecovery(entry.id, file.fileId, target, entry.state == "failed", record.changes)
    }

    fun request(recovery: TagWriteRecovery): IntentSender? = access.requestAccess(listOf(recovery.target.uri))

    suspend fun resume(
        recovery: TagWriteRecovery,
        consent: Boolean,
        onWaiting: (Boolean) -> Unit = {},
    ): WriteBackResult {
        if (!consent) return WriteBackResult.NEEDS_CONSENT
        val live = journal.entry(recovery.id)?.let { recoveryPlan(it) } ?: return WriteBackResult.JOURNAL_CHANGED
        if (live.target != recovery.target || live.fileId != recovery.fileId) return WriteBackResult.SOURCE_CHANGED
        return writePrepared(live.id, live.fileId, live.target, onWaiting)
    }

    suspend fun manualPlan(fileId: Long, forEditor: Boolean = false): ManualTagPlan? = withContext(Dispatchers.IO) {
        val target = plans.manualTarget(fileId) ?: return@withContext null
        openRead(target.uri).use { descriptor ->
            val report = (tagger.read(
                target.taggerTarget, descriptor, target.displayName, legacyId3Data = true,
            ) as? TaggerResult.Replied)?.value?.report ?: return@use null
            if (report.error != null || report.content?.key != target.contentKey) return@use null
            val legacy = if (forEditor) readLegacyDisplay(target, descriptor) else null
            val saved = if (forEditor) savedManualProperties(target.identityId) else emptyMap()
            ManualTagPlan(
                fileId, target, report.properties, report.writeFormat(), settings.options.first(), report.legacyId3, legacy, saved,
            )
        }
    }

    private suspend fun savedManualProperties(identityId: Long): Map<String, List<String>> {
        val decisions = Decisions(database).storedOf(identityId).map { it.values }
        return ManualTagValues.decode(DecisionResolver.resolve(decisions)[DecidedField.MANUAL_TAGS]?.value)
    }

    private suspend fun readLegacyDisplay(target: TagWriteTarget, descriptor: ParcelFileDescriptor): ManualLegacyTags? {
        val report = (tagger.read(
            target.taggerTarget, descriptor, target.displayName, markLegacy = true,
        ) as? TaggerResult.Replied)?.value?.report ?: return null
        if (report.error != null || report.content?.key != target.contentKey) return null
        val choice = legacyEncodings.choice(target.identityId)
        val decoded = choice.decode(report.properties, usesCyrillicTagLanguage(app), librarySettings.readLegacyEncoding())
        return ManualLegacyTags(report.properties, choice, decoded.properties).takeIf { decoded.encoding != null }
    }

    suspend fun chooseManualEncoding(plan: ManualTagPlan, name: String?): ManualTagPlan? = withContext(Dispatchers.IO) {
        val legacy = plan.legacyDisplay ?: return@withContext null
        if (plans.manualTarget(plan.fileId) != plan.target) return@withContext null
        val choice = database.withWriteTransaction<TrackLegacyEncoding?> {
            val id = database.identities().idOfKey(Hex.bytes(plan.target.contentKey))
            if (id == null || id != plan.target.identityId) return@withWriteTransaction null
            TrackLegacyEncoding(id, name, pending = true).also { legacyEncodings.save(it) }
        } ?: return@withContext null
        val decoded = choice.decode(legacy.marked, usesCyrillicTagLanguage(app), librarySettings.readLegacyEncoding())
        plan.copy(legacyDisplay = legacy.copy(choice = choice, displayed = decoded.properties))
    }

    fun request(plan: ManualTagPlan): IntentSender? = access.requestAccess(listOf(plan.target.uri))

    suspend fun previewId3v1(plan: ManualTagPlan, changes: Map<String, TagChange>): Set<String>? {
        val legacy = plan.legacyId3 ?: return null
        val tag = Base64.getDecoder().decode(legacy.rawTag)
        val result = tagger.previewId3v1(plan.target.taggerTarget, tag, changes.mapValues { it.value.after })
        return (result as? TaggerResult.Replied)?.value
    }

    suspend fun reviewPlan(fileId: Long, decisionId: Long, editId: Long?): ReviewTagPlan? {
        val file = manualPlan(fileId) ?: return null
        val ids = setOfNotNull(decisionId, editId)
        val values = plans.reviewValues(file.target.identityId, ids) ?: return null
        val selected = plans.selection(values, file.options.cover) ?: return null
        val diff = selected.diff(file.format, file.properties, file.withoutRemovedId3v1)
        val oldCovers = if (diff.frontCovers != null) readFrontCovers(file) ?: return null else null
        val stillToWrite = diff.frontCovers.takeUnless { it == oldCovers }
        if (diff.changes.isEmpty() && stillToWrite == null && !file.removesId3v1) return null
        return ReviewTagPlan(
            file.copy(target = file.target.copy(decisionId = editId ?: decisionId)), ids,
            selected.copy(frontCovers = stillToWrite), oldCovers?.fingerprints().takeIf { stillToWrite != null },
        )
    }

    private suspend fun readFrontCovers(file: ManualTagPlan): List<TagArtwork>? = withContext(Dispatchers.IO) {
        openRead(file.target.uri).use { descriptor ->
            val read = tagger.read(file.target.taggerTarget, descriptor, file.target.displayName, frontCoverData = true) as? TaggerResult.Replied
            val report = read?.value?.report ?: return@use null
            report.frontCovers.takeIf { report.content?.key == file.target.contentKey && report.error == null }
        }
    }

    suspend fun applyReview(plan: ReviewTagPlan, onWaiting: (Boolean) -> Unit): WriteBackResult? {
        val file = plan.file
        val stillApproved: suspend () -> Boolean = {
            plans.reviewValues(file.target.identityId, plan.decisionIds) == plan.selection.values
        }
        if (!stillApproved()) return WriteBackResult.SOURCE_CHANGED
        val diff = plan.selection.diff(file.format, file.properties, file.withoutRemovedId3v1)
        return applySelection(
            file, diff.changes.mapValues { it.value.after }, emptySet(), diff.frontCovers,
            plan.expectedCovers, preserveAcceptedId3v1Values = true, onWaiting = onWaiting, stillApproved = stillApproved,
        )
    }

    suspend fun applyManual(
        plan: ManualTagPlan,
        changes: Map<String, List<String>>,
        fillEmptyOnly: Set<String> = emptySet(),
        onWaiting: (Boolean) -> Unit = {},
    ): WriteBackResult? = applySelection(plan, changes, fillEmptyOnly, onWaiting = onWaiting)

    private suspend fun writePrepared(
        id: Long,
        fileId: Long,
        target: TagWriteTarget,
        onWaiting: (Boolean) -> Unit,
        stillApproved: suspend () -> Boolean = { true },
    ): WriteBackResult {
        if (!synchronized(activeSingles) { activeSingles.add(id) }) return WriteBackResult.JOURNAL_CHANGED
        val operation = Any()
        val uri = target.uri.toString()
        val affected = setOf(fileId)
        var waiting = false
        try {
            while (true) {
                val lease = playbackTagWriteGate.tryWrite(uri)
                if (lease != null) return lease.use {
                    if (!stillApproved()) {
                        session.abandonPending(id)
                        WriteBackResult.SOURCE_CHANGED
                    } else {
                        session.write(id, target, consent = true).also { result ->
                            if (result == WriteBackResult.SOURCE_CHANGED || result == WriteBackResult.INVALID_COPY) {
                                session.abandonPending(id)
                            }
                        }
                    }
                }
                if (!waiting) {
                    waiting = true
                    updateWaiting(operation, affected)
                    onWaiting(true)
                }
                playbackTagWriteGate.awaitAnyWritable(setOf(uri))
            }
        } finally {
            synchronized(activeSingles) { activeSingles.remove(id) }
            if (waiting) {
                updateWaiting(operation, emptySet())
                onWaiting(false)
            }
        }
    }

    private suspend fun applySelection(
        plan: ManualTagPlan,
        changes: Map<String, List<String>>,
        fillEmptyOnly: Set<String>,
        frontCovers: List<TagArtwork>? = null,
        expectedCovers: List<String>? = null,
        preserveAcceptedId3v1Values: Boolean = false,
        onWaiting: (Boolean) -> Unit,
        stillApproved: suspend () -> Boolean = { true },
    ): WriteBackResult? {
        if (plan.format == TagFileFormat.UNSUPPORTED) return null
        val compareWithoutId3v1 = preserveAcceptedId3v1Values && plan.removesId3v1
        val current = if (compareWithoutId3v1) checkNotNull(plan.withoutRemovedId3v1) else plan.properties
        val intended = TagDiff.between(current, changes, fillEmptyOnly).changes
        if (intended.isEmpty() && frontCovers == null && !plan.removesId3v1) return null
        val live = plans.manualTarget(plan.fileId) ?: return null
        val target = plan.target
        if (live != target.copy(decisionId = null)) return null
        val queued = session.prepare(
            target, inspectFrontCovers = expectedCovers != null, id3v1Policy = plan.options.id3v1,
        ) { report ->
            val latest = if (compareWithoutId3v1) report.legacyId3?.otherProperties.orEmpty() else report.properties
            val changed = intended.any { (key, change) -> latest[key].orEmpty() != change.before } ||
                (expectedCovers != null && report.frontCovers?.fingerprints() != expectedCovers) ||
                report.legacyId3?.rawTag != plan.legacyId3?.rawTag
            if (changed) null
            else TagDiff.exact(latest, intended.mapValues { (_, change) -> change.after }).copy(frontCovers = frontCovers)
        }
        if (queued == QueuedTagWrite.Skipped(PreparationFailure.CONTENT_CHANGED)) return WriteBackResult.SOURCE_CHANGED
        val ready = queued as? QueuedTagWrite.Ready ?: return null
        return writePrepared(ready.id, plan.fileId, target, onWaiting, stillApproved)
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

    suspend fun undo(group: BatchTagUndoGroup, onWaiting: (Boolean) -> Unit = {}): Map<Long, TagUndoOutcome> {
        val uuid = Hex.mbidBytes(group.batchUuid) ?: return emptyMap()
        val eligible = session.undoableForBatch(uuid).mapTo(HashSet()) { it.id }
        val (accepted, unavailable) = group.files.partition { it.changeId in eligible }
        return undoWhenAvailable(accepted, onWaiting) + unavailable.associate { it.changeId to TagUndoOutcome.Unavailable }
    }

    fun request(plan: TagUndoPlan): IntentSender? = access.requestAccess(listOf(plan.uri))

    suspend fun applyUndo(plan: TagUndoPlan, onWaiting: (Boolean) -> Unit = {}): TagUndoOutcome =
        undoWhenAvailable(listOf(plan), onWaiting).getValue(plan.changeId)

    private suspend fun undoWhenAvailable(plans: List<TagUndoPlan>, onWaiting: (Boolean) -> Unit): Map<Long, TagUndoOutcome> {
        val operation = Any()
        val pending = plans.toMutableList()
        val results = mutableMapOf<Long, TagUndoOutcome>()
        try {
            while (pending.isNotEmpty()) {
                val blockedFiles = mutableSetOf<Long>()
                val blockedUris = mutableSetOf<String>()
                val remaining = pending.iterator()
                while (remaining.hasNext()) {
                    val plan = remaining.next()
                    val target = undoTarget(plan.identityId, plan.changeId)?.takeIf { it.uri == plan.uri }
                    val file = target?.taggerTarget as? TagTarget.LibraryFile
                    if (target == null || file == null) {
                        results[plan.changeId] = TagUndoOutcome.Unavailable
                    } else {
                        val uri = target.uri.toString()
                        val lease = playbackTagWriteGate.tryWrite(uri)
                        if (lease == null) {
                            blockedFiles += file.fileId
                            blockedUris += uri
                            continue
                        }
                        results[plan.changeId] = lease.use { undoWithAccess(plan.changeId, target) }
                    }
                    remaining.remove()
                }
                if (blockedUris.isEmpty()) break
                updateWaiting(operation, blockedFiles)
                onWaiting(true)
                playbackTagWriteGate.awaitAnyWritable(blockedUris)
            }
            return results
        } finally {
            updateWaiting(operation, emptySet())
            onWaiting(false)
        }
    }

    private suspend fun undoWithAccess(changeId: Long, target: TagWriteTarget): TagUndoOutcome {
        val undo = session.prepareUndo(changeId, target) ?: return TagUndoOutcome.Unavailable
        return when (val queued = undo.queued) {
            is QueuedTagWrite.Ready -> {
                if (session.write(queued.id, target, consent = true) == WriteBackResult.VERIFIED) TagUndoOutcome.Restored(undo.conflicts)
                else TagUndoOutcome.Failed
            }
            is QueuedTagWrite.Skipped -> {
                if (queued.reason == PreparationFailure.NO_CHANGES) TagUndoOutcome.NoChange(undo.conflicts)
                else TagUndoOutcome.Failed
            }
        }
    }

    private suspend fun undoTarget(identityId: Long, changeId: Long): TagWriteTarget? {
        val entry = journal.entry(changeId) ?: return null
        if (entry.identityId != identityId || entry.state != "verified" || entry.undoneAt != null) return null
        return plans.target(entry)
    }
}
