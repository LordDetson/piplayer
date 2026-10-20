package io.github.lorddetson.piplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.FlacStreamMetadata

/** FLAC frame sizes for the decoder's input buffers (docs/playback.md §1.2) and the stream's bit depth (§3.5). */
@OptIn(UnstableApi::class)
object FlacFrames {

    /** "fLaC", the 4-byte metadata block header, then STREAMINFO: how Media3 passes FLAC to the decoder. */
    private const val STREAMINFO_OFFSET = 8
    private const val STREAMINFO_LENGTH = 34

    /**
     * The largest frame the stream of [format] can have, from its STREAMINFO block, or null without one. An encoder
     * never writes a frame larger than the verbatim one: a frame header of at most 16 bytes, per channel a subframe
     * header of at most 2 bytes and every sample at full width (one bit more in a side channel), and the CRC.
     */
    fun maxFrameSize(format: Format): Int? {
        val info = streamInfo(format) ?: return null
        val bitsPerChannel = info.maxBlockSizeSamples.toLong() * (info.bitsPerSample + 1)
        return (16 + info.channels * (2 + (bitsPerChannel + 7) / 8) + 2).toInt()
    }

    /** The bits per sample of the stream of [format], from its STREAMINFO block, or null without one. */
    fun bitsPerSample(format: Format): Int? = streamInfo(format)?.bitsPerSample

    private fun streamInfo(format: Format): FlacStreamMetadata? {
        val header = format.initializationData.firstOrNull() ?: return null
        if (header.size < STREAMINFO_OFFSET + STREAMINFO_LENGTH || !header.startsWith("fLaC")) return null
        return FlacStreamMetadata(header, STREAMINFO_OFFSET)
    }

    private fun ByteArray.startsWith(marker: String) = marker.indices.all { this[it] == marker[it].code.toByte() }
}
