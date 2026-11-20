package io.github.lorddetson.piplayer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomThemesSettingsTest {

    private val first = CustomTheme(
        name = "Late night",
        base = ThemeMode.GRAPHITE,
        palette = ThemePalette(accentRgb = 0x33CC55, backgroundRgb = 0, textRgb = 0x87BBC0, automaticText = false),
        font = ThemeFontSelection.BuiltIn("inter"),
        id = FIRST_ID,
    )
    private val second = CustomTheme(
        name = "Weekend",
        base = ThemeMode.WARM_LIGHT,
        palette = ThemePalette(surfaceRgb = 0xFFEECC),
        font = ThemeFontSelection.Imported("$FONT_ID.otf", "Old Town"),
        id = SECOND_ID,
    )

    @Test
    fun `older preferences have no custom theme and retain their ordinary appearance choices`() = runBlocking<Unit> {
        val old = mutablePreferencesOf(BUILT_IN to "dark", LAYOUT to "expanded")
        val file = MemoryDataStore(old)

        assertThat(CustomThemesSettings(file).state.first()).isEqualTo(CustomThemesState())
        assertThat(file.data.first()).isEqualTo(old)
    }

    @Test
    fun `named themes reopen with stable ids and independent choices and selection`() = runBlocking<Unit> {
        val file = MemoryDataStore(mutablePreferencesOf(BUILT_IN to "light", LAYOUT to "minimal"))
        val settings = CustomThemesSettings(file)

        assertThat(settings.save(first, select = true)).isEqualTo(CustomThemeWrite.STORED)
        assertThat(settings.save(second)).isEqualTo(CustomThemeWrite.STORED)
        val edited = first.copy(name = "Night", palette = first.palette.copy(automaticText = true))
        assertThat(settings.save(edited)).isEqualTo(CustomThemeWrite.STORED)
        val reopened = CustomThemesSettings(MemoryDataStore(file.data.first()))
        assertThat(reopened.state.first()).isEqualTo(CustomThemesState(listOf(edited, second), FIRST_ID))
        assertThat(reopened.state.first().selected).isEqualTo(edited)
        assertThat(file.data.first()[BUILT_IN]).isEqualTo("light")
        assertThat(file.data.first()[LAYOUT]).isEqualTo("minimal")

        assertThat(reopened.select(SECOND_ID)).isEqualTo(CustomThemeWrite.STORED)
        assertThat(reopened.state.first().selected).isEqualTo(second)
        assertThat(reopened.select(null)).isEqualTo(CustomThemeWrite.STORED)
        assertThat(reopened.state.first()).isEqualTo(CustomThemesState(listOf(edited, second)))
    }

    @Test
    fun `malformed entries and unknown fonts are safe and an unrelated edit preserves the original entries`() = runBlocking<Unit> {
        val raw = """{"$FIRST_ID":{"name":"From newer PiPlayer","base":"future-mode","font":{"type":"built_in","id":"future-font"},"future":true},"$SECOND_ID":17,"invalid-id":{"name":"Broken"}}"""
        val file = MemoryDataStore(mutablePreferencesOf(THEMES to raw, ACTIVE to SECOND_ID, BUILT_IN to "dark"))
        val settings = CustomThemesSettings(file)

        assertThat(settings.state.first()).isEqualTo(CustomThemesState(listOf(CustomTheme(name = "From newer PiPlayer", id = FIRST_ID))))
        assertThat(settings.select(FIRST_ID)).isEqualTo(CustomThemeWrite.STORED)
        assertThat(file.data.first()[THEMES]).isEqualTo(raw)
        assertThat(settings.save(second)).isEqualTo(CustomThemeWrite.STORED)
        assertThat(file.data.first()[THEMES]).contains("future-font", "\"future\":true", "invalid-id")
        assertThat(settings.state.first().selected?.id).isEqualTo(FIRST_ID)
        file.edit { it[THEMES] = "{broken" }
        assertThat(settings.state.first()).isEqualTo(CustomThemesState())
        assertThat(file.data.first()[BUILT_IN]).isEqualTo("dark")
    }

    @Test
    fun `rejected and failed saves leave the selected theme and stored entries unchanged`() = runBlocking<Unit> {
        val file = MemoryDataStore()
        val settings = CustomThemesSettings(file)
        assertThat(settings.save(first, select = true)).isEqualTo(CustomThemeWrite.STORED)
        val before = file.data.first()

        assertThat(settings.save(second.copy(name = " "), select = true)).isEqualTo(CustomThemeWrite.INVALID_THEME)
        assertThat(settings.save(second.copy(font = ThemeFontSelection.Imported("../file.ttf", "File")), select = true))
            .isEqualTo(CustomThemeWrite.INVALID_THEME)
        assertThat(settings.save(second.copy(font = ThemeFontSelection.BuiltIn("unknown")), select = true))
            .isEqualTo(CustomThemeWrite.INVALID_THEME)
        assertThat(settings.select(SECOND_ID)).isEqualTo(CustomThemeWrite.UNKNOWN_THEME)

        val failingFile = object : DataStore<Preferences> {
            override val data = file.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                transform(data.first())
                throw IOException("Simulated failure to commit")
            }
        }
        assertThat(CustomThemesSettings(failingFile).save(second, select = true)).isEqualTo(CustomThemeWrite.WRITE_FAILED)
        assertThat(settings.state.first().selected).isEqualTo(first)
        assertThat(file.data.first()).isEqualTo(before)
    }

    @Test
    fun `two concurrent saves keep both themes and the chosen selection`() = runBlocking<Unit> {
        val settings = CustomThemesSettings(MemoryDataStore())
        val a = async(Dispatchers.Default) { settings.save(first, select = true) }
        val b = async(Dispatchers.Default) { settings.save(second) }

        assertThat(a.await()).isEqualTo(CustomThemeWrite.STORED)
        assertThat(b.await()).isEqualTo(CustomThemeWrite.STORED)
        assertThat(settings.state.first().themes).containsExactlyInAnyOrder(first, second)
        assertThat(settings.state.first().selected).isEqualTo(first)
    }

    private companion object {
        const val FIRST_ID = "9317446c-8272-4f6a-92be-ba8e7b249468"
        const val SECOND_ID = "833718f7-d2ef-4586-a2a2-d8db37637ca2"
        const val FONT_ID = "ab38bb49-bdf2-404d-8b1f-6694c2ab4108"
        val THEMES = stringPreferencesKey("custom_themes")
        val ACTIVE = stringPreferencesKey("selected_custom_theme_id")
        val BUILT_IN = stringPreferencesKey("theme_mode")
        val LAYOUT = stringPreferencesKey("now_playing_layout")
    }
}
