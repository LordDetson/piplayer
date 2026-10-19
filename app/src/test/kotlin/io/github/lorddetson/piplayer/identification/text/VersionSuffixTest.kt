package io.github.lorddetson.piplayer.identification.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class VersionSuffixTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        Night Work Original Mix          | Night Work (Original Mix)
        Paper Lanterns Radio Edit        | Paper Lanterns (Radio Edit)
        Paper Lanterns Extended Mix      | Paper Lanterns (Extended Mix)
        Paper Lanterns Remix             | Paper Lanterns (Remix)
        Paper Lanterns Lofi Remix        | Paper Lanterns (Lofi Remix)
        Paper Lanterns Slowed            | Paper Lanterns (Slowed)
        Paper Lanterns slowed + reverb   | Paper Lanterns (slowed + reverb)
        Paper Lanterns Sped Up           | Paper Lanterns (Sped Up)
        Paper Lanterns Instrumental      | Paper Lanterns (Instrumental)
        Paper Lanterns Rain Version      | Paper Lanterns (Rain Version)
        Paper Lanterns Remastered 2011   | Paper Lanterns (Remastered 2011)
        Бумажные фонари Slowed           | Бумажные фонари (Slowed)
        Paper Lanterns - slowed down     | Paper Lanterns (slowed down)
        Paper Lanterns - Live            | Paper Lanterns (Live)
        Paper Lanterns - Ada Vale Remix  | Paper Lanterns (Ada Vale Remix)
        Paper Lanterns Re-Recorded       | Paper Lanterns (Re-Recorded)
        Paper Lanterns Bonus Track       | Paper Lanterns (Bonus Track)
        Paper Lanterns From Harbor Lights Soundtrack | Paper Lanterns (From Harbor Lights Soundtrack)""",
    )
    fun `brackets a trailing version description`(title: String, expected: String) {
        assertThat(VersionSuffix.bracket(title)).isEqualTo(expected)
    }

    @Test
    fun `a no-break space works like a space`() {
        val nbsp = Char(0x00A0)
        assertThat(VersionSuffix.bracket("Paper Lanterns${nbsp}Radio Edit")).isEqualTo("Paper Lanterns (Radio Edit)")
    }

    @Test
    fun `a quoted source after From is a version description`() {
        assertThat(VersionSuffix.bracket("Paper Lanterns From \"Harbor Lights\""))
            .isEqualTo("Paper Lanterns (From \"Harbor Lights\")")
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        Paper Lanterns (Original Mix)
        Paper Lanterns [Remix]
        Remix
        Original Mix
        Live Forever
        Mix Tape Memories
        Paper Lanterns""",
    )
    fun `leaves titles alone that have no unbracketed suffix or nothing before it`(title: String) {
        assertThat(VersionSuffix.bracket(title)).isEqualTo(title)
    }
}
