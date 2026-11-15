package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.library.details.PictureSize

/** The image formats a cover may come in, told apart by their first bytes. */
enum class ImageFormat(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    GIF("image/gif"),
    WEBP("image/webp"),
}

/** Reads what a cover is from its first bytes, without decoding it, so it runs in JVM tests too. */
object ImageHeader {

    /** The format of [bytes], or null when they are no image of [ImageFormat]. */
    fun format(bytes: ByteArray): ImageFormat? = when {
        bytes.startsWith(JPEG_START) -> ImageFormat.JPEG
        bytes.startsWith(PNG_SIGNATURE) -> ImageFormat.PNG
        bytes.startsWith(GIF87) || bytes.startsWith(GIF89) -> ImageFormat.GIF
        bytes.startsWith(RIFF) && bytes.size >= 12 && bytes.copyOfRange(8, 12).contentEquals(WEBP) -> ImageFormat.WEBP
        else -> null
    }

    /** The size in pixels from a JPEG, PNG or GIF header; null for other data or a header that is cut off. */
    fun size(bytes: ByteArray): PictureSize? = when (format(bytes)) {
        ImageFormat.JPEG -> jpegSize(bytes)
        // The IHDR chunk comes first: length and type (8 bytes) after the signature, then width and height.
        ImageFormat.PNG -> if (bytes.size >= 24) positive(bytes.int32(16), bytes.int32(20)) else null
        // The logical screen descriptor: width and height, little-endian, after the six signature bytes.
        ImageFormat.GIF -> if (bytes.size >= 10) positive(bytes.uint16le(6), bytes.uint16le(8)) else null
        ImageFormat.WEBP, null -> null
    }

    /**
     * Walks the JPEG segments to the first start-of-frame (SOF0 to SOF15 without DHT, JPG and DAC), whose height and
     * width follow its length and sample precision. Fill bytes before a marker and markers without a length are skipped.
     */
    private fun jpegSize(bytes: ByteArray): PictureSize? {
        var at = 2
        while (at + 1 < bytes.size) {
            if (bytes.u8(at) != 0xFF) return null
            val marker = bytes.u8(at + 1)
            when {
                marker == 0xFF -> at++
                marker == 0x01 || marker in 0xD0..0xD7 -> at += 2
                marker == 0xD9 || marker == 0xDA -> return null
                else -> {
                    if (at + 3 >= bytes.size) return null
                    val length = bytes.uint16be(at + 2)
                    if (length < 2) return null
                    if (marker in 0xC0..0xCF && marker !in NOT_FRAMES) {
                        if (at + 8 >= bytes.size) return null
                        return positive(bytes.uint16be(at + 7), bytes.uint16be(at + 5))
                    }
                    at += 2 + length
                }
            }
        }
        return null
    }

    private fun positive(width: Long, height: Long): PictureSize? =
        if (width in 1..Int.MAX_VALUE && height in 1..Int.MAX_VALUE) PictureSize(width.toInt(), height.toInt()) else null

    private fun positive(width: Int, height: Int): PictureSize? = positive(width.toLong(), height.toLong())

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

    private fun ByteArray.uint16be(at: Int): Int = u8(at) shl 8 or u8(at + 1)

    private fun ByteArray.uint16le(at: Int): Int = u8(at + 1) shl 8 or u8(at)

    private fun ByteArray.int32(at: Int): Long =
        (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()

    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val JPEG_START = bytesOf(0xFF, 0xD8, 0xFF)
    private val PNG_SIGNATURE = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val GIF87 = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89 = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    /** DHT, JPG and DAC share the SOF range but are no frame headers. */
    private val NOT_FRAMES = setOf(0xC4, 0xC8, 0xCC)
}
