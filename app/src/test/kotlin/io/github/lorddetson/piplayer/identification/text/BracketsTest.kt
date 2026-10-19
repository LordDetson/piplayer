package io.github.lorddetson.piplayer.identification.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BracketsTest {

    @Test
    fun `splits off the bracketed parts at the end`() {
        assertThat(Brackets.split("Paper Lanterns (Night Remix) [Live]"))
            .isEqualTo(Pair("Paper Lanterns", "Night Remix Live"))
    }

    @Test
    fun `brackets before the end belong to the name`() {
        assertThat(Brackets.split("(Don't Fear) The Reaper")).isEqualTo(Pair("(Don't Fear) The Reaper", ""))
        assertThat(Brackets.split("Paper (Blue) Lanterns (Live)")).isEqualTo(Pair("Paper (Blue) Lanterns", "Live"))
    }

    @Test
    fun `full-width brackets count`() {
        assertThat(Brackets.split("夜に駆ける（Live）")).isEqualTo(Pair("夜に駆ける", "Live"))
    }

    @Test
    fun `a title that is all brackets keeps it as the name`() {
        assertThat(Brackets.split("(Intro)")).isEqualTo(Pair("(Intro)", ""))
    }
}
