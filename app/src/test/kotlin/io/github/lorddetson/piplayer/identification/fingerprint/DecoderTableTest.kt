package io.github.lorddetson.piplayer.identification.fingerprint

import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The decoding table in code (TI §3.2); DecodingTableTest checks it against the fixtures on a device. */
@androidx.annotation.OptIn(UnstableApi::class)
class DecoderTableTest {

    @Test
    fun hasOneRowPerMimeType() {
        val mimeTypes = DecoderTable.ROWS.map { it.mimeType }

        assertThat(mimeTypes).doesNotHaveDuplicates()
        assertThat(mimeTypes).allSatisfy { assertThat(it).isLowerCase() }
    }

    @Test
    fun listsEveryCodecThePlayerPlays() {
        assertThat(DecoderTable.ROWS.map { it.codec })
            .containsExactlyInAnyOrder("MP3", "AAC", "AMR-NB", "AMR-WB", "Vorbis", "Opus", "FLAC", "PCM", "ALAC")
    }

    @Test
    fun onlyFlacEndsAtARepeatedTimeAndHoldsItsLastSample() {
        val flac = DecoderTable.rowOf("audio/flac")

        assertThat(flac.endsAtRepeatedTime).isTrue()
        assertThat(flac.holdsLastSample).isTrue()
        assertThat(DecoderTable.ROWS.filter { it.endsAtRepeatedTime || it.holdsLastSample }).containsExactly(flac)
    }

    @Test
    fun pcmInTheContainerTakesNoDecoder() {
        assertThat(DecoderTable.rowOf("audio/raw").route).isEqualTo(DecodingRoute.CONTAINER_PCM)
        assertThat(DecoderTable.ROWS.filter { it.route == DecodingRoute.CONTAINER_PCM }.map { it.mimeType })
            .containsExactly("audio/raw")
    }

    @Test
    fun alacHasNoPlatformDecoder() {
        val alac = DecoderTable.rowOf("audio/alac")

        assertThat(alac.platformDecoder).isFalse()
        assertThat(alac.route).isEqualTo(DecodingRoute.PLATFORM_DECODER)
        assertThat(DecoderTable.ROWS.filterNot { it.platformDecoder }).containsExactly(alac)
    }

    @Test
    fun aCodecOutsideTheTableTakesThePlatformRoute() {
        val row = DecoderTable.rowOf("audio/ac3")

        assertThat(row.route).isEqualTo(DecodingRoute.PLATFORM_DECODER)
        assertThat(row.platformDecoder).isTrue()
        assertThat(row.endsAtRepeatedTime).isFalse()
        assertThat(row.holdsLastSample).isFalse()
        assertThat(row.containers).isEmpty()
    }

    @Test
    fun findsARowWhateverTheCaseOfTheMimeType() {
        assertThat(DecoderTable.rowOf("audio/FLAC")).isEqualTo(DecoderTable.rowOf("audio/flac"))
    }

    @Test
    fun buildsTheDecoderPathFromTheMedia3VersionTheMimeTypeAndTheDecoder() {
        assertThat(DecoderTable.decoderPath("audio/flac", "c2.android.flac.decoder"))
            .isEqualTo("media3-${MediaLibraryInfo.VERSION}:audio/flac:c2.android.flac.decoder")
        assertThat(DecoderTable.decoderPath("audio/raw", null))
            .isEqualTo("media3-${MediaLibraryInfo.VERSION}:audio/raw:container")
    }
}
