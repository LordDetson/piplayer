package io.github.lorddetson.piplayer.identification.fingerprint

import android.media.MediaCodecList
import io.github.lorddetson.piplayer.TestFiles
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The decoding table (TI §3.2, §11.2 M3): for every format the player plays (docs/playback.md §1.7) the path its audio
 * takes to Chromaprint today, through Media3's `MediaExtractorCompat` and the fingerprint path of identification. A
 * platform decoder decodes compressed formats, PCM in the container (WAV) needs none, and ALAC, which the Android
 * platform has no decoder for, is not fingerprintable: in 1.0 such a file is identified by text search only. The
 * fixtures are the 2 s tones of scripts/generate_decoding_fixtures.py, shorter than Chromaprint's first item (about
 * 2.7 s), so "fingerprinted" means that all of the audio reached Chromaprint at its rate and the fingerprint finished;
 * how long that audio is, per fixture, DecodingFixturesTest says.
 */
@RunWith(Parameterized::class)
class DecodingTableTest(private val row: Row) {

    enum class Path {
        /** Decoded by the platform's `MediaCodec`. */
        PLATFORM_DECODER,

        /** PCM stored in the container, handed on without a decoder. */
        CONTAINER_PCM,

        /** No decoder on the platform: the file is not fingerprinted. */
        NOT_FINGERPRINTABLE,
    }

    data class Row(
        val format: String,
        val fixture: String,
        val mimeType: String,
        val path: Path,
        /**
         * The length a decoder must give, for a fixture that is no decoding fixture because the platform cannot decode
         * it (DecodingFixturesTest would fail on it); every other row takes its length from DecodingFixturesTest.
         */
        val ownLength: DecodingFixturesTest.Fixture? = null,
    ) {
        override fun toString() = format
    }

    companion object {
        private const val SECONDS = 2.0

        /** Rounding of the length to whole samples after resampling. */
        private const val SLACK_SECONDS = 0.001

        val TABLE = listOf(
            Row("MP3", "mp3-44100-stereo.mp3", "audio/mpeg", Path.PLATFORM_DECODER),
            Row("MP3 at 320k", "mp3-44100-320k-stereo.mp3", "audio/mpeg", Path.PLATFORM_DECODER),
            Row("AAC in MP4", "aac-44100-stereo.m4a", "audio/mp4a-latm", Path.PLATFORM_DECODER),
            Row("raw AAC (ADTS)", "aac-adts-44100-stereo.aac", "audio/mp4a-latm", Path.PLATFORM_DECODER),
            Row("raw AMR-NB", "amr-nb-8000-mono.amr", "audio/3gpp", Path.PLATFORM_DECODER),
            Row("AMR-NB in 3GP", "amr-nb-8000-mono.3gp", "audio/3gpp", Path.PLATFORM_DECODER),
            Row("raw AMR-WB", "amr-wb-16000-mono.awb", "audio/amr-wb", Path.PLATFORM_DECODER),
            Row("AMR-WB in 3GP", "amr-wb-16000-mono.3gp", "audio/amr-wb", Path.PLATFORM_DECODER),
            Row("Ogg Vorbis", "vorbis-48000-mono.ogg", "audio/vorbis", Path.PLATFORM_DECODER),
            Row("Vorbis in Matroska", "vorbis-44100-stereo.mka", "audio/vorbis", Path.PLATFORM_DECODER),
            Row("Opus in Ogg", "opus-48000-stereo.opus", "audio/opus", Path.PLATFORM_DECODER),
            Row("Opus in WebM", "opus-48000-stereo.webm", "audio/opus", Path.PLATFORM_DECODER),
            Row("FLAC", "flac-96000-24bit-stereo.flac", "audio/flac", Path.PLATFORM_DECODER),
            Row("FLAC in 5.1", "flac-48000-16bit-5.1.flac", "audio/flac", Path.PLATFORM_DECODER),
            Row("WAV", "wav-44100-16bit-stereo.wav", "audio/raw", Path.CONTAINER_PCM),
            // The file holds exactly 2 s (its last frame is short); a maker's decoder may give at most one 4096-sample
            // ALAC frame more.
            Row(
                "ALAC in MP4", "alac-44100-stereo.m4a", "audio/alac", Path.NOT_FINGERPRINTABLE,
                ownLength = DecodingFixturesTest.Fixture(
                    "alac-44100-stereo.m4a", "audio/alac", 44_100, 2, endPaddingSeconds = 0.093,
                ),
            ),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows() = TABLE.map { arrayOf<Any>(it) }

        private val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }

        private fun decoderTakes(mimeType: String) =
            decoders.any { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
    }

    /** The fixture whose length bounds the row's audio; a row without one fails instead of skipping the check. */
    private fun expectedLength(): DecodingFixturesTest.Fixture =
        row.ownLength
            ?: DecodingFixturesTest.FIXTURES.singleOrNull { it.name == row.fixture }
            ?: throw AssertionError("${row.fixture} has no length: add it to DecodingFixturesTest.FIXTURES")

    private fun fingerprint(): FingerprintResult {
        val file = TestFiles.copy("decoding/${row.fixture}")
        return try {
            TestFiles.open(file).use { pfd ->
                val source = MediaCodecPcmSource(TestFiles.targetContext, pfd.fileDescriptor)
                runBlocking { NativeFingerprinting.calculator().fingerprint(source) }
            }
        } finally {
            file.delete()
        }
    }

    /** Also for a row whose path ends before the length is checked on this device (ALAC without a decoder). */
    @Test
    fun knowsTheLengthItsAudioMustHave() {
        val fixture = expectedLength()

        assertThat(fixture.name).isEqualTo(row.fixture)
        assertThat(fixture.mimeType).isEqualTo(row.mimeType)
    }

    @Test
    fun takesItsPathToChromaprint() {
        // A phone whose maker adds a decoder the platform lacks decodes that format like the others.
        if (row.path == Path.NOT_FINGERPRINTABLE && !decoderTakes(row.mimeType)) {
            assertThatThrownBy { fingerprint() }
                .isInstanceOfSatisfying(DecodingException.UnsupportedFormat::class.java) { error ->
                    assertThat(error.mimeType).isEqualTo(row.mimeType)
                }
            return
        }

        val result = fingerprint()

        assertThat(result.source.mimeType).isEqualTo(row.mimeType)
        val decoderNames = decoders.map { it.name }
        if (row.path == Path.CONTAINER_PCM) {
            assertThat(decoderNames).doesNotContain(result.source.decoderName)
        } else {
            assertThat(decoderNames).contains(result.source.decoderName)
        }
        assertThat(result.source.endedEarly).isNull()
        assertThat(result.reachedEnd).isTrue()
        val fixture = expectedLength()
        assertThat(result.fedSeconds).isBetween(
            SECONDS - fixture.shortBySeconds - SLACK_SECONDS,
            SECONDS + fixture.endPaddingSeconds + SLACK_SECONDS,
        )
        // Too short for an item, but Chromaprint took all of it and finished.
        assertThat(result.fingerprint.raw).isEmpty()
    }
}
