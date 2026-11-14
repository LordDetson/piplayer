package io.github.lorddetson.piplayer.library.lists

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The genre key of a name and the key of the songs without a genre (docs/library.md §19.1, §19.4). */
class GenreKeysTest {

    @Test
    fun `a key is the name normalized as for search`() {
        assertThat(GenreKeys.of("Hip-Hop")).isEqualTo(GenreKeys.of("hip hop")).isEqualTo("hip hop")
        assertThat(GenreKeys.of("ROCK")).isEqualTo(GenreKeys.of("Rock")).isEqualTo("rock")
        assertThat(GenreKeys.of("Электроника")).isEqualTo(GenreKeys.of("электроника"))
        assertThat(GenreKeys.of("Hip-Hop/Rap")).isEqualTo("hip hop rap")
    }

    @Test
    fun `a name of punctuation alone keeps itself in lower case`() {
        assertThat(GenreKeys.of("!")).isEqualTo("!")
        assertThat(GenreKeys.of("-")).isEqualTo("-")
        assertThat(GenreKeys.of("<>")).isEqualTo("<>")
    }

    @Test
    fun `no name gives the key of the songs without a genre`() {
        val names = listOf("!none", "!NONE", "None", "none", "!", "<none>", "-", "! none", "!none!", "Unknown genre", "")
        for (name in names) assertThat(GenreKeys.of(name)).describedAs(name).isNotEqualTo(GenreKeys.NONE)
        assertThat(GenreKeys.NONE).isEqualTo("!none")
    }
}
