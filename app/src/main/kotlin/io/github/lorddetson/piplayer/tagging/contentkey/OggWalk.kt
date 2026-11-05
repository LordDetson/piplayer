package io.github.lorddetson.piplayer.tagging.contentkey

/**
 * The bounded page walk of decision D10 (TI §3.1). What it finds does not depend on how far it read: the key takes the
 * first and last 64 KiB of the page bodies after the last header page, and the final granule position.
 */
internal object OggWalk {

    private const val PAGE_HEADER_BYTES = 27
    private const val BEGINNING_OF_STREAM = 2
    private const val NO_GRANULE = -1L
    private val CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)
    private val VORBIS = byteArrayOf(1) + "vorbis".toByteArray(Charsets.US_ASCII)
    private val OPUS = "OpusHead".toByteArray(Charsets.US_ASCII)
    private const val IDENTIFICATION_BYTES = 19
    private const val VORBIS_RATE_AT = 12
    private const val OPUS_PRE_SKIP_AT = 10
    private const val OPUS_RATE = 48_000

    /** The first read of the tail; pages usually waste less than this over the 64 KiB. */
    private const val FIRST_TAIL_BYTES = Payload.CHUNK + 16_384

    /** A tail needing more than this holds something other than pages, or pages almost without bodies. */
    private const val MAX_TAIL_BYTES = 1L shl 20

    private class Page(val flags: Int, val granule: Long, val serial: Long, val body: ByteSpan, val completedPackets: Int) {
        val beginsStream get() = flags and BEGINNING_OF_STREAM != 0
    }

    private class Stream(val format: KeyFormat, val headerPackets: Int, val sampleRate: Int, val preSkip: Int)

    /** The audio of a single Vorbis or Opus stream; null for anything else, which then takes the whole-file token. */
    fun locate(source: ByteSource): Located? {
        val first = page(source, 0) ?: return null
        if (!first.beginsStream) return null
        val stream = stream(source, first) ?: return null
        val audioStart = audioStart(source, first, stream.headerPackets) ?: return null
        val forward = forward(source, audioStart, first.serial) ?: return null
        val total = forward.spans.sumOf { it.size }
        val payload = if (total < Payload.OGG_WHOLE_BELOW) {
            val granule = forward.granule ?: return null
            if (total == 0L) return null
            Payload(stream.format, granule, forward.spans, emptyList())
        } else {
            val tail = tail(source, audioStart, first.serial) ?: return null
            Payload(
                stream.format,
                tail.granule,
                Payload.firstBytes(forward.spans, Payload.CHUNK),
                Payload.lastBytes(tail.spans, Payload.CHUNK),
            )
        }
        return Located(payload, ogg = OggLength(payload.length, stream.sampleRate, stream.preSkip))
    }

    private fun stream(source: ByteSource, first: Page): Stream? {
        val count = minOf(IDENTIFICATION_BYTES.toLong(), first.body.size).toInt()
        val packet = source.bytesAt(first.body.start, count) ?: return null
        return when {
            packet.startsWith(VORBIS) && packet.size >= VORBIS_RATE_AT + Int.SIZE_BYTES ->
                Stream(KeyFormat.OGG_VORBIS, 3, littleEndian(packet, VORBIS_RATE_AT, Int.SIZE_BYTES).toInt(), 0)
            packet.startsWith(OPUS) && packet.size >= OPUS_PRE_SKIP_AT + Short.SIZE_BYTES ->
                Stream(KeyFormat.OPUS, 2, OPUS_RATE, littleEndian(packet, OPUS_PRE_SKIP_AT, Short.SIZE_BYTES).toInt())
            else -> null
        }
    }

    /** Adds up the segment tables of the header pages, without reading their bodies. */
    private fun audioStart(source: ByteSource, first: Page, headerPackets: Int): Long? {
        var page = first
        var packets = page.completedPackets
        var structures = 0
        while (packets < headerPackets) {
            if (++structures > PayloadLocator.MAX_STRUCTURES) return null
            page = page(source, page.body.end) ?: return null
            if (page.beginsStream || page.serial != first.serial) return null
            packets += page.completedPackets
        }
        return page.body.end
    }

    private class Walked(val spans: List<ByteSpan>, val granule: Long?)

    /**
     * Page headers from [audioStart], until the bodies reach 128 KiB, so that a short payload is told exactly and only
     * page headers are read. Stops at the first thing that is not a whole page. Null for another logical stream.
     */
    private fun forward(source: ByteSource, audioStart: Long, serial: Long): Walked? {
        val spans = mutableListOf<ByteSpan>()
        var total = 0L
        var granule: Long? = null
        var position = audioStart
        var structures = 0
        val size = source.size
        while (total < Payload.OGG_WHOLE_BELOW) {
            if (++structures > PayloadLocator.MAX_STRUCTURES) return null
            val page = page(source, position) ?: break
            if (page.body.end > size) break
            if (page.beginsStream || page.serial != serial) return null
            if (page.body.size > 0) spans += page.body
            total += page.body.size
            if (page.granule != NO_GRANULE) granule = page.granule
            position = page.body.end
        }
        return Walked(spans, granule)
    }

    private class Tail(val spans: List<ByteSpan>, val granule: Long)

    /**
     * The pages at the end of the file: page starts are told by their CRC, and anything else is skipped. Reads further
     * back until the bodies reach 64 KiB. Null when no such pages are found or another logical stream shows up.
     */
    private fun tail(source: ByteSource, audioStart: Long, serial: Long): Tail? {
        val size = source.size
        val all = size - audioStart
        var window = minOf(FIRST_TAIL_BYTES, all)
        while (true) {
            val start = size - window
            val bytes = source.bytesAt(start, window.toInt()) ?: return null
            val pages = validPages(bytes, start)
            if (pages.any { it.beginsStream || it.serial != serial }) return null
            val granule = pages.lastOrNull { it.granule != NO_GRANULE }?.granule
            val spans = pages.map { it.body }.filter { it.size > 0 }
            if (granule != null && spans.sumOf { it.size } >= Payload.CHUNK) return Tail(spans, granule)
            if (window >= all || window >= MAX_TAIL_BYTES) return null
            window = minOf(window * 2, all, MAX_TAIL_BYTES)
        }
    }

    /** The pages in [bytes], which sit at [offset] in the file, whose CRC is right; bytes between them are skipped. */
    private fun validPages(bytes: ByteArray, offset: Long): List<Page> {
        val pages = mutableListOf<Page>()
        var at = 0
        while (at + PAGE_HEADER_BYTES <= bytes.size) {
            val page = if (bytes.startsWith(CAPTURE, at)) pageIn(bytes, at, offset) else null
            if (page == null) {
                at++
            } else {
                pages += page
                at = (page.body.end - offset).toInt()
            }
        }
        return pages
    }

    private fun pageIn(bytes: ByteArray, at: Int, offset: Long): Page? {
        val segments = bytes[at + PAGE_HEADER_BYTES - 1].toInt() and 0xFF
        val headerEnd = at + PAGE_HEADER_BYTES + segments
        if (headerEnd > bytes.size) return null
        val page = parse(bytes.copyOfRange(at, headerEnd), offset + at) ?: return null
        val end = page.body.end - offset
        if (end > bytes.size || !crcMatches(bytes, at, end.toInt())) return null
        return page
    }

    private fun page(source: ByteSource, position: Long): Page? {
        val fixed = source.bytesAt(position, PAGE_HEADER_BYTES) ?: return null
        if (!fixed.startsWith(CAPTURE)) return null
        val lacing = source.bytesAt(position + PAGE_HEADER_BYTES, fixed[PAGE_HEADER_BYTES - 1].toInt() and 0xFF) ?: return null
        return parse(fixed + lacing, position)
    }

    /** [header] is a page's whole header with its segment table, found at [position] in the file. */
    private fun parse(header: ByteArray, position: Long): Page? {
        if (header[4].toInt() != 0) return null
        var bodySize = 0L
        var completed = 0
        for (index in PAGE_HEADER_BYTES until header.size) {
            val value = header[index].toInt() and 0xFF
            bodySize += value
            if (value < 255) completed++
        }
        val bodyStart = position + header.size
        return Page(
            flags = header[5].toInt() and 0xFF,
            granule = littleEndian(header, 6, Long.SIZE_BYTES),
            serial = littleEndian(header, 14, Int.SIZE_BYTES),
            body = ByteSpan(bodyStart, bodyStart + bodySize),
            completedPackets = completed,
        )
    }

    private val CRC_TABLE = IntArray(256) { index ->
        var value = index shl 24
        repeat(Byte.SIZE_BITS) { value = if (value < 0) (value shl 1) xor 0x04C11DB7 else value shl 1 }
        value
    }

    private fun crcMatches(bytes: ByteArray, start: Int, end: Int): Boolean {
        var crc = 0
        for (at in start until end) {
            // The CRC field counts as zero.
            val byte = if (at - start in 22..25) 0 else bytes[at].toInt() and 0xFF
            crc = (crc shl Byte.SIZE_BITS) xor CRC_TABLE[(crc ushr 24) xor byte]
        }
        return crc == littleEndian(bytes, start + 22, Int.SIZE_BYTES).toInt()
    }
}
