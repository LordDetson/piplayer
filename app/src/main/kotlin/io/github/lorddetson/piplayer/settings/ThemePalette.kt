package io.github.lorddetson.piplayer.settings

import kotlinx.serialization.Serializable

/** Optional opaque sRGB colors (0xRRGGBB) over the selected theme. Missing colors inherit from that theme. */
@Serializable
data class ThemePalette(
    val accentRgb: Int? = null,
    val backgroundRgb: Int? = null,
    val surfaceRgb: Int? = null,
    val textRgb: Int? = null,
    val automaticText: Boolean = true,
) {
    internal val hasValidColors: Boolean
        get() = listOfNotNull(accentRgb, backgroundRgb, surfaceRgb, textRgb).all { it in 0..0xFFFFFF }
}
