package io.github.lorddetson.piplayer.tagging.write

import android.os.ParcelFileDescriptor
import android.system.Os
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
        diff: (TagReport) -> TagDiff,
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
            val before = read(working, target, fileName) ?: return@withContext PreparationFailure.UNVERIFIED
            if (before.content?.key != expectedKey) return@withContext PreparationFailure.CONTENT_CHANGED
            val selected = diff(before)
            if (selected.changes.isEmpty()) return@withContext PreparationFailure.NO_CHANGES
            val originalSha256 = sha256(working)

            val written = ParcelFileDescriptor.open(working, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                tagger.writeWorkingCopy(target, descriptor, selected.changes.mapValues { (_, change) -> change.after })
            }
            if (written != TaggerResult.Replied(true)) return@withContext PreparationFailure.UNVERIFIED
            val after = read(working, target, fileName) ?: return@withContext PreparationFailure.UNVERIFIED
            if (after.content?.key != expectedKey || after.properties != selected.properties) {
                return@withContext PreparationFailure.UNVERIFIED
            }
            // The tag pass keeps legacy bytes marked, so its snapshot has to match this form of the properties.
            val marked = read(working, target, fileName, markLegacy = true) ?: return@withContext PreparationFailure.UNVERIFIED
            if (marked.content?.key != expectedKey) return@withContext PreparationFailure.UNVERIFIED
            FileOutputStream(working, true).use { it.fd.sync() }
            PreparedTagCopy(
                working, before, after, selected, tagPropertiesHash(marked.properties), sha256(working), originalSha256,
                originalStat.st_dev, originalStat.st_ino,
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

    private suspend fun read(copy: File, target: TagTarget, fileName: String, markLegacy: Boolean = false): TagReport? =
        ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            (tagger.read(target, descriptor, fileName, markLegacy = markLegacy, background = true) as? TaggerResult.Replied)
                ?.value?.report?.takeIf { it.error == null }
        }
}
