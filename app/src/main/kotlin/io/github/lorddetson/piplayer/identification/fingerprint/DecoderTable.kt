package io.github.lorddetson.piplayer.identification.fingerprint

import androidx.annotation.OptIn
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** How the audio of a codec reaches Chromaprint (TI §3.2). */
enum class DecodingRoute {
    /** Decoded by a platform `MediaCodec` decoder. */
    PLATFORM_DECODER,

    /** PCM stored in the container, handed on without a decoder. */
    CONTAINER_PCM,
}

/**
 * One row of the decoding table: a codec as Media3's extractor names it ([mimeType]), the containers the player plays
 * it in (docs/playback.md §1.7), its [route] and the rules of its format. With [endsAtRepeatedTime] a sample with the
 * time of the one before ends the stream, and with [holdsLastSample] the last sample waits until the decoder has put
 * out the frame before it (both FLAC, TI §3.2 "End of the stream"). Without [platformDecoder] Android has no decoder
 * for the codec (ALAC): a device without one of its maker's refuses it as an unsupported format, so in 1.0 such a file
 * is identified by its tags and text search only, until bundled decoders come in v1.x.
 */
data class DecoderRow(
    val codec: String,
    val mimeType: String,
    val containers: List<String>,
    val route: DecodingRoute = DecodingRoute.PLATFORM_DECODER,
    val endsAtRepeatedTime: Boolean = false,
    val holdsLastSample: Boolean = false,
    val platformDecoder: Boolean = true,
)

/**
 * The "format → extractor and decoder" table of TI §3.2. Every codec goes through Media3's `MediaExtractorCompat`; the
 * row says how it is decoded then. DecodingTableTest checks every row against a fixture of the player's formats.
 */
object DecoderTable {

    /** Instead of a decoder name in [decoderPath], for PCM in the container. */
    const val CONTAINER = "container"

    val ROWS: List<DecoderRow> = listOf(
        DecoderRow("MP3", "audio/mpeg", listOf("MP3")),
        DecoderRow("AAC", "audio/mp4a-latm", listOf("MP4", "ADTS")),
        DecoderRow("AMR-NB", "audio/3gpp", listOf("AMR", "3GP")),
        DecoderRow("AMR-WB", "audio/amr-wb", listOf("AMR", "3GP")),
        DecoderRow("Vorbis", "audio/vorbis", listOf("Ogg", "Matroska")),
        DecoderRow("Opus", "audio/opus", listOf("Ogg", "WebM")),
        DecoderRow("FLAC", "audio/flac", listOf("FLAC"), endsAtRepeatedTime = true, holdsLastSample = true),
        DecoderRow("PCM", "audio/raw", listOf("WAV"), route = DecodingRoute.CONTAINER_PCM),
        DecoderRow("ALAC", "audio/alac", listOf("MP4"), platformDecoder = false),
    )

    private val byMimeType = ROWS.associateBy { it.mimeType }

    /** The row of [mimeType]; a codec outside the table takes a platform decoder, when the device has one. */
    fun rowOf(mimeType: String): DecoderRow {
        val key = mimeType.lowercase(Locale.ROOT)
        return byMimeType[key] ?: DecoderRow(key, key, emptyList())
    }

    /**
     * How the audio was decoded, as the fingerprint cache keeps it (`fingerprint.decoder_path`, TI §10.1):
     * `media3-<version>:<MIME type>:<decoder name>`, with [CONTAINER] for PCM in the container.
     */
    @OptIn(UnstableApi::class)
    fun decoderPath(mimeType: String, decoderName: String?): String =
        "media3-${MediaLibraryInfo.VERSION}:$mimeType:${decoderName ?: CONTAINER}"
}
