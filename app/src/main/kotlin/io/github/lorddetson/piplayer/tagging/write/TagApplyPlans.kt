package io.github.lorddetson.piplayer.tagging.write

import android.content.ContentUris
import android.provider.MediaStore
import io.github.lorddetson.piplayer.library.db.AudioFileEntity
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.tagging.TagArtwork
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.userdata.AppliedChangeEntity
import io.github.lorddetson.piplayer.userdata.BatchDecisionTrackRow
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.TagApplyItem
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal data class TagFileSelection(val values: Map<DecidedField, String?>, val frontCovers: List<TagArtwork>?) {
    fun diff(format: TagFileFormat, properties: Map<String, List<String>>): TagDiff {
        val projected = projectTags(format, properties, values)
        return TagDiff.between(properties, projected.properties)
            .copy(frontCovers = frontCovers.takeUnless { format == TagFileFormat.UNSUPPORTED })
    }
}

internal class TagApplyPlans(
    private val library: LibraryAccess,
    private val user: UserDatabase,
    private val loadCover: suspend (Long) -> TagArtwork? = { null },
) {
    suspend fun diff(item: TagApplyItem): ((TagReport) -> TagDiff)? {
        val uuid = Hex.mbidBytes(item.planId) ?: return null
        val selected = user.decisions().decision(item.decisionId) ?: return null
        if (!selected.batchUuid.contentEquals(uuid) || selected.undoneAt != null) return null
        val stored = Decisions(user).storedOf(selected.identityId)
        val approved = stored.filter { decision ->
            decision.row.batchUuid.contentEquals(uuid) && decision.row.id <= item.decisionId && decision.row.undoneAt == null &&
                (decision.values.kind == DecisionKind.APPLY || decision.values.kind == DecisionKind.EDIT)
        }.mapTo(HashSet()) { it.row.id }
        val desired = DecisionResolver.resolve(stored.map { it.values })
            .filterValues { it.decisionId in approved }.mapValues { it.value.value }
        val selection = selection(desired) ?: return null
        return { report -> selection.diff(report.writeFormat(), report.properties) }
    }

    suspend fun selection(values: Map<DecidedField, String?>): TagFileSelection? {
        if (values.isEmpty()) return null
        val coverId = values[DecidedField.COVER]
        val cover = if (coverId != null) loadCover(coverId.toLongOrNull() ?: return null) ?: return null else null
        return TagFileSelection(values, cover?.let(::listOf))
    }

    suspend fun target(item: TagApplyItem): TagWriteTarget? {
        val uuid = Hex.mbidBytes(item.planId) ?: return null
        val decision = user.decisions().decision(item.decisionId) ?: return null
        if (!decision.batchUuid.contentEquals(uuid) || (decision.undoneAt != null && item.writeId == null)) return null
        val identity = user.identities().identity(decision.identityId) ?: return null
        if (identity.startMs != 0L || Hex.text(identity.contentKey) != item.contentKey) return null
        return targetAt(Place(item.volumeName, item.relativePath, item.displayName), identity.id, decision.id, uuid, item.contentKey)
    }

    suspend fun target(entry: AppliedChangeEntity): TagWriteTarget? {
        val identity = user.identities().identity(entry.identityId) ?: return null
        if (identity.startMs != 0L || !identity.contentKey.contentEquals(entry.expectedContentKey)) return null
        return targetAt(
            Place(entry.volumeName, entry.relativePath, entry.displayName), identity.id, entry.decisionId,
            entry.batchUuid, Hex.text(entry.expectedContentKey),
        )
    }

    suspend fun manualTarget(fileId: Long): TagWriteTarget? {
        val file = withContext(Dispatchers.IO) { library.use { database -> runBlocking { database.library().file(fileId) } } }
            ?: return null
        val key = file.contentKey ?: return null
        val identityId = user.identities().idOfKey(Hex.bytes(key)) ?: return null
        return file.writeTarget(identityId, null, null, key)
    }

    suspend fun reviewValues(identityId: Long, decisionIds: Set<Long>): Map<DecidedField, String?>? {
        val stored = Decisions(user).storedOf(identityId)
        val approved = stored.filter { decision ->
            decision.row.id in decisionIds && decision.row.batchUuid == null && decision.row.undoneAt == null &&
                (decision.values.kind == DecisionKind.APPLY || decision.values.kind == DecisionKind.EDIT)
        }
        if (approved.size != decisionIds.size) return null
        return DecisionResolver.resolve(stored.map { it.values })
            .filterValues { it.decisionId in decisionIds }.mapValues { it.value.value }
    }

    private suspend fun targetAt(
        place: Place,
        identity: Long,
        decision: Long?,
        batch: ByteArray?,
        key: String,
    ): TagWriteTarget? {
        val file = withContext(Dispatchers.IO) {
            library.use { database ->
                runBlocking {
                    val dao = database.library()
                    val atPlace = dao.foundFilesAt(place.volume, place.relativePath, place.displayName).singleOrNull()
                    atPlace?.let { dao.file(it.fileId) }
                }
            }
        } ?: return null
        return file.writeTarget(identity, decision, batch, key)
    }

    private fun AudioFileEntity.writeTarget(identity: Long, decision: Long?, batch: ByteArray?, key: String): TagWriteTarget? {
        if (mediaStoreId <= 0) return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volumeName), mediaStoreId)
        return TagWriteTarget(
            uri, TagTarget.LibraryFile(id), identity, decision, batch,
            volumeName, relativePath, displayName, key,
        )
    }

    suspend fun forBatch(batchUuid: String): List<TagApplyItem> {
        val uuid = Hex.mbidBytes(batchUuid) ?: return emptyList()
        val files = withContext(Dispatchers.IO) {
            library.use { database ->
                runBlocking {
                    val batches = database.batches()
                    if (batches.batch(batchUuid)?.status == BatchEntity.STATUS_DONE) batches.places(batchUuid) else emptyList()
                }
            }
        }
        if (files.isEmpty()) return emptyList()

        val decisions = user.decisions().decidedTracksOfBatch(uuid)
        val byKey = decisions.associateBy { Hex.text(it.contentKey) }
        val byPlace = decisions.mapNotNull { row -> row.place()?.let { it to row } }.toMap()
        return files.distinctBy { it.fileId }.mapNotNull { file ->
            val place = Place(file.volume, file.relativePath, file.displayName)
            val chosen = byPlace[place] ?: byKey[file.contentKey] ?: return@mapNotNull null
            if (chosen.kind != DecisionKind.APPLY.code && chosen.kind != DecisionKind.EDIT.code) return@mapNotNull null
            TagApplyItem(batchUuid, file.fileId, Hex.text(chosen.contentKey), chosen.decisionId, file.volume, file.relativePath, file.displayName)
        }
    }

    private fun BatchDecisionTrackRow.place(): Place? {
        return Place(hintVolume ?: return null, hintRelativePath ?: return null, hintDisplayName ?: return null)
    }
}
