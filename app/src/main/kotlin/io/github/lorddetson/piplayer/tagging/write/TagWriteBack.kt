package io.github.lorddetson.piplayer.tagging.write

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class Stamp(val size: Long, val modifiedMs: Long)

internal enum class WriteBackResult {
    NEEDS_CONSENT, INVALID_COPY, VERIFIED, FAILED, JOURNAL_CHANGED, SOURCE_CHANGED, INTERRUPTED,
}

internal class TagWriteBack(
    private val journal: Journal,
    private val stream: suspend (File) -> Unit,
    private val verify: suspend () -> Stamp?,
) {
    interface Journal {
        suspend fun streamed(): Boolean
        suspend fun verified(stamp: Stamp): Boolean
        suspend fun failed(): Boolean
    }

    suspend fun run(copy: File, size: Long, sha256: ByteArray, consent: Boolean): WriteBackResult =
        withContext(Dispatchers.IO) {
            if (!consent) return@withContext WriteBackResult.NEEDS_CONSENT
            if (!matches(copy, size, sha256)) return@withContext WriteBackResult.INVALID_COPY
            repeat(2) {
                stream(copy)
                if (!journal.streamed()) return@withContext WriteBackResult.JOURNAL_CHANGED
                val stamp = verify()
                if (stamp != null) {
                    if (!journal.verified(stamp)) return@withContext WriteBackResult.JOURNAL_CHANGED
                    copy.delete()
                    return@withContext WriteBackResult.VERIFIED
                }
            }
            if (journal.failed()) WriteBackResult.FAILED else WriteBackResult.JOURNAL_CHANGED
        }

    private fun matches(copy: File, size: Long, expectedHash: ByteArray): Boolean = try {
        copy.isFile && copy.length() == size && MessageDigest.isEqual(sha256(copy), expectedHash)
    } catch (_: IOException) {
        false
    }
}

internal fun sha256(file: File): ByteArray = file.inputStream().use(::sha256)

internal fun sha256(input: InputStream): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest()
}
