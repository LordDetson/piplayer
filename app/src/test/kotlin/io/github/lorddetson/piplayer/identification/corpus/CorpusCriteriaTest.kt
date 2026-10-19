package io.github.lorddetson.piplayer.identification.corpus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CorpusCriteriaTest {

    private val track = LikedTrack(1, "Paper Lanterns Radio Edit", "The Quiet Harbors, Ada Vale", 214_000)

    @Test
    fun `strict needs the whole title`() {
        assertThat(CorpusCriteria.strict(track, "Paper Lanterns (Radio Edit)", "The Quiet Harbors & Ada Vale", 215_000))
            .isTrue()
        assertThat(CorpusCriteria.strict(track, "Paper Lanterns", "The Quiet Harbors & Ada Vale", 215_000)).isFalse()
    }

    @Test
    fun `lenient compares the names without the version`() {
        assertThat(CorpusCriteria.lenient(track, "Paper Lanterns", "The Quiet Harbors & Ada Vale", 215_000)).isTrue()
    }

    @Test
    fun `both need the artist and a length within 5 s`() {
        assertThat(CorpusCriteria.lenient(track, "Paper Lanterns", "Someone Else", 215_000)).isFalse()
        assertThat(CorpusCriteria.lenient(track, "Paper Lanterns", "The Quiet Harbors", 220_000)).isFalse()
        assertThat(CorpusCriteria.lenient(track, "Paper Lanterns", "The Quiet Harbors", null)).isFalse()
    }

    @Test
    fun `the first credited artist is enough`() {
        assertThat(CorpusCriteria.artistSimilarity("The Quiet Harbors", "The Quiet Harbors feat. Ada Vale"))
            .isEqualTo(1.0)
    }
}
