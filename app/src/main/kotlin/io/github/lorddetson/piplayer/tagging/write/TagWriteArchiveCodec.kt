package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.userdata.AppliedChangeEntity
import io.github.lorddetson.piplayer.userdata.TagBackupEntity
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class ArchivedTagWrite(val entry: AppliedChangeEntity, val backup: TagBackupEntity)

internal object TagWriteArchiveCodec {
    fun encode(records: List<ArchivedTagWrite>): ByteArray {
        val stored = records.map(::stored)
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter().use { it.write(Json.encodeToString(stored)) }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<ArchivedTagWrite> =
        GZIPInputStream(bytes.inputStream()).bufferedReader().use {
            Json.decodeFromString<List<StoredWrite>>(it.readText()).map(::archived)
        }

    private fun stored(record: ArchivedTagWrite): StoredWrite {
        val entry = record.entry
        val backup = record.backup
        require(entry.state == "verified")
        val actualSize = requireNotNull(entry.actualSize)
        require(entry.backupId == backup.id && entry.identityId == backup.identityId &&
            entry.volumeName == backup.volumeName && entry.relativePath == backup.relativePath && entry.displayName == backup.displayName)
        val diff = requireNotNull(TagWriteRecord.decode(entry.diff))
        return StoredWrite(
            entry.id, entry.identityId, entry.decisionId, entry.batchUuid?.base64(), entry.volumeName, entry.relativePath,
            entry.displayName, entry.copySize, entry.expectedContentKey.base64(), entry.tagHash.base64(),
            actualSize.takeUnless { it == entry.copySize }, entry.actualMtime?.minus(entry.createdAt),
            entry.createdAt, entry.verifiedAt?.minus(entry.createdAt), entry.undoneAt,
            StoredDiff(diff.changes.mapValues { (_, change) -> listOf(change.before, change.after) },
                diff.undoOf, diff.conflicts, diff.markedTagHash, diff.coverChange),
            StoredBackup(backup.id, backup.createdAt - entry.createdAt, backup.tagsPath, backup.tagsHash.base64(),
                backup.coverPath, backup.coverHash?.base64(), backup.id3Version),
        )
    }

    private fun archived(record: StoredWrite): ArchivedTagWrite {
        val diff = record.diff
        val backup = record.backup
        val changes = diff.values.mapValues { (_, change) ->
            require(change.size == 2)
            TagChange(change[0], change[1])
        }
        return ArchivedTagWrite(
            AppliedChangeEntity(
                id = record.id, identityId = record.identityId, decisionId = record.decisionId, batchUuid = record.batch?.binary(),
                backupId = backup.id, volumeName = record.volume, relativePath = record.path, displayName = record.name,
                state = "verified", copyPath = "", copySize = record.size, copySha256 = byteArrayOf(),
                expectedContentKey = record.key.binary(), tagHash = record.tagHash.binary(),
                diff = TagWriteRecord(byteArrayOf(), 0, 0, changes, diff.undoOf, diff.conflicts, diff.markedTagHash, diff.coverChange).encode(),
                actualSize = record.actualSize ?: record.size, actualMtime = record.mtimeDelta?.plus(record.createdAt),
                createdAt = record.createdAt, verifiedAt = record.verifiedDelta?.plus(record.createdAt), undoneAt = record.undoneAt,
            ),
            TagBackupEntity(
                id = backup.id, identityId = record.identityId, volumeName = record.volume, relativePath = record.path,
                displayName = record.name, createdAt = record.createdAt + backup.createdDelta,
                tagsPath = backup.tagsPath, tagsHash = backup.tagsHash.binary(),
                coverPath = backup.coverPath, coverHash = backup.coverHash?.binary(), id3Version = backup.id3Version,
            ),
        )
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().withoutPadding().encodeToString(this)

    private fun String.binary(): ByteArray = Base64.getDecoder().decode(this)

    @Serializable
    private data class StoredWrite(
        val id: Long,
        val identityId: Long,
        val decisionId: Long?,
        val batch: String?,
        val volume: String,
        val path: String,
        val name: String,
        val size: Long,
        val key: String,
        val tagHash: String,
        val actualSize: Long? = null,
        val mtimeDelta: Long?,
        val createdAt: Long,
        val verifiedDelta: Long?,
        val undoneAt: Long? = null,
        val diff: StoredDiff,
        val backup: StoredBackup,
    )

    @Serializable
    private data class StoredDiff(
        val values: Map<String, List<List<String>>>,
        val undoOf: Long? = null,
        val conflicts: Set<String> = emptySet(),
        val markedTagHash: Boolean = true,
        val coverChange: TagChange? = null,
    )

    @Serializable
    private data class StoredBackup(
        val id: Long,
        val createdDelta: Long = 0,
        val tagsPath: String,
        val tagsHash: String,
        val coverPath: String? = null,
        val coverHash: String? = null,
        val id3Version: Int?,
    )
}
