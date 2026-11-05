package io.github.lorddetson.piplayer.tagging.contentkey

import android.system.ErrnoException
import android.system.Os
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A file to read at any position; the reads of a key go through it, so that tests can count them. */
interface ByteSource {
    val size: Long

    /** Reads up to [length] bytes at [position] into [target]; returns the count, 0 or less at the end of the file. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int
}

/** Reads with pread(), which leaves the descriptor's own position alone. The caller owns [fd]. */
class FdByteSource(private val fd: FileDescriptor) : ByteSource {
    override val size: Long
        get() = try {
            Os.fstat(fd).st_size
        } catch (e: ErrnoException) {
            throw IOException(e)
        }

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int = try {
        Os.pread(fd, target, offset, length, position)
    } catch (e: ErrnoException) {
        throw IOException(e)
    }
}

/** The format tokens of key v1 (TI §3.1). They are hashed: they never change. */
enum class KeyFormat(val token: String) {
    MP3("mp3"),
    FLAC("flac"),
    MP4("mp4"),
    WAV("wav"),
    OGG_VORBIS("ogg-vorbis"),
    OPUS("opus"),
    ADTS("adts"),
    FILE("file"),
}

/** Bytes from [start] up to [end], which is excluded. */
data class ByteSpan(val start: Long, val end: Long) {
    val size get() = end - start
}

/**
 * What a key hashes, in the order [head], [middle], [tail]: [length] is the value of the length field. A payload too
 * short for windows is all in [head]. Otherwise each of the three holds 64 KiB of it, or two for Ogg, which has no
 * [middle] so that its walk stays bounded.
 */
data class Payload(
    val format: KeyFormat,
    val length: Long,
    val head: List<ByteSpan>,
    val tail: List<ByteSpan>,
    val middle: List<ByteSpan> = emptyList(),
) {

    companion object {
        const val CHUNK = 65_536L

        /** Under this size a payload is hashed whole. */
        const val WHOLE_BELOW = 3 * CHUNK

        /** The same for Ogg, which takes two windows. */
        const val OGG_WHOLE_BELOW = 2 * CHUNK

        /** The payload made of [spans] in file order, its length field being their size. */
        fun of(format: KeyFormat, spans: List<ByteSpan>): Payload {
            val length = spans.sumOf { it.size }
            if (length < WHOLE_BELOW) return Payload(format, length, spans, emptyList())
            return Payload(
                format,
                length,
                head = slice(spans, 0, CHUNK),
                tail = slice(spans, length - CHUNK, CHUNK),
                middle = slice(spans, (length - CHUNK) / 2, CHUNK),
            )
        }

        /** The first [count] bytes of [spans]. */
        fun firstBytes(spans: List<ByteSpan>, count: Long): List<ByteSpan> = slice(spans, 0, count)

        /** The last [count] bytes of [spans]. */
        fun lastBytes(spans: List<ByteSpan>, count: Long): List<ByteSpan> =
            slice(spans, maxOf(spans.sumOf { it.size } - count, 0), count)

        /** The [count] bytes of the joined [spans] from [offset] into them. */
        private fun slice(spans: List<ByteSpan>, offset: Long, count: Long): List<ByteSpan> {
            val taken = mutableListOf<ByteSpan>()
            var skip = offset
            var left = count
            for (span in spans) {
                if (left <= 0) break
                if (skip >= span.size) {
                    skip -= span.size
                    continue
                }
                val start = span.start + skip
                val end = minOf(span.end, start + left)
                taken += ByteSpan(start, end)
                left -= end - start
                skip = 0
            }
            return taken
        }
    }
}

/** Content key v1 (TI §3.1): its layout never changes, and golden tests freeze it. */
object ContentKey {

    private const val VERSION = 1
    private const val BUFFER_BYTES = 16_384

    /** The 32 bytes of the key of [payload]; throws IOException when [source] ends before the payload does. */
    fun of(source: ByteSource, payload: Payload): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(VERSION.toByte())
        digest.update(payload.format.token.toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(payload.length).array())
        val buffer = ByteArray(BUFFER_BYTES)
        for (span in payload.head + payload.middle + payload.tail) {
            var position = span.start
            while (position < span.end) {
                val count = source.read(position, buffer, 0, minOf(span.end - position, BUFFER_BYTES.toLong()).toInt())
                if (count <= 0) throw IOException("The file ended at $position, before the end of its audio")
                digest.update(buffer, 0, count)
                position += count
            }
        }
        return digest.digest()
    }

    fun hex(key: ByteArray): String = key.joinToString("") { "%02x".format(it) }
}
