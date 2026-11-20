package io.github.lorddetson.piplayer.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.lorddetson.piplayer.settings.ThemePalette
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThemePaletteTest {

    private val base = lightColorScheme(
        primary = Color(0xFF604190),
        onPrimary = Color.White,
        background = Color(0xFFFFF8EF),
        onBackground = Color(0xFF251A12),
        surface = Color(0xFFF8EEE2),
        onSurface = Color(0xFF33231B),
    )

    @Test
    fun `a saved palette without overrides uses the selected theme unchanged`() {
        assertThat(Json.decodeFromString<ThemePalette>("{}").applyTo(base)).isSameAs(base)
        assertThat(ThemePalette(textRgb = 0x22BB77).applyTo(base)).isSameAs(base)
        assertThat(ThemePalette(backgroundRgb = -1, surfaceRgb = 0x1000000).applyTo(base)).isSameAs(base)
    }

    @Test
    fun `a partial palette inherits every missing role`() {
        val palette = Json.decodeFromString<ThemePalette>("""{"backgroundRgb":0,"automaticText":false}""")
        val colors = palette.applyTo(base)

        assertThat(colors.background).isEqualTo(Color.Black)
        assertThat(colors.onBackground).isEqualTo(Color.White)
        assertThat(colors.primary).isEqualTo(base.primary)
        assertThat(colors.onPrimary).isEqualTo(base.onPrimary)
        assertThat(colors.surface).isEqualTo(base.surface)
        assertThat(colors.onSurface).isEqualTo(base.onSurface)
        assertThat(colors.surfaceContainer).isEqualTo(base.surfaceContainer)
    }

    @Test
    fun `automatic foregrounds independently contrast with accent background and panels`() {
        val colors = ThemePalette(accentRgb = 0xFFCC00, backgroundRgb = 0xFFFFFF, surfaceRgb = 0).applyTo(base)

        assertThat(colors.primary).isEqualTo(Color(0xFFFFCC00))
        assertThat(colors.onPrimary).isEqualTo(Color.Black)
        assertThat(colors.background).isEqualTo(Color.White)
        assertThat(colors.onBackground).isEqualTo(Color.Black)
        assertThat(colors.surface).isEqualTo(Color.Black)
        assertThat(colors.surfaceContainer).isEqualTo(Color.Black)
        assertThat(colors.surfaceVariant).isEqualTo(Color.Black)
        assertThat(colors.onSurface).isEqualTo(Color.White)
        assertThat(colors.onSurfaceVariant).isEqualTo(Color.White)
        val contrast = colors.paletteContrast()
        assertThat(contrast.backgroundText).isEqualTo(21f)
        assertThat(contrast.panelText).isEqualTo(21f)
        assertThat(contrast.hasLowTextContrast).isFalse()
        assertThat(contrast.accentOnBackground).isLessThan(4.5f)
        assertThat(contrast.accentOnPanel).isGreaterThan(4.5f)
    }

    @Test
    fun `manual text overrides auto and is retained when auto is restored`() {
        val manual = ThemePalette(backgroundRgb = 0, surfaceRgb = 0xFFFFFF, textRgb = 0x222222, automaticText = false)
        val colors = manual.applyTo(base)
        val expectedText = Color(0xFF222222)

        assertThat(colors.onBackground).isEqualTo(expectedText)
        assertThat(colors.onSurface).isEqualTo(expectedText)
        assertThat(colors.onSurfaceVariant).isEqualTo(expectedText)
        val contrast = colors.paletteContrast()
        assertThat(contrast.backgroundText).isLessThan(4.5f)
        assertThat(contrast.panelText).isGreaterThan(4.5f)
        assertThat(contrast.hasLowTextContrast).isTrue()

        val automatic = manual.copy(automaticText = true)
        val restored = Json.decodeFromString<ThemePalette>(Json.encodeToString(automatic))
        val autoColors = restored.applyTo(base)
        assertThat(autoColors.onBackground).isEqualTo(Color.White)
        assertThat(autoColors.onSurface).isEqualTo(Color.Black)
        assertThat(restored.copy(automaticText = false).applyTo(base).onBackground).isEqualTo(expectedText)
    }
}
