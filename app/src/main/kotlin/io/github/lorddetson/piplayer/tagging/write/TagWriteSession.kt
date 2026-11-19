package io.github.lorddetson.piplayer.tagging.write

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.StructStat
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.userdata.AppliedChangeEntity
import io.github.lorddetson.piplayer.userdata.TagApplyItem
import io.github.lorddetson.piplayer.userdata.TagBackupEntity
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class TagWriteTarget(
    val uri: Uri,
    val taggerTarget: TagTarget,
    val identityId: Long,
    val decisionId: Long?,
    val batchUuid: ByteArray?,
    val volume: String,
    val relativePath: String,
    val displayName: String,
    val contentKey: String,
)

internal sealed interface QueuedTagWrite {
    data class Ready(val id: Long) : QueuedTagWrite
    data class Skipped(val reason: PreparationFailure) : QueuedTagWrite
}

internal data class TagUndo(val queued: QueuedTagWrite, val conflicts: Set<String>)

internal data class TagWriteHistoryEntry(
    val id: Long,
    val displayName: String,
    val at: Long,
    val undoneAt: Long?,
    val undoOf: Long?,
    val changes: Map<String, TagChange>,
    val conflicts: Set<String>,
)

internal class TagWriteSession(
    private val directory: File,
    private val tagger: TaggerClient,
    private val journal: TagWriteJournal,
    private val access: TagWriteAccess,
    private val openForRead: (Uri) -> ParcelFileDescriptor,
    availableBytes: (File) -> Long,
) {
    private val copies = File(directory, "copies")
    private val snapshots = File(directory, "snapshots")
    private val preparation = TagWritePreparation(copies, tagger, availableBytes)
    private val writing = Mutex()

    suspend fun historyForTrack(identityId: Long): List<TagWriteHistoryEntry> = withContext(Dispatchers.IO) {
        journal.forTrack(identityId).mapNotNull { entry ->
            val record = decodeRecord(entry.diff) ?: return@mapNotNull null
            TagWriteHistoryEntry(
                entry.id, entry.displayName, entry.verifiedAt ?: entry.createdAt, entry.undoneAt,
                record.undoOf, record.changes, record.conflicts,
            )
        }
    }

    suspend fun prepareUndo(id: Long, target: TagWriteTarget): TagUndo? = writing.withLock {
        val original = undoable(id, target) ?: return@withLock null
        val record = decodeRecord(original.diff) ?: return@withLock null
        for (pending in journal.resumableForTrack(target.identityId)) {
            if (!pending.matches(target)) continue
            val reversal = decodeRecord(pending.diff) ?: continue
            if (reversal.undoOf == id) return@withLock TagUndo(QueuedTagWrite.Ready(pending.id), reversal.conflicts)
        }
        var conflicts = emptySet<String>()
        val queued = prepare(target, undoOf = id, item = null) { report ->
            TagDiff.undo(report.properties, record.changes).also { conflicts = it.conflicts }
        }
        TagUndo(queued, conflicts)
    }

    suspend fun prepare(target: TagWriteTarget, diff: (TagReport) -> TagDiff): QueuedTagWrite =
        prepare(target, undoOf = null, item = null, diff)

    suspend fun prepareForApply(target: TagWriteTarget, item: TagApplyItem, diff: (TagReport) -> TagDiff): QueuedTagWrite =
        prepare(target, undoOf = null, item, diff)

    private suspend fun prepare(
        target: TagWriteTarget,
        undoOf: Long?,
        item: TagApplyItem?,
        diff: (TagReport) -> TagDiff,
    ): QueuedTagWrite = withContext(Dispatchers.IO) {
        val result = preparation.prepare(target.taggerTarget, { openForRead(target.uri) }, target.displayName, target.contentKey, diff)
        if (result is PreparationFailure) return@withContext QueuedTagWrite.Skipped(result)
        val copy = result as PreparedTagCopy
        val snapshot = File(snapshots, copy.file.nameWithoutExtension + ".json")
        var journaled = false
        try {
            if (!snapshots.isDirectory && !snapshots.mkdirs()) throw IOException("Could not create the tag snapshot directory")
            snapshot.outputStream().use {
                it.write(Json.encodeToString(copy.before.properties).toByteArray())
                it.fd.sync()
            }
            val at = System.currentTimeMillis()
            val backup = TagBackupEntity(
                identityId = target.identityId, volumeName = target.volume, relativePath = target.relativePath,
                displayName = target.displayName, createdAt = at, tagsPath = "snapshots/${snapshot.name}",
                tagsHash = sha256(snapshot), coverPath = null, coverHash = null, id3Version = copy.before.id3Version,
            )
            val entry = AppliedChangeEntity(
                identityId = target.identityId, decisionId = target.decisionId, batchUuid = target.batchUuid,
                backupId = 0, volumeName = target.volume, relativePath = target.relativePath,
                displayName = target.displayName, state = "pending", copyPath = copy.file.name,
                copySize = copy.file.length(), copySha256 = copy.sha256, expectedContentKey = Hex.bytes(target.contentKey),
                tagHash = copy.tagHash, diff = encodeRecord(copy, undoOf), createdAt = at,
            )
            val id = withContext(NonCancellable) {
                val written = if (item == null) journal.prepare(backup, entry) else journal.prepareForApply(backup, entry, item)
                journaled = written != null
                written
            }
            if (id == null) QueuedTagWrite.Skipped(PreparationFailure.QUEUE_CHANGED) else QueuedTagWrite.Ready(id)
        } catch (_: IOException) {
            QueuedTagWrite.Skipped(PreparationFailure.UNVERIFIED)
        } finally {
            if (!journaled) {
                snapshot.delete()
                copy.file.delete()
            }
        }
    }

    suspend fun write(id: Long, target: TagWriteTarget, consent: Boolean): WriteBackResult = writing.withLock {
        withContext(Dispatchers.IO) {
            if (!consent) return@withContext WriteBackResult.NEEDS_CONSENT
            val entry = journal.entry(id) ?: return@withContext WriteBackResult.JOURNAL_CHANGED
            if (!entry.matches(target)) return@withContext WriteBackResult.SOURCE_CHANGED
            if (entry.state == "verified") return@withContext WriteBackResult.VERIFIED
            val copy = File(copies, entry.copyPath)
            try {
                if (copy.canonicalFile.parentFile != copies.canonicalFile) return@withContext WriteBackResult.INVALID_COPY
                val record = decodeRecord(entry.diff) ?: return@withContext WriteBackResult.INVALID_COPY
                if (record.undoOf != null && undoable(record.undoOf, target) == null) {
                    return@withContext WriteBackResult.JOURNAL_CHANGED
                }
                var recovering = entry.state != "pending"
                TagWriteBack(
                    journal = object : TagWriteBack.Journal {
                        override suspend fun streamed() = journal.streamed(id)
                        override suspend fun verified(stamp: Stamp) =
                            journal.verified(id, stamp.size, stamp.modifiedMs, System.currentTimeMillis(), record.undoOf)
                        override suspend fun failed() = journal.failed(id)
                    },
                    stream = {
                        if (!sourceCanBeReplaced(target, record, copy, recovering)) throw SourceChanged()
                        if (!journal.beginStreaming(id)) throw JournalChanged()
                        recovering = true
                        ParcelFileDescriptor.AutoCloseOutputStream(access.openForWrite(target.uri)).use { output ->
                            copy.inputStream().use { it.copyTo(output) }
                            output.fd.sync()
                        }
                    },
                    verify = { verifyOriginal(target, entry, record) },
                ).run(copy, entry.copySize, entry.copySha256, consent)
            } catch (_: SourceChanged) {
                WriteBackResult.SOURCE_CHANGED
            } catch (_: JournalChanged) {
                WriteBackResult.JOURNAL_CHANGED
            } catch (_: SecurityException) {
                WriteBackResult.NEEDS_CONSENT
            } catch (_: IOException) {
                // The original may be partial. Keep the working copy and journal for explicit recovery.
                WriteBackResult.INTERRUPTED
            }
        }
    }

    private fun sourceCanBeReplaced(target: TagWriteTarget, record: WriteRecord, copy: File, recovering: Boolean): Boolean {
        val first = openForRead(target.uri)
        val unchanged = ParcelFileDescriptor.AutoCloseInputStream(first).use { input ->
            if (!stat(first).sameFile(record)) return false
            MessageDigest.isEqual(sha256(input), record.originalSha256)
        }
        if (unchanged) return true
        if (!recovering) return false

        // If an earlier stream stopped after truncation, the bytes on disk are a prefix of the verified copy.
        val original = openForRead(target.uri)
        return ParcelFileDescriptor.AutoCloseInputStream(original).use { input ->
            val info = stat(original)
            info.sameFile(record) && info.st_size <= copy.length() && copy.inputStream().use { expected ->
                isPrefix(DataInputStream(input), DataInputStream(expected), info.st_size)
            }
        }
    }

    private suspend fun verifyOriginal(target: TagWriteTarget, entry: AppliedChangeEntity, record: WriteRecord): Stamp? {
        val descriptor = openForRead(target.uri)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val info = stat(descriptor)
            if (!info.sameFile(record) || info.st_size != entry.copySize) return null
            if (!MessageDigest.isEqual(sha256(input), entry.copySha256)) return null
            val result = tagger.read(
                target.taggerTarget, descriptor, target.displayName, markLegacy = record.markedTagHash, background = true,
            ) as? TaggerResult.Replied
            val report = result?.value?.report ?: return null
            if (report.error != null || report.content?.key != target.contentKey ||
                !MessageDigest.isEqual(tagPropertiesHash(report.properties), entry.tagHash)
            ) {
                return null
            }
            val observed = stat(descriptor)
            if (!observed.sameFile(record) || observed.st_size != info.st_size ||
                observed.st_mtim.tv_sec != info.st_mtim.tv_sec || observed.st_mtim.tv_nsec != info.st_mtim.tv_nsec
            ) return null
            Stamp(info.st_size, info.st_mtim.tv_sec * 1_000 + info.st_mtim.tv_nsec / 1_000_000)
        }
    }

    private class SourceChanged : IOException()
    private class JournalChanged : IOException()

    private fun stat(descriptor: ParcelFileDescriptor): StructStat = try {
        Os.fstat(descriptor.fileDescriptor)
    } catch (error: ErrnoException) {
        throw IOException(error)
    }

    private fun StructStat.sameFile(record: WriteRecord): Boolean =
        st_dev == record.originalDevice && st_ino == record.originalInode

    private fun AppliedChangeEntity.matches(target: TagWriteTarget): Boolean =
        identityId == target.identityId && volumeName == target.volume && relativePath == target.relativePath &&
            displayName == target.displayName && expectedContentKey.contentEquals(Hex.bytes(target.contentKey))

    private suspend fun undoable(id: Long, target: TagWriteTarget): AppliedChangeEntity? =
        journal.entry(id)?.takeIf { it.state == "verified" && it.undoneAt == null && it.matches(target) }

    private fun encodeRecord(copy: PreparedTagCopy, undoOf: Long?): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter().use {
            val record = WriteRecord(
                copy.originalSha256, copy.originalDevice, copy.originalInode, copy.diff.changes, undoOf, copy.diff.conflicts, markedTagHash = true,
            )
            it.write(Json.encodeToString(record))
        }
        return bytes.toByteArray()
    }

    private fun decodeRecord(bytes: ByteArray): WriteRecord? = try {
        GZIPInputStream(bytes.inputStream()).bufferedReader().use { Json.decodeFromString(it.readText()) }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isPrefix(original: DataInputStream, expected: DataInputStream, size: Long): Boolean {
        val observedBytes = ByteArray(DEFAULT_BUFFER_SIZE)
        val expectedBytes = ByteArray(DEFAULT_BUFFER_SIZE)
        var left = size
        while (left > 0) {
            val count = minOf(left, observedBytes.size.toLong()).toInt()
            original.readFully(observedBytes, 0, count)
            expected.readFully(expectedBytes, 0, count)
            for (index in 0 until count) if (observedBytes[index] != expectedBytes[index]) return false
            left -= count
        }
        return original.read() == -1
    }

    @Serializable
    private data class WriteRecord(
        val originalSha256: ByteArray,
        val originalDevice: Long,
        val originalInode: Long,
        val changes: Map<String, TagChange>,
        val undoOf: Long? = null,
        val conflicts: Set<String> = emptySet(),
        val markedTagHash: Boolean = false,
    )
}
