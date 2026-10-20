package io.github.lorddetson.piplayer.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FormatSummaryTest {

    private fun audio(
        mimeType: String?,
        container: String? = null,
        codecs: String? = null,
        pcmEncoding: Int = Format.NO_VALUE,
        sampleRate: Int = Format.NO_VALUE,
        channels: Int = Format.NO_VALUE,
        averageBitrate: Int = Format.NO_VALUE,
        peakBitrate: Int = Format.NO_VALUE,
        initializationData: List<ByteArray> = emptyList(),
    ) = Format.Builder()
        .setSampleMimeType(mimeType)
        .setContainerMimeType(container)
        .setCodecs(codecs)
        .setPcmEncoding(pcmEncoding)
        .setSampleRate(sampleRate)
        .setChannelCount(channels)
        .setAverageBitrate(averageBitrate)
        .setPeakBitrate(peakBitrate)
        .setInitializationData(initializationData)
        .build()

    /** "fLaC", a last-block STREAMINFO header and STREAMINFO with [bitsPerSample], as Media3 passes FLAC on. */
    private fun streamInfo(bitsPerSample: Int, sampleRate: Int = 44_100, channels: Int = 2): ByteArray {
        val bits = StringBuilder()
        fun put(value: Long, width: Int) = bits.append(value.toString(2).padStart(width, '0'))
        put(4_096, 16)
        put(4_096, 16)
        put(0, 24)
        put(0, 24)
        put(sampleRate.toLong(), 20)
        put(channels - 1L, 3)
        put(bitsPerSample - 1L, 5)
        put(88_200, 36)
        val info = bits.chunked(8).map { it.toInt(2).toByte() }.toByteArray() + ByteArray(16)
        return "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + info
    }

    @Test
    fun `FLAC names its bit depth and sample rate`() {
        val format = audio(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_FLAC, pcmEncoding = C.ENCODING_PCM_24BIT,
            sampleRate = 96_000, channels = 2)

        assertThat(FormatSummary.of(format)).isEqualTo(FormatSummary("FLAC", bits = 24, sampleRateHz = 96_000, channels = 2))
    }

    @Test
    fun `FLAC depths with no PCM encoding come from STREAMINFO`() {
        val twenty = audio(MimeTypes.AUDIO_FLAC, pcmEncoding = C.ENCODING_INVALID, sampleRate = 44_100, channels = 2,
            initializationData = listOf(streamInfo(bitsPerSample = 20)))
        val twelve = audio(MimeTypes.AUDIO_FLAC, sampleRate = 44_100, channels = 2,
            initializationData = listOf(streamInfo(bitsPerSample = 12)))
        val unknown = audio(MimeTypes.AUDIO_FLAC, sampleRate = 44_100, channels = 2)

        assertThat(FormatSummary.of(twenty)?.bits).isEqualTo(20)
        assertThat(FormatSummary.of(twelve)?.bits).isEqualTo(12)
        assertThat(FormatSummary.of(unknown)).isEqualTo(FormatSummary("FLAC", sampleRateHz = 44_100, channels = 2))
    }

    @Test
    fun `lossy formats name the average bitrate in whole kbit per second`() {
        val mp3 = audio(MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG, sampleRate = 44_100, channels = 2,
            averageBitrate = 319_872)
        val down = audio(MimeTypes.AUDIO_MPEG, sampleRate = 44_100, channels = 2, averageBitrate = 127_499)
        val up = audio(MimeTypes.AUDIO_MPEG, sampleRate = 44_100, channels = 2, averageBitrate = 127_500)

        assertThat(FormatSummary.of(mp3)).isEqualTo(FormatSummary("MP3", kbps = 320, sampleRateHz = 44_100, channels = 2))
        assertThat(FormatSummary.of(down)?.kbps).isEqualTo(127)
        assertThat(FormatSummary.of(up)?.kbps).isEqualTo(128)
    }

    @Test
    fun `a lossy format without an average bitrate names none, not its peak`() {
        val mllt = audio(MimeTypes.AUDIO_MPEG, sampleRate = 44_100, channels = 2)
        val peakOnly = audio(MimeTypes.AUDIO_VORBIS, sampleRate = 44_100, channels = 2, peakBitrate = 320_000)
        val opus = audio(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_OGG, sampleRate = 48_000, channels = 2)

        assertThat(FormatSummary.of(mllt)?.kbps).isNull()
        assertThat(FormatSummary.of(peakOnly)?.kbps).isNull()
        assertThat(FormatSummary.of(opus)).isEqualTo(FormatSummary("Opus", sampleRateHz = 48_000, channels = 2))
    }

    @Test
    fun `AAC is named by its profile`() {
        fun aac(codecs: String?) = audio(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MP4, codecs = codecs, sampleRate = 44_100,
            channels = 2, averageBitrate = 127_764, peakBitrate = 128_000)

        assertThat(FormatSummary.of(aac("mp4a.40.2"))).isEqualTo(FormatSummary("AAC", kbps = 128, sampleRateHz = 44_100, channels = 2))
        assertThat(FormatSummary.of(aac("mp4a.40.5"))?.codec).isEqualTo("HE-AAC")
        assertThat(FormatSummary.of(aac("mp4a.40.29"))?.codec).isEqualTo("HE-AAC v2")
        assertThat(FormatSummary.of(aac(null))?.codec).isEqualTo("AAC")
    }

    @Test
    fun `Vorbis names its nominal bitrate`() {
        val vorbis = audio(MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_OGG, sampleRate = 48_000, channels = 1,
            averageBitrate = 86_000)

        assertThat(FormatSummary.of(vorbis)).isEqualTo(FormatSummary("Vorbis", kbps = 86, sampleRateHz = 48_000, channels = 1))
    }

    @Test
    fun `WAV names its sample format, not its bitrate`() {
        fun wav(encoding: Int) = audio(MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV, pcmEncoding = encoding,
            sampleRate = 44_100, channels = 1, averageBitrate = 705_600, peakBitrate = 705_600)

        assertThat(FormatSummary.of(wav(C.ENCODING_PCM_16BIT)))
            .isEqualTo(FormatSummary("WAV", bits = 16, sampleRateHz = 44_100, channels = 1))
        assertThat(FormatSummary.of(wav(C.ENCODING_PCM_8BIT))?.bits).isEqualTo(8)
        assertThat(FormatSummary.of(wav(C.ENCODING_PCM_24BIT))?.bits).isEqualTo(24)
        assertThat(FormatSummary.of(wav(C.ENCODING_PCM_32BIT))?.bits).isEqualTo(32)
        assertThat(FormatSummary.of(wav(C.ENCODING_PCM_16BIT_BIG_ENDIAN))?.bits).isEqualTo(16)
        assertThat(FormatSummary.of(wav(C.ENCODING_PCM_FLOAT))).isEqualTo(
            FormatSummary("WAV", bits = 32, floating = true, sampleRateHz = 44_100, channels = 1),
        )
        assertThat(FormatSummary.of(wav(C.ENCODING_PCM_DOUBLE))?.let { it.bits to it.floating }).isEqualTo(64 to true)
    }

    @Test
    fun `A-law and mu-law in WAV are lossy`() {
        val alaw = audio(MimeTypes.AUDIO_ALAW, MimeTypes.AUDIO_WAV, sampleRate = 8_000, channels = 1, averageBitrate = 64_000)
        val mlaw = audio(MimeTypes.AUDIO_MLAW, MimeTypes.AUDIO_WAV, sampleRate = 8_000, channels = 1, averageBitrate = 64_000)

        assertThat(FormatSummary.of(alaw)).isEqualTo(FormatSummary("A-law", kbps = 64, sampleRateHz = 8_000, channels = 1))
        assertThat(FormatSummary.of(mlaw)?.codec).isEqualTo("μ-law")
    }

    @Test
    fun `other codecs have their own names`() {
        fun codec(mimeType: String) = FormatSummary.of(audio(mimeType, sampleRate = 8_000, channels = 1))?.codec

        assertThat(codec(MimeTypes.AUDIO_ALAC)).isEqualTo("ALAC")
        assertThat(FormatSummary.of(audio(MimeTypes.AUDIO_ALAC, pcmEncoding = C.ENCODING_PCM_24BIT))?.bits).isEqualTo(24)
        assertThat(codec(MimeTypes.AUDIO_MPEG_L1)).isEqualTo("MP1")
        assertThat(codec(MimeTypes.AUDIO_MPEG_L2)).isEqualTo("MP2")
        assertThat(codec(MimeTypes.AUDIO_AMR_NB)).isEqualTo("AMR-NB")
        assertThat(codec(MimeTypes.AUDIO_AMR_WB)).isEqualTo("AMR-WB")
        assertThat(codec("audio/x-something")).isEqualTo("audio/x-something")
    }

    @Test
    fun `unknown values are left out`() {
        val bare = audio(MimeTypes.AUDIO_MPEG)

        assertThat(FormatSummary.of(bare)).isEqualTo(FormatSummary("MP3"))
        assertThat(FormatSummary.of(audio(mimeType = null))).isNull()
    }
}
