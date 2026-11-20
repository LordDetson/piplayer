package io.github.lorddetson.piplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.lorddetson.piplayer.settings.ThemeMode

internal fun builtInColorScheme(mode: ThemeMode): ColorScheme? = when (mode) {
    ThemeMode.AMOLED -> amoled
    ThemeMode.GRAPHITE -> graphite
    ThemeMode.WARM_LIGHT -> warmLight
    ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK -> null
}

private val amoled = darkColorScheme(
    primary = Color(0xFFD4BDFF),
    onPrimary = Color(0xFF30204F),
    primaryContainer = Color(0xFF49326C),
    onPrimaryContainer = Color(0xFFEDDDFF),
    secondary = Color(0xFFC8C8EA),
    onSecondary = Color(0xFF302F47),
    secondaryContainer = Color(0xFF45425D),
    onSecondaryContainer = Color(0xFFE5E1FE),
    tertiary = Color(0xFFF0B7E4),
    onTertiary = Color(0xFF47243F),
    tertiaryContainer = Color(0xFF602E53),
    onTertiaryContainer = Color(0xFFFFD8F2),
    background = Color.Black,
    onBackground = Color(0xFFF1EEF4),
    surface = Color.Black,
    onSurface = Color(0xFFF1EEF4),
    surfaceVariant = Color(0xFF242129),
    onSurfaceVariant = Color(0xFFCFC7D4),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0C10),
    surfaceContainer = Color(0xFF151318),
    surfaceContainerHigh = Color(0xFF1D1B22),
    surfaceContainerHighest = Color(0xFF28252D),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF34313A),
    inverseSurface = Color(0xFFE9E3ED),
    inverseOnSurface = Color(0xFF312D36),
    inversePrimary = Color(0xFF684A93),
    outline = Color(0xFF9A909E),
    outlineVariant = Color(0xFF4B4651),
)

private val graphite = darkColorScheme(
    primary = Color(0xFFA4DDF4),
    onPrimary = Color(0xFF123547),
    primaryContainer = Color(0xFF254D60),
    onPrimaryContainer = Color(0xFFCBEDFF),
    secondary = Color(0xFFBBCDD8),
    onSecondary = Color(0xFF263640),
    secondaryContainer = Color(0xFF3C4D58),
    onSecondaryContainer = Color(0xFFD7E6EF),
    tertiary = Color(0xFFC2CBFA),
    onTertiary = Color(0xFF2D3659),
    tertiaryContainer = Color(0xFF434D73),
    onTertiaryContainer = Color(0xFFE0E5FF),
    background = Color(0xFF141C23),
    onBackground = Color(0xFFE8F0F6),
    surface = Color(0xFF141C23),
    onSurface = Color(0xFFE8F0F6),
    surfaceVariant = Color(0xFF2C3A44),
    onSurfaceVariant = Color(0xFFBDCCD7),
    surfaceContainerLowest = Color(0xFF10171D),
    surfaceContainerLow = Color(0xFF1A242D),
    surfaceContainer = Color(0xFF202C35),
    surfaceContainerHigh = Color(0xFF28353F),
    surfaceContainerHighest = Color(0xFF31404C),
    surfaceDim = Color(0xFF141C23),
    surfaceBright = Color(0xFF394853),
    inverseSurface = Color(0xFFDFE8EE),
    inverseOnSurface = Color(0xFF27343D),
    inversePrimary = Color(0xFF365E70),
    outline = Color(0xFF899BA8),
    outlineVariant = Color(0xFF485862),
)

private val warmLight = lightColorScheme(
    primary = Color(0xFF80501F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5D8B6),
    onPrimaryContainer = Color(0xFF422506),
    secondary = Color(0xFF6E5844),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2DDC7),
    onSecondaryContainer = Color(0xFF362719),
    tertiary = Color(0xFF5E6540),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1E7BF),
    onTertiaryContainer = Color(0xFF292F11),
    background = Color(0xFFFFF8EF),
    onBackground = Color(0xFF251A12),
    surface = Color(0xFFFFF8EF),
    onSurface = Color(0xFF251A12),
    surfaceVariant = Color(0xFFF0DFCC),
    onSurfaceVariant = Color(0xFF594738),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF2E3),
    surfaceContainer = Color(0xFFFAEBDA),
    surfaceContainerHigh = Color(0xFFF3E4D3),
    surfaceContainerHighest = Color(0xFFEDDECE),
    surfaceDim = Color(0xFFE7D9CB),
    surfaceBright = Color(0xFFFFF8EF),
    inverseSurface = Color(0xFF392D24),
    inverseOnSurface = Color(0xFFFFF0E3),
    inversePrimary = Color(0xFFF6BC80),
    outline = Color(0xFF846F5D),
    outlineVariant = Color(0xFFD6C3B0),
)
