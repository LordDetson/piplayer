package io.github.lorddetson.piplayer.settings.configuration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.settings.AppLanguage
import io.github.lorddetson.piplayer.settings.AppLanguageChange
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.CustomTheme
import io.github.lorddetson.piplayer.settings.CustomThemesSettings
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.MemoryDataStore
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.ThemeApplyResult
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.UiCanvasBounds
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiThemeCanvases
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigurationStoreTest {
    private val file = FailingFile()
    private val locale = FakeLocale()
    private val store = ConfigurationStore(file, locale, { "installation" })
    private val unrelated = booleanPreferencesKey("user_device_choice")
    private val encoding = stringPreferencesKey("legacy_encoding")

    @Test
    fun `apply and undo change only touched settings and preserve prior raw values`() = runBlocking<Unit> {
        file.edit { it[encoding] = "old-encoding" }
        val preview = ready(ConfigurationPatch(library = LibraryPatch(showSystemSounds = true, legacyEncodingId = "windows-1252")))
        assertThat(preview.changes.map { it.section }).containsOnly(ConfigurationSection.LIBRARY)
        file.edit { it[unrelated] = true }
        val attemptsBeforeApply = file.attempts
        val applied = store.apply(preview) as ConfigurationWriteResult.Applied
        assertThat(file.attempts).isEqualTo(attemptsBeforeApply + 1)
        assertThat(file.data.first()[HiddenFiles.SHOW_SYSTEM_SOUNDS]).isTrue()
        assertThat(file.data.first()[encoding]).isEqualTo("windows-1252")
        file.edit { it[HiddenFiles.SHOW_RECORDINGS] = true }
        assertThat(store.undo(applied.undo)).isInstanceOf(ConfigurationWriteResult.Restored::class.java)
        assertThat(file.data.first()[HiddenFiles.SHOW_SYSTEM_SOUNDS]).isNull()
        assertThat(file.data.first()[encoding]).isEqualTo("old-encoding")
        assertThat(file.data.first()[HiddenFiles.SHOW_RECORDINGS]).isTrue()
        assertThat(file.data.first()[unrelated]).isTrue()
    }

    @Test
    fun `apply and undo reject a newer write to a touched setting`() = runBlocking<Unit> {
        val patch = ConfigurationPatch(library = LibraryPatch(showSystemSounds = true))
        val stale = ready(patch)
        file.edit { it[HiddenFiles.SHOW_SYSTEM_SOUNDS] = true }
        assertThat(store.apply(stale)).isEqualTo(ConfigurationWriteResult.Stale)
        file.edit { it.remove(HiddenFiles.SHOW_SYSTEM_SOUNDS) }
        val applied = store.apply(ready(patch)) as ConfigurationWriteResult.Applied
        file.edit { it[HiddenFiles.SHOW_SYSTEM_SOUNDS] = false }
        assertThat(store.undo(applied.undo)).isEqualTo(ConfigurationWriteResult.Stale)
        assertThat(file.data.first()[HiddenFiles.SHOW_SYSTEM_SOUNDS]).isFalse()
    }

    @Test
    fun `settings-only failure keeps every prior value`() = runBlocking<Unit> {
        val preview = ready(mixedPatch().copy(language = null))
        file.failAt = 1
        assertThat(store.apply(preview)).isEqualTo(ConfigurationWriteResult.WriteFailed)
        assertThat(file.data.first().asMap()).isEmpty()
        assertThat(locale.writes).isEmpty()
    }

    @Test
    fun `locale is restored on settings write failure and successful retry can be undone exactly`() = runBlocking<Unit> {
        val preview = ready(mixedPatch())
        file.failAt = 2
        assertThat(store.apply(preview)).isEqualTo(ConfigurationWriteResult.WriteFailed)
        assertThat(locale.raw).isEqualTo("en-GB,fr-CA")
        assertThat(file.data.first().asMap()).isEmpty()
        val applied = store.apply(ready(mixedPatch())) as ConfigurationWriteResult.Applied
        assertThat(applied.languageChange).isEqualTo(AppLanguageChange.APPLIED)
        assertThat(locale.raw).isEqualTo("ru")
        assertThat(store.undo(applied.undo)).isInstanceOf(ConfigurationWriteResult.Restored::class.java)
        assertThat(locale.raw).isEqualTo("en-GB,fr-CA")
        assertThat(file.data.first().asMap()).isEmpty()
    }

    @Test
    fun `interruption after native write is recovered on reopening`() = runBlocking<Unit> {
        locale.interruptOnce = true
        try {
            store.apply(ready(mixedPatch()))
        } catch (_: InterruptedByProcessDeath) {
            // A process can disappear without executing normal cleanup.
        }
        assertThat(locale.raw).isEqualTo("ru")
        assertThat(file.data.first()[HiddenFiles.SHOW_SYSTEM_SOUNDS]).isNull()
        assertThat(store.preview(ConfigurationPatch())).isEqualTo(ConfigurationPreviewResult.Unreadable)
        assertThat(locale.raw).isEqualTo("ru")
        ConfigurationStore(file, locale, { "installation" }).recover()
        assertThat(locale.raw).isEqualTo("en-GB,fr-CA")
        assertThat(file.data.first().asMap()).isEmpty()
    }

    @Test
    fun `restart never overwrites a newer platform choice or a different installation`() = runBlocking<Unit> {
        locale.interruptOnce = true
        try {
            store.apply(ready(mixedPatch()))
        } catch (_: InterruptedByProcessDeath) {
            // The journal remains durable.
        }
        locale.raw = "de"
        store.recover()
        assertThat(locale.raw).isEqualTo("de")
        assertThat(file.data.first().asMap()).isEmpty()

        locale.interruptOnce = true
        try {
            store.apply(ready(mixedPatch()))
        } catch (_: InterruptedByProcessDeath) {
            // A restored journal is not evidence of a write on this installation.
        }
        ConfigurationStore(file, locale, { "another-installation" }).recover()
        assertThat(locale.raw).isEqualTo("ru")
        assertThat(file.data.first().asMap()).isEmpty()
    }

    @Test
    fun `locale changes outside the app make a preview stale even with the same effective language`() = runBlocking<Unit> {
        val preview = ready(mixedPatch())
        locale.raw = "en-AU,fr-CA"
        assertThat(store.apply(preview)).isEqualTo(ConfigurationWriteResult.Stale)
        assertThat(locale.raw).isEqualTo("en-AU,fr-CA")
        assertThat(file.data.first().asMap()).isEmpty()
    }

    @Test
    fun `failure to read back the requested locale preserves a newer platform choice`() = runBlocking<Unit> {
        locale.overrideOnWrite = "de"
        assertThat(store.apply(ready(mixedPatch()))).isEqualTo(ConfigurationWriteResult.Stale)
        assertThat(locale.raw).isEqualTo("de")
        assertThat(file.data.first().asMap()).isEmpty()
    }

    @Test
    fun `cancellation during the staged transaction still leaves a coherent settings and locale pair`() = runBlocking<Unit> {
        val preview = ready(mixedPatch())
        val caller = kotlinx.coroutines.Job()
        locale.onWrite = { caller.cancel() }
        try {
            kotlinx.coroutines.withContext(caller) { store.apply(preview) }
        } catch (_: CancellationException) {
            // Navigation may cancel the caller while disk writes are under way.
        }
        assertThat(locale.raw).isEqualTo("ru")
        assertThat(file.data.first()[HiddenFiles.SHOW_SYSTEM_SOUNDS]).isTrue()
        assertThat(file.data.first().asMap()).hasSize(1)
    }

    @Test
    fun `empty preview does not write and a clean restart leaves settings untouched`() = runBlocking<Unit> {
        assertThat(store.apply(ready(ConfigurationPatch()))).isEqualTo(ConfigurationWriteResult.NoChanges)
        store.recover()
        assertThat(file.attempts).isZero()
        assertThat(locale.writes).isEmpty()
    }

    @Test
    fun `an exported effective default does not become an edit or a stale dependency`() = runBlocking<Unit> {
        val patch = generalConfiguration(file.data.first())
        val preview = ready(patch)
        assertThat(preview.changes).isEmpty()
        file.edit { it[HiddenFiles.SHOW_RECORDINGS] = true }
        assertThat(store.apply(preview)).isEqualTo(ConfigurationWriteResult.NoChanges)
        assertThat(file.data.first().asMap()).hasSize(1)
    }

    @Test
    fun `apply and undo preserve a concurrently edited theme in the same raw preferences entry`() = runBlocking<Unit> {
        val themes = CustomThemesSettings(file)
        val first = CustomTheme("First", id = "894eb315-cdf3-4f54-9afe-482d55be9b97")
        val second = CustomTheme("Second", id = "fb87d4c5-709b-47d7-9adf-a13f924e09a8")
        themes.save(first)
        themes.save(second)
        val patch = ConfigurationPatch(appearance = AppearancePatch(
            savedThemes = mapOf(first.id to SavedThemePatch(name = "Imported")),
        ))
        val preview = ready(patch)
        themes.save(second.copy(name = "Edited before Apply"))
        val result = store.apply(preview) as ConfigurationWriteResult.Applied
        assertThat(themes.state.first().themes.associate { it.id to it.name })
            .containsEntry(first.id, "Imported").containsEntry(second.id, "Edited before Apply")
        themes.save(second.copy(name = "Edited before Undo"))
        assertThat(store.undo(result.undo)).isInstanceOf(ConfigurationWriteResult.Restored::class.java)
        assertThat(themes.state.first().themes.associate { it.id to it.name })
            .containsEntry(first.id, "First").containsEntry(second.id, "Edited before Undo")
    }

    @Test
    fun `undo keeps an added theme that the user selected afterward`() = runBlocking<Unit> {
        val theme = CustomTheme("New")
        val applied = addTheme(theme)
        val themes = CustomThemesSettings(file)
        themes.select(theme.id)
        assertThat(store.undo(applied.undo)).isEqualTo(ConfigurationWriteResult.Stale)
        assertThat(themes.state.first().selectedId).isEqualTo(theme.id)
    }

    @Test
    fun `undo keeps an added theme that the user customized afterward`() = runBlocking<Unit> {
        val theme = CustomTheme("New")
        val applied = addTheme(theme)
        val layout = AppearanceSettings.themeLayoutKey(theme.id)
        file.edit { it[layout] = "minimal" }
        assertThat(store.undo(applied.undo)).isEqualTo(ConfigurationWriteResult.Stale)
        assertThat(file.data.first()[layout]).isEqualTo("minimal")
        assertThat(CustomThemesSettings(file).state.first().themes.map { it.id }).contains(theme.id)
    }

    @Test
    fun `a selected theme deleted after preview makes apply stale`() = runBlocking<Unit> {
        val theme = CustomTheme("Existing")
        val themes = CustomThemesSettings(file)
        themes.save(theme)
        val preview = ready(ConfigurationPatch(appearance = AppearancePatch(activeTheme = ActiveTheme.Custom(theme.id))))
        file.edit { CustomThemesSettings.storeThemeEntries(it, emptyMap()) }
        assertThat(store.apply(preview)).isEqualTo(ConfigurationWriteResult.Stale)
        assertThat(file.data.first()[CustomThemesSettings.SELECTED]).isNull()
    }

    @Test
    fun `undo restores exact legacy canvas and keeps a newer edit to a different theme`() = runBlocking<Unit> {
        val id = installLegacyTheme()
        val canvasKey = UiCanvasSettings.key(id)
        val original = file.data.first()[canvasKey]
        assertThat(original).contains("now:future_control")
        val result = clearNowPlayingCanvas(id)
        assertThat(file.data.first()[canvasKey]).isNull()

        val unrelatedTheme = CustomTheme("Unrelated")
        val themes = CustomThemesSettings(file)
        themes.save(unrelatedTheme)
        assertThat(store.undo(result.undo)).isInstanceOf(ConfigurationWriteResult.Restored::class.java)
        assertThat(file.data.first()[canvasKey]).isEqualTo(original)
        assertThat(themes.state.first().themes).contains(unrelatedTheme)
    }

    @Test
    fun `undo cannot restore a legacy canvas into a newer layout of the same theme`() = runBlocking<Unit> {
        val id = installLegacyTheme()
        val result = clearNowPlayingCanvas(id)
        val layout = AppearanceSettings.themeLayoutKey(id)
        file.edit { it[layout] = NowPlayingLayout.EXPANDED.stored }

        assertThat(store.undo(result.undo)).isEqualTo(ConfigurationWriteResult.Invalid)
        assertThat(file.data.first()[UiCanvasSettings.key(id)]).isNull()
        assertThat(file.data.first()[layout]).isEqualTo(NowPlayingLayout.EXPANDED.stored)
    }

    @Test
    fun `undo restores the exact canvas map present at apply after a concurrent surface edit`() = runBlocking<Unit> {
        assertUndoRestoresCanvasAtApply(language = null)
    }

    @Test
    fun `undo restores the canvas map and app locale without capturing the transaction journal`() = runBlocking<Unit> {
        assertUndoRestoresCanvasAtApply(language = AppLanguage.RUSSIAN)
    }

    private suspend fun assertUndoRestoresCanvasAtApply(language: AppLanguage?) {
        val id = installLegacyTheme()
        val canvasKey = UiCanvasSettings.key(id)
        val preview = ready(clearNowPlayingCanvasPatch(id).copy(language = language))
        file.edit { preferences ->
            val existing = UiCanvasSettings.savedLayouts(preferences, id)
            val library = UiCanvasLayout(listOf(UiCanvasItem("library:title", UiCanvasBounds(0, 0, 100, 100))))
            UiCanvasSettings.store(preferences, id, existing.copy(panels = existing.panels + (UiSurface.LIBRARY to library)))
        }
        val beforeApply = file.data.first()
        val exactRaw = checkNotNull(beforeApply[canvasKey])
        assertThat(exactRaw.indexOf("NOW_PLAYING")).isBetween(0, exactRaw.indexOf("LIBRARY") - 1)

        val result = store.apply(preview) as ConfigurationWriteResult.Applied
        assertThat(file.data.first()[canvasKey]).contains("LIBRARY").doesNotContain("NOW_PLAYING")
        val languageChange = if (language == null) AppLanguageChange.UNCHANGED else AppLanguageChange.APPLIED
        assertThat(result.languageChange).isEqualTo(languageChange)
        assertThat(store.undo(result.undo)).isEqualTo(ConfigurationWriteResult.Restored(languageChange))
        assertThat(file.data.first().asMap()).isEqualTo(beforeApply.asMap())
        assertThat(locale.raw).isEqualTo("en-GB,fr-CA")
    }

    private suspend fun installLegacyTheme(): String {
        val theme = CustomTheme("Legacy")
        val futureControl = UiCanvasItem("now:future_control", UiCanvasBounds(0, 0, 100, 100))
        val canvases = UiThemeCanvases(panels = mapOf(UiSurface.NOW_PLAYING to UiCanvasLayout(listOf(futureControl))))
        assertThat(AppearanceSettings(file).applyCustomTheme(theme, canvases = canvases))
            .isInstanceOf(ThemeApplyResult.Applied::class.java)
        return theme.id
    }

    private suspend fun clearNowPlayingCanvas(id: String): ConfigurationWriteResult.Applied =
        store.apply(ready(clearNowPlayingCanvasPatch(id))) as ConfigurationWriteResult.Applied

    private fun clearNowPlayingCanvasPatch(id: String): ConfigurationPatch {
        val layout = ThemeLayoutPatch(canvases = mapOf(UiSurface.NOW_PLAYING to Explicit(null)))
        return ConfigurationPatch(appearance = AppearancePatch(savedThemes = mapOf(id to SavedThemePatch(layout = layout))))
    }

    private suspend fun addTheme(theme: CustomTheme): ConfigurationWriteResult.Applied {
        val patch = ConfigurationPatch(appearance = AppearancePatch(
            savedThemes = mapOf(theme.id to SavedThemePatch(name = theme.name, base = ThemeMode.SYSTEM)),
        ))
        return store.apply(ready(patch)) as ConfigurationWriteResult.Applied
    }

    private suspend fun ready(patch: ConfigurationPatch): ConfigurationPreview =
        (store.preview(patch) as ConfigurationPreviewResult.Ready).preview

    private fun mixedPatch() = ConfigurationPatch(
        language = AppLanguage.RUSSIAN,
        library = LibraryPatch(showSystemSounds = true),
    )

    private class FakeLocale : ConfigurationLocale {
        override val backend = "platform"
        var raw: String? = "en-GB,fr-CA"
        var interruptOnce = false
        var overrideOnWrite: String? = null
        var onWrite: () -> Unit = {}
        val writes = mutableListOf<String?>()

        override fun read() = raw

        override fun write(value: String?): Boolean {
            writes += value
            raw = overrideOnWrite ?: value
            onWrite()
            if (interruptOnce) {
                interruptOnce = false
                throw InterruptedByProcessDeath()
            }
            return true
        }
    }

    private class InterruptedByProcessDeath : Error()

    private class FailingFile(private val delegate: DataStore<Preferences> = MemoryDataStore()) :
        DataStore<Preferences> by delegate {
        var failAt = 0
        var attempts = 0

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData {
                transform(it).also {
                    if (++attempts == failAt) throw IOException("Storage write failed")
                }
            }
    }
}
