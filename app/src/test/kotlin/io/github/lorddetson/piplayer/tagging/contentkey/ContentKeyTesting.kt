package io.github.lorddetson.piplayer.tagging.contentkey

import io.github.lorddetson.piplayer.nativelib.taglib.MpegLayout
import java.io.ByteArrayOutputStream

/** A file in memory that counts what is read from it. */
class ArraySource(private val bytes: ByteArray) : ByteSource {
    var bytesRead = 0L
        private set

    override val size get() = bytes.size.toLong()

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(target, offset, position.toInt(), position.toInt() + count)
        bytesRead += count
        return count
    }
}

/** Bytes that differ along their whole length, the pattern of the reference keys. */
fun pattern(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 256).toByte() }

/** Like [pattern], but another stream of bytes for another [seed]. */
fun seeded(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 31 + it / 256) xor (seed * 73)).toByte() }

fun keyOf(
    bytes: ByteArray,
    container: AudioContainer,
    mpeg: MpegLayout? = null,
): Pair<String, Located> {
    val source = ArraySource(bytes)
    val located = PayloadLocator.locate(source, container, mpeg)
    return ContentKey.hex(ContentKey.of(source, located.payload)) to located
}

fun bytesOf(vararg parts: Any): ByteArray {
    val out = ByteArrayOutputStream()
    for (part in parts) {
        when (part) {
            is ByteArray -> out.write(part)
            is String -> out.write(part.toByteArray(Charsets.ISO_8859_1))
            is Int -> out.write(part)
            else -> error("unsupported part $part")
        }
    }
    return out.toByteArray()
}

fun bigEndian(value: Long, bytes: Int) = ByteArray(bytes) { (value shr (8 * (bytes - 1 - it))).toByte() }

fun littleEndian(value: Long, bytes: Int) = ByteArray(bytes) { (value shr (8 * it)).toByte() }

/** A tag of the given size at the end of a file: ID3v1. */
fun id3v1Tag() = bytesOf("TAG", ByteArray(125) { 'x'.code.toByte() })

/** An APEv2 tag with a header and a footer around [items] bytes. */
fun apeTag(items: Int = 40): ByteArray {
    val size = (items + 32).toLong()
    fun block(flags: Long) = bytesOf(
        "APETAGEX", littleEndian(2000, 4), littleEndian(size, 4), littleEndian(1, 4), littleEndian(flags, 4), ByteArray(8),
    )
    return bytesOf(block(0xA0000000L), ByteArray(items) { 'a'.code.toByte() }, block(0x80000000L))
}

fun id3v2Tag(size: Int): ByteArray {
    val encoded = byteArrayOf(
        (size shr 21 and 0x7f).toByte(), (size shr 14 and 0x7f).toByte(), (size shr 7 and 0x7f).toByte(), (size and 0x7f).toByte(),
    )
    return bytesOf("ID3", byteArrayOf(3, 0, 0), encoded, ByteArray(size))
}

/** Builds Ogg pages for the walks; every page gets a correct CRC unless told otherwise. */
object Ogg {
    const val BOS = 2
    const val SERIAL = 77L

    fun crc(page: ByteArray): Long {
        var crc = 0L
        for (byte in page) {
            crc = crc xor ((byte.toLong() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and 0x80000000L != 0L) ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL else (crc shl 1) and 0xFFFFFFFFL }
        }
        return crc
    }

    /** One page whose lacing values are [lacing] (their sum is the size of [body]). */
    fun page(
        body: ByteArray,
        lacing: List<Int>,
        granule: Long = 0,
        flags: Int = 0,
        sequence: Long = 0,
        serial: Long = SERIAL,
        goodCrc: Boolean = true,
    ): ByteArray {
        require(lacing.sum() == body.size)
        val page = bytesOf(
            "OggS", 0, flags, littleEndian(granule, 8), littleEndian(serial, 4), littleEndian(sequence, 4), ByteArray(4),
            lacing.size, ByteArray(lacing.size) { lacing[it].toByte() }, body,
        )
        val crc = littleEndian(crc(page) + if (goodCrc) 0 else 1, 4)
        crc.copyInto(page, 22)
        return page
    }

    /** The lacing values of one packet of [size] bytes that ends in this page. */
    fun lacingOf(size: Int): List<Int> = List(size / 255) { 255 } + (size % 255)

    /** Pages that hold [packets] in order, at most [perPage] lacing values each. */
    fun pages(packets: List<ByteArray>, perPage: Int, firstFlags: Int = 0, serial: Long = SERIAL): List<ByteArray> {
        val lacing = packets.flatMap { lacingOf(it.size) }
        val body = packets.fold(ByteArray(0)) { all, packet -> all + packet }
        val pages = mutableListOf<ByteArray>()
        var at = 0
        var flags = firstFlags
        for (values in lacing.chunked(perPage)) {
            val size = values.sum()
            pages += page(body.copyOfRange(at, at + size), values, flags = flags, serial = serial)
            at += size
            flags = if (values.last() == 255) 1 else 0
        }
        return pages
    }

    val vorbisIdentification = bytesOf(1, "vorbis", littleEndian(0, 4), 2, littleEndian(44_100, 4), ByteArray(13))
    val opusHead = bytesOf("OpusHead", 1, 2, littleEndian(312, 2), littleEndian(48_000, 4), ByteArray(3))

    fun comment(size: Int) = bytesOf(3, "vorbis", ByteArray(size) { 'c'.code.toByte() })

    fun opusTags(size: Int) = bytesOf("OpusTags", ByteArray(size) { 'c'.code.toByte() })

    val setup = bytesOf(5, "vorbis", ByteArray(500) { 's'.code.toByte() })

    /** The header pages of a Vorbis stream whose comment packet has [comment] bytes. */
    fun vorbisHeaders(comment: Int = 100, perPage: Int = 255): ByteArray {
        val first = page(vorbisIdentification, lacingOf(vorbisIdentification.size), flags = BOS)
        val rest = pages(listOf(comment(comment), setup), perPage)
        return rest.fold(first) { all, next -> all + next }
    }

    fun opusHeaders(comment: Int = 100, perPage: Int = 255): ByteArray {
        val first = page(opusHead, lacingOf(opusHead.size), flags = BOS)
        return pages(listOf(opusTags(comment)), perPage).fold(first) { all, next -> all + next }
    }

    /** Audio pages over [audio], each holding [pageBody] bytes, the granule rising to [finalGranule] on the last. */
    fun audioPages(audio: ByteArray, pageBody: Int = 4_000, finalGranule: Long = 960_000, serial: Long = SERIAL): ByteArray {
        val out = ByteArrayOutputStream()
        val chunks = audio.toList().chunked(pageBody)
        chunks.forEachIndexed { index, chunk ->
            val granule = finalGranule * (index + 1) / chunks.size
            out.write(page(chunk.toByteArray(), lacingOf(chunk.size), granule = granule, serial = serial))
        }
        return out.toByteArray()
    }
}
