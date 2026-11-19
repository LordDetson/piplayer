package io.github.lorddetson.piplayer.tagging.write

import android.content.ContentUris
import android.provider.MediaStore
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.userdata.BatchDecisionTrackRow
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.TagApplyItem
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal class TagApplyPlans(private val library: LibraryAccess, private val user: UserDatabase) {
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
        if (desired.isEmpty()) return null
        return { report ->
            val projection = projectTags(report.writeFormat(), report.properties, desired)
            TagDiff.between(report.properties, projection.properties)
        }
    }

    suspend fun target(item: TagApplyItem): TagWriteTarget? {
        val uuid = Hex.mbidBytes(item.planId) ?: return null
        val decision = user.decisions().decision(item.decisionId) ?: return null
        if (!decision.batchUuid.contentEquals(uuid) || (decision.undoneAt != null && item.writeId == null)) return null
        val identity = user.identities().identity(decision.identityId) ?: return null
        if (identity.startMs != 0L || Hex.text(identity.contentKey) != item.contentKey) return null

        val file = withContext(Dispatchers.IO) {
            library.use { database ->
                runBlocking {
                    val dao = database.library()
                    val atPlace = dao.foundFilesAt(item.volumeName, item.relativePath, item.displayName).singleOrNull()
                    atPlace?.let { dao.file(it.fileId) }
                }
            }
        } ?: return null
        if (file.mediaStoreId <= 0) return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(file.volumeName), file.mediaStoreId)
        return TagWriteTarget(
            uri, TagTarget.LibraryFile(file.id), identity.id, decision.id, uuid,
            file.volumeName, file.relativePath, file.displayName, item.contentKey,
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
