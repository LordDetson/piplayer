package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.score.TrackHint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpikeHintsTest {

    @Test
    fun `tags give title, artist, album, track number and the duration`() {
        val tags = mapOf(
            "TITLE" to listOf("Paper Lanterns"),
            "ARTIST" to listOf("The Quiet Harbors"),
            "ALBUM" to listOf("Harbor Lights"),
            "TRACKNUMBER" to listOf("3/11"),
        )
        val hint = SpikeHints.from(tags, "01 - Someone Else - Other Song.mp3", durationMs = 214_000)

        assertThat(hint.source).isEqualTo(HintSource.TAGS)
        assertThat(hint.hint).isEqualTo(TrackHint("Paper Lanterns", "The Quiet Harbors", 214_000, "Harbor Lights", 3))
    }

    @Test
    fun `several artists are joined by commas`() {
        val tags = mapOf("TITLE" to listOf("Song"), "ARTIST" to listOf("A", "B"))
        assertThat(SpikeHints.from(tags, null, null).hint.artist).isEqualTo("A, B")
    }

    @Test
    fun `without a title tag the file name supplies title, artist and track number`() {
        val hint = SpikeHints.from(emptyMap(), "07 - The Quiet Harbors - Paper Lanterns.flac", durationMs = 1_000)

        assertThat(hint.source).isEqualTo(HintSource.FILE_NAME)
        assertThat(hint.hint).isEqualTo(TrackHint("Paper Lanterns", "The Quiet Harbors", 1_000, null, 7))
    }

    @Test
    fun `an artist tag wins over the artist in the file name`() {
        val tags = mapOf("ARTIST" to listOf("Тихая Гавань"), "TITLE" to listOf("  "))
        val hint = SpikeHints.from(tags, "Quiet Harbors - Бумажные фонари.mp3", null)

        assertThat(hint.source).isEqualTo(HintSource.FILE_NAME)
        assertThat(hint.hint.title).isEqualTo("Бумажные фонари")
        assertThat(hint.hint.artist).isEqualTo("Тихая Гавань")
    }

    @Test
    fun `no title anywhere leaves the hint without one`() {
        val hint = SpikeHints.from(mapOf("ARTIST" to listOf("The Quiet Harbors")), "AUD-20230101-WA0001.opus", 90_000)

        assertThat(hint.source).isEqualTo(HintSource.NONE)
        assertThat(hint.hint).isEqualTo(TrackHint(null, "The Quiet Harbors", 90_000))
    }

    @Test
    fun `a track number tag that is not a number is ignored`() {
        val tags = mapOf("TITLE" to listOf("Song"), "TRACKNUMBER" to listOf("A1"))
        assertThat(SpikeHints.from(tags, null, null).hint.trackNumber).isNull()
    }
}
