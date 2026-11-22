package io.github.lorddetson.piplayer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
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
    fun `each built in theme has a distinct layout for all four panels`() = runBlocking<Unit> {
        val settings = AppearanceSettings(MemoryDataStore())
        val themes = ThemeMode.entries.map { theme ->
            settings.setTheme(theme)
            settings.exportThemePackage { it.stored }
        }

        assertThat(themes.map { it.panel?.tabs?.visible }).doesNotHaveDuplicates()
        assertThat(themes.map { it.panel }).doesNotHaveDuplicates()
        assertThat(themes.map { it.player }).doesNotHaveDuplicates()
        assertThat(themes.map { it.miniPlayer }).doesNotHaveDuplicates()
        assertThat(themes.map { it.navigation?.visible }).doesNotHaveDuplicates()
        for (theme in themes) {
            val encoded = ThemePackageCodec.encode(theme)
            assertThat(ThemePackageCodec.decode(encoded.inputStream())).isEqualTo(ThemePackageRead.Loaded(theme))
        }
    }

    @Test
    fun `two built in themes independently retain edits to all panels after reopening`() = runBlocking<Unit> {
        val file = MemoryDataStore()
        val settings = AppearanceSettings(file)
        val playerSettings = nowPlayingPanels.Settings(file)
        val miniSettings = miniPlayerPanels.Settings(file)
        val navigationSettings = bottomNavigationLayouts.Settings(file)
        val library = LibraryTabsSettings(file)
        val graphite = ThemeMode.GRAPHITE.stored
        val warm = ThemeMode.WARM_LIGHT.stored

        settings.setTheme(ThemeMode.WARM_LIGHT)
        val warmDefault = settings.exportThemePackage { it.stored }
        settings.setTheme(ThemeMode.GRAPHITE)
        settings.setNowPlayingLayout(NowPlayingLayout.MINIMAL)
        library.setVisible("artists", false, graphite)
        library.setButtonVisible("search", false, graphite)
        playerSettings.edit(graphite) { it.copy(hidden = it.hidden + NowPlayingElement.FAVORITE) }
        miniSettings.edit(graphite) { it.copy(hidden = it.hidden + MiniPlayerElement.COVER) }
        navigationSettings.edit(graphite) { it.hide(BottomDestination.SEARCH) }
        val graphiteEdited = settings.exportThemePackage { it.stored }

        settings.setTheme(ThemeMode.WARM_LIGHT)
        settings.setNowPlayingLayout(NowPlayingLayout.MINIMAL, themeId = graphite)
        assertThat(settings.exportThemePackage { it.stored }).isEqualTo(warmDefault)
        settings.setNowPlayingLayout(NowPlayingLayout.EXPANDED)
        library.move("playlists", 0)
        library.setButtonVisible("settings", false, warm)
        playerSettings.edit(warm) { it.copy(hidden = it.hidden + NowPlayingElement.COVER) }
        miniSettings.edit(warm) { it.copy(hidden = it.hidden + MiniPlayerElement.TRACK_INFO) }
        navigationSettings.edit(warm) { it.hide(BottomDestination.FIX) }
        val warmEdited = settings.exportThemePackage { it.stored }

        val reopened = AppearanceSettings(MemoryDataStore(file.data.first()))
        assertThat(reopened.exportThemePackage { it.stored }).isEqualTo(warmEdited)
        assertThat(warmEdited.layout).isEqualTo(NowPlayingLayout.EXPANDED)
        assertThat(warmEdited.panel?.tabs?.visible?.first()).isEqualTo("playlists")
        assertThat(warmEdited.panel?.buttons?.hidden).contains("settings")
        assertThat(warmEdited.player?.hidden).contains(NowPlayingElement.COVER)
        assertThat(warmEdited.miniPlayer?.hidden).contains(MiniPlayerElement.TRACK_INFO)
        assertThat(warmEdited.navigation?.hidden).contains(BottomDestination.FIX)
        reopened.setTheme(ThemeMode.GRAPHITE)
        assertThat(reopened.exportThemePackage { it.stored }).isEqualTo(graphiteEdited)
        assertThat(graphiteEdited.layout).isEqualTo(NowPlayingLayout.MINIMAL)
        assertThat(graphiteEdited.panel?.tabs?.hidden).contains("artists")
        assertThat(graphiteEdited.panel?.buttons?.hidden).contains("search")
        assertThat(graphiteEdited.player?.hidden).contains(NowPlayingElement.FAVORITE)
        assertThat(graphiteEdited.miniPlayer?.hidden).contains(MiniPlayerElement.COVER)
        assertThat(graphiteEdited.navigation?.hidden).contains(BottomDestination.SEARCH)
    }

    @Test
    fun `the ready themes change all panels together and remember local edits`() = runBlocking<Unit> {
        val file = RecordingStore()
        val settings = AppearanceSettings(file)
        val cover = ThemeMode.fromStored("cover_focus")
        val console = ThemeMode.fromStored("control_deck")
        assertThat(cover.stored).isEqualTo("cover_focus")
        assertThat(console.stored).isEqualTo("control_deck")

        settings.setTheme(cover)
        val first = settings.exportThemePackage { it.stored }
        assertThat(first.layout).isEqualTo(NowPlayingLayout.MINIMAL)
        assertThat(first.panel?.tabs?.visible).containsExactly("albums", "songs", "artists", "playlists")
        assertThat(first.panel?.buttons?.visible).containsExactly("search", "settings")
        assertThat(first.player?.get(NowPlayingRow.TOP)?.first()).isEqualTo(NowPlayingElement.FAVORITE)
        assertThat(first.player?.hidden).contains(NowPlayingElement.FORMAT)
        assertThat(first.miniPlayer?.progressAtBottom).isTrue()
        assertThat(first.navigation?.visible).containsExactly(BottomDestination.LIBRARY, BottomDestination.SEARCH)

        settings.setTheme(console)
        val second = settings.exportThemePackage { it.stored }
        assertThat(second.layout).isEqualTo(NowPlayingLayout.EXPANDED)
        assertThat(second.panel?.tabs?.visible).containsExactly("songs", "folders", "playlists", "albums", "artists", "genres")
        assertThat(second.panel?.buttons?.visible).containsExactly("sort", "search", "settings")
        assertThat(second.player?.get(NowPlayingRow.CONTROLS)).containsSubsequence(NowPlayingElement.NEXT, NowPlayingElement.QUEUE)
        assertThat(second.miniPlayer?.visible?.first()).isEqualTo(MiniPlayerElement.PLAY_PAUSE)
        assertThat(second.navigation?.visible).containsExactly(BottomDestination.LIBRARY, BottomDestination.FIX, BottomDestination.SEARCH)
        assertThat(ThemePackageCodec.decode(ThemePackageCodec.encode(second).inputStream())).isEqualTo(ThemePackageRead.Loaded(second))

        file.updateData { preferences ->
            preferences.toMutablePreferences().also { changed ->
                bottomNavigationLayouts.storePanel(changed, console.stored, checkNotNull(second.navigation).hide(BottomDestination.SEARCH))
            }
        }
        settings.setNowPlayingLayout(NowPlayingLayout.STANDARD)
        settings.setTheme(cover)
        assertThat(settings.exportThemePackage { it.stored }).isEqualTo(first)
        settings.setTheme(ThemeMode.DARK)
        assertThat(settings.read().nowPlayingLayout).isEqualTo(NowPlayingLayout.EXPANDED)
        settings.setTheme(console)
        assertThat(settings.read().nowPlayingLayout).isEqualTo(NowPlayingLayout.STANDARD)
        assertThat(settings.exportThemePackage { it.stored }.navigation?.hidden).containsExactly(BottomDestination.SEARCH)
    }

    @Test
    fun `an older import and undo preserve the ready theme and the original layout preference`() = runBlocking<Unit> {
        val file = RecordingStore(nowPlaying = NowPlayingLayout.STANDARD)
        val settings = AppearanceSettings(file)
        val cover = ThemeMode.fromStored("cover_focus")
        settings.setTheme(cover)
        val before = settings.exportThemePackage { it.stored }
        val applied = settings.applyCustomTheme(imported) as ThemeApplyResult.Applied
        val after = settings.exportThemePackage { it.stored }
        assertThat(after.layout).isEqualTo(NowPlayingLayout.MINIMAL)
        assertThat(after.copy(name = before.name, base = before.base, palette = before.palette)).isEqualTo(before)
        assertThat(settings.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)
        assertThat(settings.exportThemePackage { it.stored }).isEqualTo(before)
        settings.setTheme(ThemeMode.DARK)
        assertThat(settings.read().nowPlayingLayout).isEqualTo(NowPlayingLayout.STANDARD)
    }

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
        assertThat(file.commits).hasSize(1)
    }

    @Test
    fun `choosing a built in theme clears the custom selection in one commit`() = runBlocking<Unit> {
        val file = RecordingStore()
        val settings = AppearanceSettings(file)
        assertThat(settings.applyCustomTheme(previous, NowPlayingLayout.EXPANDED)).isInstanceOf(ThemeApplyResult.Applied::class.java)
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
        assertThat(settings.applyCustomTheme(previous, NowPlayingLayout.EXPANDED)).isInstanceOf(ThemeApplyResult.Applied::class.java)
        val applied = settings.applyCustomTheme(imported, NowPlayingLayout.MINIMAL) as ThemeApplyResult.Applied
        file.commits.clear()

        assertThat(settings.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)

        assertThat(file.commits).hasSize(1)
        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.WARM_LIGHT, NowPlayingLayout.EXPANDED, previous))
    }

    @Test
    fun `undo restores the earlier theme and keeps edits with the imported theme`() = runBlocking<Unit> {
        val file = RecordingStore()
        val settings = AppearanceSettings(file)
        val applied = settings.applyCustomTheme(imported, NowPlayingLayout.MINIMAL) as ThemeApplyResult.Applied
        settings.setNowPlayingLayout(NowPlayingLayout.STANDARD)

        assertThat(settings.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)

        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.DARK, NowPlayingLayout.EXPANDED))
        assertThat(CustomThemesSettings(file).select(imported.id)).isEqualTo(CustomThemeWrite.STORED)
        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.AMOLED, NowPlayingLayout.STANDARD, imported))
    }

    @Test
    fun `undo keeps the imported layout when the user changes it and then reselects it`() = runBlocking<Unit> {
        val file = RecordingStore()
        val settings = AppearanceSettings(file)
        settings.setNowPlayingLayout(NowPlayingLayout.MINIMAL)
        val applied = settings.applyCustomTheme(imported, NowPlayingLayout.EXPANDED) as ThemeApplyResult.Applied
        settings.setNowPlayingLayout(NowPlayingLayout.STANDARD)
        settings.setNowPlayingLayout(NowPlayingLayout.EXPANDED)

        assertThat(settings.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)

        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.DARK, NowPlayingLayout.MINIMAL))
        assertThat(CustomThemesSettings(file).select(imported.id)).isEqualTo(CustomThemeWrite.STORED)
        assertThat(settings.read()).isEqualTo(Appearance(ThemeMode.AMOLED, NowPlayingLayout.EXPANDED, imported))
    }

    @Test
    fun `undo keeps a later manually selected theme`() = runBlocking<Unit> {
        val settings = AppearanceSettings(RecordingStore())
        val applied = settings.applyCustomTheme(imported, NowPlayingLayout.MINIMAL) as ThemeApplyResult.Applied
        settings.setTheme(ThemeMode.GRAPHITE)

        assertThat(settings.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.STALE)

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

    private class RecordingStore(nowPlaying: NowPlayingLayout = NowPlayingLayout.EXPANDED) : DataStore<Preferences> {
        private val delegate = MemoryDataStore(mutablePreferencesOf(
            BUILT_IN to ThemeMode.DARK.stored,
            stringPreferencesKey("now_playing_presentation_dark") to nowPlaying.stored,
        ))
        override val data = delegate.data
        val commits = mutableListOf<Preferences>()

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData(transform).also { commits += it }
    }

    private companion object {
        val BUILT_IN = stringPreferencesKey("theme_mode")
    }
}
