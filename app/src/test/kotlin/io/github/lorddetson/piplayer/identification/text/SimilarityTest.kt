package io.github.lorddetson.piplayer.identification.text

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class SimilarityTest {

    private val tolerance = within(1e-9)

    @Test
    fun `ratio is one for equal strings, including two empty ones`() {
        assertThat(Similarity.ratio("abc", "abc")).isEqualTo(1.0)
        assertThat(Similarity.ratio("", "")).isEqualTo(1.0)
    }

    @Test
    fun `ratio is zero against an empty string`() {
        assertThat(Similarity.ratio("abc", "")).isEqualTo(0.0)
        assertThat(Similarity.ratio("", "abc")).isEqualTo(0.0)
    }

    @Test
    fun `ratio is one minus the edit distance over the longer length`() {
        // kitten -> sitting: 3 edits, longer length 7.
        assertThat(Similarity.ratio("kitten", "sitting")).isCloseTo(1 - 3.0 / 7, tolerance)
        assertThat(Similarity.ratio("sitting", "kitten")).isCloseTo(1 - 3.0 / 7, tolerance)
    }

    @Test
    fun `text compares normalized strings`() {
        assertThat(Similarity.text("Ёжик", "ежик")).isEqualTo(1.0)
        assertThat(Similarity.text("Beyoncé", "BEYONCE")).isEqualTo(1.0)
    }

    @Test
    fun `title with the same suffix on both sides is a full match`() {
        assertThat(Similarity.title("Night Work (Original Mix)", "night work (original mix)")).isEqualTo(1.0)
    }

    @Test
    fun `title suffix on only one side costs 0,2`() {
        assertThat(Similarity.title("Another Love", "Another Love (Live)")).isCloseTo(0.8, tolerance)
        assertThat(Similarity.title("Another Love [Acoustic]", "Another Love")).isCloseTo(0.8, tolerance)
    }

    @Test
    fun `different suffixes on both sides cost at least as much as a suffix on one side`() {
        assertThat(Similarity.title("Song (Remix)", "Song (Live)")).isLessThanOrEqualTo(0.8)
        assertThat(Similarity.title("Paper Lanterns (Remastered)", "Paper Lanterns (Live)"))
            .isLessThanOrEqualTo(Similarity.title("Paper Lanterns (Remastered)", "Paper Lanterns"))
    }

    @Test
    fun `a featured artist or a remaster note in brackets is not a different version`() {
        assertThat(Similarity.title("Paper Lanterns (feat. Ada Vale)", "Paper Lanterns")).isEqualTo(1.0)
        assertThat(Similarity.title("Paper Lanterns (Remastered 2011)", "Paper Lanterns")).isEqualTo(1.0)
        assertThat(Similarity.title("Paper Lanterns [2011 Remaster]", "Paper Lanterns")).isEqualTo(1.0)
    }

    @Test
    fun `brackets split differently on the two sides still compare the whole titles`() {
        // The left one ends with an unbracketed version, so its "(Drop It to the Floor)" is not a trailing group.
        assertThat(Similarity.title("Hey Baby (Drop It to the Floor) Radio Edit", "Hey Baby (Drop It to the Floor)"))
            .isGreaterThanOrEqualTo(0.7)
    }

    @Test
    fun `comparing whole titles never beats the price of a different version`() {
        assertThat(Similarity.title("Paper Lanterns (Night Remix)", "Paper Lanterns (Day Remix)")).isLessThanOrEqualTo(0.8)
    }

    @Test
    fun `brackets at the start of a title are part of the name`() {
        assertThat(Similarity.title("(Don't Fear) The Reaper", "Don't Fear the Reaper")).isEqualTo(1.0)
    }

    @Test
    fun `full-width brackets work like ASCII ones`() {
        assertThat(Similarity.title("夜に駆ける（Live）", "夜に駆ける (Live)")).isEqualTo(1.0)
    }

    @Test
    fun `title similarity never drops below zero`() {
        assertThat(Similarity.title("abc (Live)", "xyz")).isGreaterThanOrEqualTo(0.0)
    }
}
