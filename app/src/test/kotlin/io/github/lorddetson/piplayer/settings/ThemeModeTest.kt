package io.github.lorddetson.piplayer.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThemeModeTest {

    @Test
    fun `system follows the system, light and dark do not`() {
        assertThat(ThemeMode.SYSTEM.isDark(systemDark = true)).isTrue()
        assertThat(ThemeMode.SYSTEM.isDark(systemDark = false)).isFalse()
        assertThat(ThemeMode.DARK.isDark(systemDark = false)).isTrue()
        assertThat(ThemeMode.LIGHT.isDark(systemDark = true)).isFalse()
    }

    @Test
    fun `a stored value comes back as its mode, anything else as system`() {
        for (mode in ThemeMode.entries) assertThat(ThemeMode.fromStored(mode.stored)).isEqualTo(mode)
        assertThat(ThemeMode.fromStored(null)).isEqualTo(ThemeMode.SYSTEM)
        assertThat(ThemeMode.fromStored("sepia")).isEqualTo(ThemeMode.SYSTEM)
    }
}
