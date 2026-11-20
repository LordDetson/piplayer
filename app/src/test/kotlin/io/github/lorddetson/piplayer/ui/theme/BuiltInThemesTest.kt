package io.github.lorddetson.piplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.lorddetson.piplayer.settings.ThemeMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BuiltInThemesTest {
    @Test
    fun `AMOLED is black and each preset has its own surface and accent`() {
        val palettes = listOf("amoled", "graphite", "warm_light").map(::palette)

        assertThat(palettes[0].background).isEqualTo(Color.Black)
        assertThat(palettes[0].surface).isEqualTo(Color.Black)
        assertThat(palettes.map { it.surface }).doesNotHaveDuplicates()
        assertThat(palettes.map { it.primary }).doesNotHaveDuplicates()
        assertThat(palettes[1].surface.luminance()).isLessThan(0.08f)
        assertThat(palettes[2].surface.luminance()).isGreaterThan(0.75f)
    }

    @Test
    fun `preset text and actions meet minimum contrast on their rendered backgrounds`() {
        for (id in listOf("amoled", "graphite", "warm_light")) {
            val colors = palette(id)
            val pairs = listOf(
                colors.onBackground to colors.background,
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surfaceVariant,
                colors.onSurfaceVariant to colors.surfaceContainer,
                colors.onSurface to colors.surfaceContainerHigh,
                colors.primary to colors.surface,
                colors.primary to colors.surfaceContainer,
                colors.error to colors.surface,
                colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onTertiary to colors.tertiary,
                colors.onTertiaryContainer to colors.tertiaryContainer,
            )
            for ((foreground, background) in pairs) {
                val lighter = maxOf(foreground.luminance(), background.luminance())
                val darker = minOf(foreground.luminance(), background.luminance())
                assertThat((lighter + 0.05f) / (darker + 0.05f))
                    .describedAs("%s contrast of %s against %s", id, foreground, background)
                    .isGreaterThanOrEqualTo(4.5f)
            }
        }
    }

    private fun palette(id: String): ColorScheme = checkNotNull(builtInColorScheme(ThemeMode.fromStored(id))) {
        "No built-in color scheme for $id"
    }
}
