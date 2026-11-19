package io.github.lorddetson.piplayer.tagging.write

import android.os.ParcelFileDescriptor
import android.system.Os
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.fingerprints
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface PreparationResult

internal enum class PreparationFailure : PreparationResult { NO_SPACE, CONTENT_CHANGED, NO_CHANGES, UNVERIFIED, QUEUE_CHANGED }

internal class PreparedTagCopy(
    val file: File,
    val before: TagReport,
    val after: TagReport,
    val diff: TagDiff,
    val tagHash: ByteArray,
    val sha256: ByteArray,
    val originalSha256: ByteArray,
    val originalDevice: Long,
    val originalInode: Long,
    val coverChange: TagChange?,
    val legacyId3Change: LegacyId3Change?,
    val id3v2Changes: Map<String, TagChange>?,
) : PreparationResult

internal class TagWritePreparation(
    private val directory: File,
    private val tagger: TaggerClient,
    private val availableBytes: (File) -> Long,
) {
    /** The original is opened for reading only. A result owns a verified private copy ready to journal. */
    suspend fun prepare(
        target: TagTarget,
        openSource: () -> ParcelFileDescriptor,
        fileName: String,
        expectedKey: String,
        inspectFrontCovers: Boolean = false,
        id3v1Policy: Id3v1WritePolicy = Id3v1WritePolicy.UPDATE_IF_PRESENT,
        verifyId3v2: Boolean = false,
        diff: (TagReport) -> TagDiff?,
    ): PreparationResult = withContext(Dispatchers.IO) {
        var temporary: File? = null
        var verified = false
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@withContext PreparationFailure.UNVERIFIED
            val original = openSource()
            val originalStat = original.useStat()
            val working = ParcelFileDescriptor.AutoCloseInputStream(original).use { input ->
                val size = original.statSize
                if (size <= 0) return@withContext PreparationFailure.UNVERIFIED
                if (size > availableBytes(directory) / 2) return@withContext PreparationFailure.NO_SPACE
                File.createTempFile("tag-write-", ".copy", directory).also { copy ->
                    temporary = copy
                    if (copy.outputStream().use { input.copyTo(it) } != size) return@withContext PreparationFailure.UNVERIFIED
                }
            }
            var before = read(working, target, fileName, frontCoverData = inspectFrontCovers)
                ?: return@withContext PreparationFailure.UNVERIFIED
            if (before.content?.key != expectedKey) return@withContext PreparationFailure.CONTENT_CHANGED
            val selected = diff(before) ?: return@withContext PreparationFailure.CONTENT_CHANGED
            if (selected.frontCovers != null && !inspectFrontCovers) {
                before = read(working, target, fileName, frontCoverData = true) ?: return@withContext PreparationFailure.UNVERIFIED
            }
            val frontCovers = if (selected.frontCovers != null) {
                val existing = before.frontCovers ?: return@withContext PreparationFailure.UNVERIFIED
                selected.frontCovers.takeUnless { it == existing }
            } else null
            val oldId3v1 = before.legacyId3?.rawTag
            val restore = selected.restoreId3v1?.takeUnless { it.ifEmpty { null } == oldId3v1 }
            val removing = oldId3v1 != null && id3v1Policy == Id3v1WritePolicy.REMOVE && before.writeFormat() != TagFileFormat.UNSUPPORTED
            if (selected.changes.isEmpty() && frontCovers == null && restore == null && !removing) {
                return@withContext PreparationFailure.NO_CHANGES
            }
            val edits = selected.changes.mapValues { (_, change) -> change.after }
            // On MPEG files with legacy tags, compare the writer's target separately from the merged display tags.
            // Undo can then remove a new ID3v2 frame and recover its original ID3v1 fallback.
            val id3v2Diff = if (oldId3v1 != null || restore != null || verifyId3v2) {
                val primary = before.mpegId3v2Properties ?: return@withContext PreparationFailure.UNVERIFIED
                TagDiff.exact(primary, edits)
            } else null
            val originalSha256 = sha256(working)

            val written = ParcelFileDescriptor.open(working, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                tagger.writeWorkingCopy(
                    target, descriptor, edits, id3v1Policy, frontCovers,
                    restore?.let { Base64.getDecoder().decode(it) },
                )
            }
            if (written != TaggerResult.Replied(true)) return@withContext PreparationFailure.UNVERIFIED
            val after = read(working, target, fileName, frontCoverData = frontCovers != null) ?: return@withContext PreparationFailure.UNVERIFIED
            val newId3v1 = after.legacyId3?.rawTag
            val legacyMatches = when {
                restore != null -> newId3v1 == restore.ifEmpty { null }
                id3v1Policy == Id3v1WritePolicy.REMOVE -> newId3v1 == null
                id3v1Policy == Id3v1WritePolicy.LEAVE_UNTOUCHED -> newId3v1 == oldId3v1
                else -> (newId3v1 == null) == (oldId3v1 == null)
            }
            val propertiesMatch = if (id3v2Diff != null) after.mpegId3v2Properties == id3v2Diff.properties
                else after.properties == selected.properties
            if (after.content?.key != expectedKey || !propertiesMatch || !legacyMatches ||
                (frontCovers != null && after.frontCovers != frontCovers)
            ) {
                return@withContext PreparationFailure.UNVERIFIED
            }
            // The tag pass keeps legacy bytes marked, so its snapshot has to match this form of the properties.
            val marked = read(working, target, fileName, markLegacy = true) ?: return@withContext PreparationFailure.UNVERIFIED
            if (marked.content?.key != expectedKey) return@withContext PreparationFailure.UNVERIFIED
            FileOutputStream(working, true).use { it.fd.sync() }
            val actualChanges = TagDiff.exact(before.properties, edits.mapValues { (key, _) -> after.properties[key].orEmpty() }).changes
            PreparedTagCopy(
                working, before, after, selected.copy(properties = after.properties, changes = actualChanges),
                tagPropertiesHash(marked.properties), sha256(working), originalSha256,
                originalStat.st_dev, originalStat.st_ino,
                frontCovers?.let { TagChange(checkNotNull(before.frontCovers).fingerprints(), it.fingerprints()) },
                LegacyId3Change(oldId3v1, newId3v1).takeIf { oldId3v1 != newId3v1 },
                id3v2Diff?.changes,
            ).also { verified = true }
        } catch (_: IOException) {
            PreparationFailure.UNVERIFIED
        } finally {
            if (!verified) temporary?.delete()
        }
    }

    private fun ParcelFileDescriptor.useStat() = try {
        Os.fstat(fileDescriptor)
    } catch (error: android.system.ErrnoException) {
        close()
        throw IOException(error)
    }

    private suspend fun read(
        copy: File,
        target: TagTarget,
        fileName: String,
        markLegacy: Boolean = false,
        frontCoverData: Boolean = false,
    ): TagReport? =
        ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            (tagger.read(
                target, descriptor, fileName, markLegacy = markLegacy, background = true,
                frontCoverData = frontCoverData, legacyId3Data = !markLegacy,
            )
                as? TaggerResult.Replied)
                ?.value?.report?.takeIf { it.error == null }
        }
}
