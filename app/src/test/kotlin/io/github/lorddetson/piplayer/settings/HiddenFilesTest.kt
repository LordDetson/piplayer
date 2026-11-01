package io.github.lorddetson.piplayer.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HiddenFilesTest {

    @Test
    fun `by default system sounds, recordings and files under 10 s are hidden`() {
        assertThat(HiddenFiles.from(emptyPreferences())).isEqualTo(HiddenFiles(false, false, 10))
        assertThat(HiddenFiles().minLengthMs).isEqualTo(10_000)
    }

    @Test
    fun `stored choices come back`() {
        val preferences = preferencesOf(
            booleanPreferencesKey("show_system_sounds") to true,
            booleanPreferencesKey("show_recordings") to true,
            intPreferencesKey("min_length_s") to 0,
        )

        assertThat(HiddenFiles.from(preferences)).isEqualTo(HiddenFiles(true, true, 0))
    }

    @Test
    fun `a stored length the setting does not offer falls back to the default`() {
        assertThat(HiddenFiles.from(preferencesOf(intPreferencesKey("min_length_s") to 7)).minLengthSeconds).isEqualTo(10)
    }
}
