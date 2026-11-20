package io.github.lorddetson.piplayer.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NowPlayingLayoutTest {
    @Test
    fun `stored layout restores its preset or defaults to standard`() {
        assertThat(NowPlayingLayout.fromStored("minimal")).isEqualTo(NowPlayingLayout.MINIMAL)
        assertThat(NowPlayingLayout.fromStored("expanded")).isEqualTo(NowPlayingLayout.EXPANDED)
        assertThat(NowPlayingLayout.fromStored("standard")).isEqualTo(NowPlayingLayout.STANDARD)
        assertThat(NowPlayingLayout.fromStored(null)).isEqualTo(NowPlayingLayout.STANDARD)
        assertThat(NowPlayingLayout.fromStored("unknown")).isEqualTo(NowPlayingLayout.STANDARD)
    }
}
