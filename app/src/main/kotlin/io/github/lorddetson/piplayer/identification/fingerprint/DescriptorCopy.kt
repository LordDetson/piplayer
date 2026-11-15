package io.github.lorddetson.piplayer.identification.fingerprint

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.github.lorddetson.piplayer.identification.fingerprint.DecodingException.CopyRefused
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** A source of bytes that waits a bounded time for each read. */
fun interface TimedReader {

    /** Reads into [buffer]: the number of bytes read, 0 when none came within [timeoutMillis], -1 at the end. */
    fun read(buffer: ByteArray, timeoutMillis: Long): Int
}

/**
 * Makes a descriptor readable at random positions, as Media3's extractor needs (TI §3.2): a regular file is read as it
 * is, anything else (a pipe from a document provider) is first copied into [directory]. The copy is bounded: at most
 * [maxBytes], never leaving less than [reserveBytes] free on [directory]'s storage (looked at again every
 * [spaceCheckBytes], since the other lane or another app may write too), and [maxMillis] for the whole copy. It waits
 * for data only until that deadline, so a writer that stalls without closing its end cannot hang it. Past a bound,
 * after a failed read or when the caller cancels, the partial copy is deleted. A copy is deleted as soon as it is open
 * for reading, so even a process that dies while decoding leaves nothing; a copy that a process left behind in the
 * middle of copying is deleted by a later copy once it is [STALE_MILLIS] old.
 */
class DescriptorCopy(
    val directory: File,
    private val maxBytes: Long = MAX_BYTES,
    private val maxMillis: Long = MAX_MILLIS,
    private val reserveBytes: Long = RESERVE_BYTES,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val freeSpace: (File) -> Long = File::getUsableSpace,
    private val spaceCheckBytes: Long = SPACE_CHECK_BYTES,
) {

    /**
     * Runs [block] with a descriptor of [fd]'s bytes that can be read at random positions: [fd] itself when it is a
     * regular file, otherwise a copy. The caller keeps owning [fd]; the copy's descriptor is closed when [block] ends.
     * Throws [CopyRefused] past a bound, [DecodingException.Failed] when the copy cannot be read or written, and
     * [CancellationException] once [cancelled] says so.
     */
    fun <T> readable(fd: FileDescriptor, cancelled: () -> Boolean = { false }, block: (FileDescriptor) -> T): T {
        if (isRegularFile(fd)) return block(fd)
        val copy = try {
            copy(PipeReader(fd), cancelled)
        } catch (e: DecodingException) {
            throw e
        } catch (e: IOException) {
            throw DecodingException.Failed("The file could not be copied: ${e.message}", e)
        }
        val descriptor = try {
            ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw DecodingException.Failed("The copy could not be opened: ${e.message}", e)
        } finally {
            // Unlinked while open, it lives on until the descriptor is closed.
            copy.delete()
        }
        return descriptor.use { block(it.fileDescriptor) }
    }

    /** Copies everything [reader] gives into a new file of [directory], within the bounds; see [readable]. */
    fun copy(reader: TimedReader, cancelled: () -> Boolean = { false }): File {
        sweep()
        val free = freeSpace(directory.existingAncestor()) - reserveBytes
        if (free <= 0) throw CopyRefused(CopyRefused.Reason.NO_SPACE)
        val limit = minOf(maxBytes, free)
        val pastLimit = if (free < maxBytes) CopyRefused.Reason.NO_SPACE else CopyRefused.Reason.TOO_LARGE
        directory.mkdirs()
        val file = File.createTempFile(PREFIX, null, directory)
        try {
            val deadline = clock() + maxMillis
            FileOutputStream(file).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                var sinceCheck = 0L
                while (true) {
                    if (cancelled()) throw CancellationException("The copy was cancelled")
                    val left = deadline - clock()
                    if (left <= 0) throw CopyRefused(CopyRefused.Reason.TOO_SLOW)
                    val count = reader.read(buffer, minOf(left, POLL_MILLIS))
                    if (count < 0) break
                    total += count
                    if (total > limit) throw CopyRefused(pastLimit)
                    out.write(buffer, 0, count)
                    sinceCheck += count
                    if (sinceCheck >= spaceCheckBytes) {
                        sinceCheck = 0
                        if (freeSpace(directory) < reserveBytes) throw CopyRefused(CopyRefused.Reason.NO_SPACE)
                    }
                }
            }
            return file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    /** Deletes the copies that a process left behind when it died, once they are [STALE_MILLIS] old. */
    private fun sweep() {
        val before = wallClock() - STALE_MILLIS
        directory.listFiles()?.forEach { file -> if (file.lastModified() < before) file.delete() }
    }

    /** The free space of a directory not made yet is that of its nearest existing parent. */
    private fun File.existingAncestor(): File =
        generateSequence(this) { it.parentFile }.firstOrNull { it.exists() } ?: this

    /**
     * Reads a descriptor that may not be ready (a pipe or a socket), waiting for data with `poll` so that a read never
     * blocks past its timeout.
     */
    private class PipeReader(private val fd: FileDescriptor) : TimedReader {
        private val poll = StructPollfd().apply {
            this.fd = this@PipeReader.fd
            events = OsConstants.POLLIN.toShort()
        }

        override fun read(buffer: ByteArray, timeoutMillis: Long): Int = try {
            poll.revents = 0
            if (Os.poll(arrayOf(poll), timeoutMillis.toInt()) == 0) {
                0
            } else {
                // Ready: data, the end (0 from read) or an error, which read reports.
                Os.read(fd, buffer, 0, buffer.size).let { if (it == 0) -1 else it }
            }
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) 0 else throw IOException(e.message, e)
        }
    }

    companion object {
        /** The directory of the app's copies, in its cache. */
        const val DIRECTORY = "fingerprint-copies"

        /** Large lossless files fit: about 48 minutes of 96 kHz/24-bit stereo FLAC. */
        const val MAX_BYTES = 1L shl 30
        const val MAX_MILLIS = 120_000L
        const val RESERVE_BYTES = 256L shl 20
        const val STALE_MILLIS = 3_600_000L

        /** How often a running copy looks at the free space again, which another copy or app may have taken. */
        const val SPACE_CHECK_BYTES = 16L shl 20

        /** The longest a read waits, so that a cancelled caller is noticed soon. */
        const val POLL_MILLIS = 100L

        private const val BUFFER_SIZE = 64 * 1024
        private const val PREFIX = "copy"

        fun of(context: Context) = DescriptorCopy(File(context.cacheDir, DIRECTORY))

        /** Whether [fd] is a regular file; a descriptor that cannot even be examined is left to the extractor. */
        fun isRegularFile(fd: FileDescriptor): Boolean = try {
            OsConstants.S_ISREG(Os.fstat(fd).st_mode)
        } catch (_: ErrnoException) {
            true
        }
    }
}
