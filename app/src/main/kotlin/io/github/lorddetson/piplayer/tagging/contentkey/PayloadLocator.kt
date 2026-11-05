package io.github.lorddetson.piplayer.tagging.contentkey

import io.github.lorddetson.piplayer.nativelib.taglib.MpegLayout

/** The container of a file, told by its content: a file's name never changes its key. */
enum class AudioContainer { MPEG, FLAC, MP4, WAV, OGG, OTHER }

/** What the final page of an Ogg stream says about its length. */
data class OggLength(val finalGranule: Long, val sampleRate: Int, val preSkip: Int)

/**
 * Where a file's audio is. [declaredBeyondEnd] means a WAV `data` chunk or an MP4 `mdat` atom says it is longer than
 * the bytes left in the file.
 */
data class Located(val payload: Payload, val declaredBeyondEnd: Boolean = false, val ogg: OggLength? = null)

/** Finds the audio payload of a file (TI §3.1), from TagLib's offsets or a walk over top-level structures. */
object PayloadLocator {

    /** A walk gives up after this many structures: a file made of tiny atoms or pages must not take minutes. */
    internal const val MAX_STRUCTURES = 1 shl 20

    /** The container [source] names by its first bytes; [AudioContainer.OTHER] may still hold MPEG frames. */
    fun sniff(source: ByteSource): AudioContainer {
        val start = source.bytesAt(0, SNIFF_BYTES) ?: return AudioContainer.OTHER
        return when {
            start.startsWith(OGG_CAPTURE) -> AudioContainer.OGG
            start.startsWith(FTYP, offset = 4) -> AudioContainer.MP4
            start.startsWith(RIFF) && start.startsWith(WAVE, offset = 8) -> AudioContainer.WAV
            source.bytesAt(id3v2End(source), FLAC_MARKER.size)?.contentEquals(FLAC_MARKER) == true -> AudioContainer.FLAC
            else -> AudioContainer.OTHER
        }
    }

    /**
     * [container] comes from [sniff] and [mpeg] from TagLib; any format it did not name, or a walk that fails, takes
     * [KeyFormat.FILE]. Throws IOException when reading fails.
     */
    fun locate(source: ByteSource, container: AudioContainer, mpeg: MpegLayout?): Located {
        val located = when (container) {
            AudioContainer.MPEG -> mpeg?.let(::mpegPayload)?.let { Located(it) }
            AudioContainer.FLAC -> flac(source)
            AudioContainer.MP4 -> mp4(source)
            AudioContainer.WAV -> wav(source)
            AudioContainer.OGG -> OggWalk.locate(source)
            AudioContainer.OTHER -> null
        }
        return located ?: Located(Payload.of(KeyFormat.FILE, listOf(ByteSpan(0, source.size))))
    }

    private fun mpegPayload(layout: MpegLayout): Payload? {
        if (layout.firstFrame < 0) return null
        val span = if (layout.adts) {
            ByteSpan(layout.firstFrame, layout.audioEnd)
        } else {
            if (layout.lastFrame < layout.firstFrame) return null
            val start = layout.firstFrame + if (layout.serviceFrame) layout.firstFrameLength else 0
            ByteSpan(start, minOf(layout.lastFrame + layout.lastFrameLength, layout.audioEnd))
        }
        if (span.size <= 0) return null
        return Payload.of(if (layout.adts) KeyFormat.ADTS else KeyFormat.MP3, listOf(span))
    }

    private fun flac(source: ByteSource): Located? {
        var position = id3v2End(source)
        if (source.bytesAt(position, FLAC_MARKER.size)?.contentEquals(FLAC_MARKER) != true) return null
        position += FLAC_MARKER.size
        repeat(MAX_STRUCTURES) {
            val header = source.bytesAt(position, FLAC_BLOCK_HEADER_BYTES) ?: return null
            position += FLAC_BLOCK_HEADER_BYTES + bigEndian(header, 1, 3)
            if (header[0].toInt() and FLAC_LAST_BLOCK != 0) {
                val end = trailingTagsStart(source)
                if (position >= end) return null
                return Located(Payload.of(KeyFormat.FLAC, listOf(ByteSpan(position, end))))
            }
        }
        return null
    }

    /** Where a leading ID3v2 tag ends, 0 without one. */
    private fun id3v2End(source: ByteSource): Long {
        val header = source.bytesAt(0, ID3V2_HEADER_BYTES) ?: return 0
        if (!header.startsWith(ID3V2_MARKER)) return 0
        var size = 0L
        for (index in 6..9) size = size shl 7 or (header[index].toLong() and 0x7F)
        val footer = if (header[5].toInt() and ID3V2_FOOTER_FLAG != 0) ID3V2_HEADER_BYTES else 0
        return ID3V2_HEADER_BYTES + size + footer
    }

    /** Where the ID3v1 and APE tags at the end of the file start: its size without them. */
    private fun trailingTagsStart(source: ByteSource): Long {
        var end = source.size
        if (end >= ID3V1_BYTES && source.bytesAt(end - ID3V1_BYTES, ID3V1_MARKER.size)?.contentEquals(ID3V1_MARKER) == true) {
            // The end of an APE tag of 131 bytes can read TAG in the place of ID3v1.
            val apeAt = end - ID3V1_BYTES - TAG_IN_APE_MARKER
            val ape = if (apeAt >= 0) source.bytesAt(apeAt, APE_MARKER.size) else null
            if (ape?.contentEquals(APE_MARKER) != true) end -= ID3V1_BYTES
        }
        if (end >= APE_FOOTER_BYTES) {
            val footer = source.bytesAt(end - APE_FOOTER_BYTES, APE_FOOTER_BYTES)
            if (footer != null && footer.startsWith(APE_MARKER)) {
                val size = littleEndian(footer, 12, 4)
                val hasHeader = littleEndian(footer, 20, 4) and APE_HAS_HEADER != 0L
                end -= size + if (hasHeader) APE_FOOTER_BYTES else 0
            }
        }
        return end
    }

    private fun mp4(source: ByteSource): Located? {
        val spans = mutableListOf<ByteSpan>()
        var cut = false
        var position = 0L
        val fileSize = source.size
        var structures = 0
        while (position + ATOM_HEADER_BYTES <= fileSize) {
            if (++structures > MAX_STRUCTURES) return null
            val header = source.bytesAt(position, ATOM_HEADER_BYTES) ?: break
            var size = bigEndian(header, 0, 4)
            var headerSize = ATOM_HEADER_BYTES
            if (size == 1L) {
                val large = source.bytesAt(position + ATOM_HEADER_BYTES, Long.SIZE_BYTES) ?: break
                size = bigEndian(large, 0, Long.SIZE_BYTES)
                headerSize += Long.SIZE_BYTES
            } else if (size == 0L) {
                size = fileSize - position
            }
            // This also stops at a 64-bit size over the signed range.
            if (size < headerSize) break
            val end = if (size > fileSize - position) fileSize else position + size
            if (header.startsWith(MDAT, offset = 4)) {
                if (size > fileSize - position) cut = true
                if (end > position + headerSize) spans += ByteSpan(position + headerSize, end)
            }
            position = end
        }
        if (spans.isEmpty()) return null
        return Located(Payload.of(KeyFormat.MP4, spans), declaredBeyondEnd = cut)
    }

    private fun wav(source: ByteSource): Located? {
        val header = source.bytesAt(0, RIFF_HEADER_BYTES) ?: return null
        if (!header.startsWith(RIFF) || !header.startsWith(WAVE, offset = 8)) return null
        val fileSize = source.size
        var position = RIFF_HEADER_BYTES.toLong()
        repeat(MAX_STRUCTURES) {
            val chunk = source.bytesAt(position, CHUNK_HEADER_BYTES) ?: return null
            val size = littleEndian(chunk, 4, 4)
            val start = position + CHUNK_HEADER_BYTES
            if (chunk.startsWith(DATA)) {
                val left = fileSize - start
                if (size == 0L || left <= 0) return null
                return Located(
                    Payload.of(KeyFormat.WAV, listOf(ByteSpan(start, start + minOf(size, left)))),
                    declaredBeyondEnd = size > left,
                )
            }
            // Chunks of an odd size are padded to an even one.
            position = start + size + (size and 1)
        }
        return null
    }

    private const val SNIFF_BYTES = 12
    private val OGG_CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
    private val FLAC_MARKER = "fLaC".toByteArray(Charsets.US_ASCII)
    private const val FLAC_BLOCK_HEADER_BYTES = 4
    private const val FLAC_LAST_BLOCK = 0x80
    private const val ID3V2_HEADER_BYTES = 10
    private val ID3V2_MARKER = "ID3".toByteArray(Charsets.US_ASCII)
    private const val ID3V2_FOOTER_FLAG = 0x10
    private const val ID3V1_BYTES = 128
    private val ID3V1_MARKER = "TAG".toByteArray(Charsets.US_ASCII)
    private const val TAG_IN_APE_MARKER = 3
    private const val APE_FOOTER_BYTES = 32
    private val APE_MARKER = "APETAGEX".toByteArray(Charsets.US_ASCII)
    private const val APE_HAS_HEADER = 0x80000000L
    private const val ATOM_HEADER_BYTES = 8
    private val MDAT = "mdat".toByteArray(Charsets.US_ASCII)
    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
    private val DATA = "data".toByteArray(Charsets.US_ASCII)
}

/** The [count] bytes at [position], null when the file ends before them. */
internal fun ByteSource.bytesAt(position: Long, count: Int): ByteArray? {
    val bytes = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(position + filled, bytes, filled, count - filled)
        if (read <= 0) return null
        filled += read
    }
    return bytes
}

internal fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean {
    if (size < offset + prefix.size) return false
    return prefix.indices.all { this[offset + it] == prefix[it] }
}

internal fun bigEndian(bytes: ByteArray, offset: Int, count: Int): Long {
    var value = 0L
    for (index in offset until offset + count) value = value shl 8 or (bytes[index].toLong() and 0xFF)
    return value
}

internal fun littleEndian(bytes: ByteArray, offset: Int, count: Int): Long {
    var value = 0L
    for (index in offset + count - 1 downTo offset) value = value shl 8 or (bytes[index].toLong() and 0xFF)
    return value
}
