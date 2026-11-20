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

    @Test
    fun `each preset round trips and keeps its own light or dark appearance`() {
        val modes = mapOf("amoled" to true, "graphite" to true, "warm_light" to false)
        for ((stored, dark) in modes) {
            val mode = ThemeMode.fromStored(stored)
            assertThat(mode.stored).isEqualTo(stored)
            assertThat(mode.isDark(systemDark = false)).isEqualTo(dark)
            assertThat(mode.isDark(systemDark = true)).isEqualTo(dark)
        }
    }
}
