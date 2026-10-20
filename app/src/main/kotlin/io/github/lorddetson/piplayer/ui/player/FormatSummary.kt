package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.github.lorddetson.piplayer.playback.FlacFrames

/**
 * What the format line says of an audio track (docs/playback.md §3.5): the codec, the bit depth of a lossless format or
 * the average bitrate of a lossy one, the sample rate and the channel count, each null when unknown.
 */
data class FormatSummary(
    val codec: String,
    val bits: Int? = null,
    val floating: Boolean = false,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
) {
    @OptIn(UnstableApi::class)
    companion object {
        private val lossless = setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW)

        /** The summary of [format], or null without a media type. */
        fun of(format: Format): FormatSummary? {
            val mimeType = format.sampleMimeType ?: return null
            val sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE }
            val channels = format.channelCount.takeIf { it != Format.NO_VALUE }
            if (mimeType !in lossless) {
                // Not Format.bitrate: that is the peak bitrate whenever the file states one.
                val kbps = format.averageBitrate.takeIf { it != Format.NO_VALUE }?.let { (it + 500) / 1_000 }
                return FormatSummary(codec(format, mimeType), kbps = kbps, sampleRateHz = sampleRate, channels = channels)
            }
            val floating = format.pcmEncoding == C.ENCODING_PCM_FLOAT || format.pcmEncoding == C.ENCODING_PCM_DOUBLE
            val bits = bits(format.pcmEncoding) ?: if (mimeType == MimeTypes.AUDIO_FLAC) FlacFrames.bitsPerSample(format) else null
            return FormatSummary(codec(format, mimeType), bits, floating, sampleRateHz = sampleRate, channels = channels)
        }

        private fun codec(format: Format, mimeType: String): String = when (mimeType) {
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_RAW -> "WAV"
            MimeTypes.AUDIO_ALAW -> "A-law"
            MimeTypes.AUDIO_MLAW -> "μ-law"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_MPEG_L1 -> "MP1"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_AAC -> when (format.codecs) {
                "mp4a.40.5" -> "HE-AAC"
                "mp4a.40.29" -> "HE-AAC v2"
                else -> "AAC"
            }
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_AMR_NB -> "AMR-NB"
            MimeTypes.AUDIO_AMR_WB -> "AMR-WB"
            else -> mimeType
        }

        private fun bits(pcmEncoding: Int): Int? = when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            C.ENCODING_PCM_DOUBLE -> 64
            else -> null
        }
    }
}
