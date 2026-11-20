package io.github.lorddetson.piplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.lorddetson.piplayer.settings.ThemePalette

/** Resolves only the supplied roles, preserving the selected base palette for anything left unset. */
internal fun ThemePalette.applyTo(base: ColorScheme): ColorScheme {
    val accent = accentRgb.asColor()
    val background = backgroundRgb.asColor()
    val panel = surfaceRgb.asColor()
    val manualText = if (automaticText) null else textRgb.asColor()
    if (accent == null && background == null && panel == null && manualText == null) return base

    val backgroundText = manualText ?: background?.let(::autoText)
    val panelText = manualText ?: panel?.let(::autoText)
    return base.copy(
        primary = accent ?: base.primary,
        onPrimary = accent?.let(::autoText) ?: base.onPrimary,
        surfaceTint = accent ?: base.surfaceTint,
        background = background ?: base.background,
        onBackground = backgroundText ?: base.onBackground,
        surface = panel ?: base.surface,
        onSurface = panelText ?: base.onSurface,
        surfaceVariant = panel ?: base.surfaceVariant,
        onSurfaceVariant = panelText ?: base.onSurfaceVariant,
        surfaceDim = panel ?: base.surfaceDim,
        surfaceBright = panel ?: base.surfaceBright,
        surfaceContainerLowest = panel ?: base.surfaceContainerLowest,
        surfaceContainerLow = panel ?: base.surfaceContainerLow,
        surfaceContainer = panel ?: base.surfaceContainer,
        surfaceContainerHigh = panel ?: base.surfaceContainerHigh,
        surfaceContainerHighest = panel ?: base.surfaceContainerHighest,
    )
}

internal data class PaletteContrast(
    val backgroundText: Float,
    val panelText: Float,
    val accentOnBackground: Float,
    val accentOnPanel: Float,
) {
    val hasLowTextContrast: Boolean get() = backgroundText < 4.5f || panelText < 4.5f
}

internal fun ColorScheme.paletteContrast(): PaletteContrast = PaletteContrast(
    backgroundText = contrast(onBackground, background),
    panelText = contrast(onSurface, surface),
    accentOnBackground = contrast(primary, background),
    accentOnPanel = contrast(primary, surface),
)

private fun Int?.asColor(): Color? = this?.takeIf { it in 0..0xFFFFFF }?.let { Color(0xFF000000 or it.toLong()) }

private fun autoText(background: Color): Color =
    if (contrast(Color.Black, background) >= contrast(Color.White, background)) Color.Black else Color.White

private fun contrast(a: Color, b: Color): Float {
    val aLuminance = a.luminance().toDouble()
    val bLuminance = b.luminance().toDouble()
    return ((maxOf(aLuminance, bLuminance) + 0.05) / (minOf(aLuminance, bLuminance) + 0.05)).toFloat()
}
