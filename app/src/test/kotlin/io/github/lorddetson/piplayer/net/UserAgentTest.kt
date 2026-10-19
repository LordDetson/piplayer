package io.github.lorddetson.piplayer.net

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UserAgentTest {

    @Test
    fun `contains app name, version and contact in the MusicBrainz format`() {
        assertThat(UserAgent.of("0.1.0")).isEqualTo("PiPlayer/0.1.0 ( https://github.com/LordDetson/piplayer )")
    }

    @Test
    fun `keeps a debug suffix of the version name`() {
        assertThat(UserAgent.of("0.1.0-debug"))
            .isEqualTo("PiPlayer/0.1.0-debug ( https://github.com/LordDetson/piplayer )")
    }

    @Test
    fun `rejects a blank version name`() {
        assertThatThrownBy { UserAgent.of(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a version name with spaces, which would break the header format`() {
        assertThatThrownBy { UserAgent.of("0.1 beta") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
