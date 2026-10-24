package io.github.lorddetson.piplayer.ui.player

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TimerPresetsTest {

    @Test
    fun `the ready-made times run from the longest to the shortest`() {
        assertThat(TimerPresets.MINUTES).containsExactly(90, 60, 45, 30, 15)
    }

    @Test
    fun `the wheels match a preset by its hours and minutes`() {
        assertThat(TimerPresets.matching(hours = 1, minutes = 30)).isEqualTo(90)
        assertThat(TimerPresets.matching(hours = 0, minutes = 45)).isEqualTo(45)
        assertThat(TimerPresets.matching(hours = 1, minutes = 0)).isEqualTo(60)
        assertThat(TimerPresets.matching(hours = 0, minutes = 20)).isNull()
        assertThat(TimerPresets.matching(hours = 0, minutes = 0)).isNull()
    }
}
