package io.github.lorddetson.piplayer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AppearanceSettingsTest {

    private val imported = CustomTheme(
        name = "Night stage",
        base = ThemeMode.AMOLED,
        palette = ThemePalette(accentRgb = 0x90C0E0, backgroundRgb = 0, textRgb = 0xEEEEEE, automaticText = false),
        font = ThemeFontSelection.BuiltIn("inter"),
    )
    private val previous = CustomTheme(name = "Warm daylight", base = ThemeMode.WARM_LIGHT)

    @Test
    fun `apply stores and activates a custom theme with its layout in one commit`() = runBlocking<Unit> {
        val file = RecordingStore()
        val settings = AppearanceSettings(file)

        assertThat(settings.applyCustomTheme(imported, NowPlayingLayout.MINIMAL)).isInstanceOf(ThemeApplyResult.Applied::class.java)

        assertThat(file.commits).hasSize(1)
        val reopened = AppearanceSettings(MemoryDataStore(file.data.first()))
        assertThat(reopened.read()).isEqualTo(Appearance(ThemeMode.AMOLED, NowPlayingLayout.MINIMAL, imported))
        assertThat(reopened.read().customTheme?.name).isEqualTo("Night stage")
        assertThat(reopened.read().customTheme?.palette).isEqualTo(imported.palette)
        assertThat(file.data.first()[BUILT_IN]).isEqualTo("dark")
        assertThat(CustomThemesSettings.stateOf(file.data.first()).themes).containsExactly(imported)
    }

    @Test
    fun `an imported theme without a layout keeps the current layout`() = runBlocking<Unit> {
        val file = RecordingStore()
        val settings = AppearanceSettings(file)

        assertThat(settings.applyCustomTheme(imported)).isInstanceOf(ThemeApplyResult.Applied::class.java)

        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.AMOLED, NowPlayingLayout.EXPANDED, imported))
        assertThat(file.commits.single()[LAYOUT]).isEqualTo("expanded")
    }

    @Test
    fun `choosing a built in theme clears the custom selection in one commit`() = runBlocking<Unit> {
        val file = RecordingStore()
        assertThat(CustomThemesSettings(file).save(previous, select = true)).isEqualTo(CustomThemeWrite.STORED)
        val settings = AppearanceSettings(file)
        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.WARM_LIGHT, NowPlayingLayout.EXPANDED, previous))
        file.commits.clear()

        settings.setTheme(ThemeMode.GRAPHITE)

        val committed = file.commits.single()
        assertThat(committed[BUILT_IN]).isEqualTo("graphite")
        assertThat(CustomThemesSettings.stateOf(committed)).isEqualTo(CustomThemesState(listOf(previous)))
        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.GRAPHITE, NowPlayingLayout.EXPANDED))
    }

    @Test
    fun `undo restores the previous custom theme and layout together`() = runBlocking<Unit> {
        val file = RecordingStore()
        val settings = AppearanceSettings(file)
        assertThat(CustomThemesSettings(file).save(previous, select = true)).isEqualTo(CustomThemeWrite.STORED)
        val applied = settings.applyCustomTheme(imported, NowPlayingLayout.MINIMAL) as ThemeApplyResult.Applied
        file.commits.clear()

        assertThat(settings.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)

        assertThat(file.commits).hasSize(1)
        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.WARM_LIGHT, NowPlayingLayout.EXPANDED, previous))
    }

    @Test
    fun `undo keeps a later manually selected layout`() = runBlocking<Unit> {
        val settings = AppearanceSettings(RecordingStore())
        val applied = settings.applyCustomTheme(imported, NowPlayingLayout.MINIMAL) as ThemeApplyResult.Applied
        settings.setNowPlayingLayout(NowPlayingLayout.STANDARD)

        assertThat(settings.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)

        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.DARK, NowPlayingLayout.STANDARD))
    }

    @Test
    fun `undo keeps the imported layout when the user changes it and then reselects it`() = runBlocking<Unit> {
        val settings = AppearanceSettings(RecordingStore())
        settings.setNowPlayingLayout(NowPlayingLayout.MINIMAL)
        val applied = settings.applyCustomTheme(imported, NowPlayingLayout.EXPANDED) as ThemeApplyResult.Applied
        settings.setNowPlayingLayout(NowPlayingLayout.STANDARD)
        settings.setNowPlayingLayout(NowPlayingLayout.EXPANDED)

        assertThat(settings.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)

        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.DARK, NowPlayingLayout.EXPANDED))
    }

    @Test
    fun `undo keeps a later manually selected theme`() = runBlocking<Unit> {
        val settings = AppearanceSettings(RecordingStore())
        val applied = settings.applyCustomTheme(imported, NowPlayingLayout.MINIMAL) as ThemeApplyResult.Applied
        settings.setTheme(ThemeMode.GRAPHITE)

        assertThat(settings.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)

        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.GRAPHITE, NowPlayingLayout.EXPANDED))
    }

    @Test
    fun `an invalid theme or failed transaction leaves the active appearance intact`() = runBlocking<Unit> {
        val file = RecordingStore()
        val settings = AppearanceSettings(file)
        val before = file.data.first()

        assertThat(settings.applyCustomTheme(imported.copy(name = ""), NowPlayingLayout.MINIMAL)).isEqualTo(ThemeApplyResult.InvalidTheme)
        assertThat(file.commits).isEmpty()
        val failingFile = object : DataStore<Preferences> {
            override val data = file.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                transform(data.first())
                throw IOException("Simulated commit failure")
            }
        }
        assertThat(AppearanceSettings(failingFile).applyCustomTheme(imported, NowPlayingLayout.MINIMAL)).isEqualTo(ThemeApplyResult.WriteFailed)

        assertThat(file.data.first()).isEqualTo(before)
        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.DARK, NowPlayingLayout.EXPANDED))
    }

    private class RecordingStore : DataStore<Preferences> {
        private val delegate = MemoryDataStore(mutablePreferencesOf(BUILT_IN to "dark", LAYOUT to "expanded"))
        override val data = delegate.data
        val commits = mutableListOf<Preferences>()

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData(transform).also { commits += it }
    }

    private companion object {
        val BUILT_IN = stringPreferencesKey("theme_mode")
        val LAYOUT = stringPreferencesKey("now_playing_layout")
    }
}
