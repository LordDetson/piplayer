package io.github.lorddetson.piplayer.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NowPlayingMoodTest {
    @Test
    fun `low and high energy give visibly different tints`() {
        val surface = Color(0xFF151515)
        val calm = moodTint(surface, Color.White, Color.LightGray, energy = 0f, intensity = 0.5f)
        val lively = moodTint(surface, Color.White, Color.LightGray, energy = 1f, intensity = 0.5f)

        assertThat(calm.blue).isGreaterThan(calm.red)
        assertThat(lively.red).isGreaterThan(lively.blue)
    }

    @Test
    fun `the backdrop keeps theme text readable`() {
        val surface = Color.White
        val text = Color(0xFF666666)
        val secondary = Color(0xFF737373)
        val tint = moodTint(surface, text, secondary, energy = 1f, intensity = 1f)

        assertThat((tint.luminance() + 0.05f) / (secondary.luminance() + 0.05f)).isGreaterThanOrEqualTo(4.5f)
    }
}
