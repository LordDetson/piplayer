package io.github.lorddetson.piplayer.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings

/** Preserves legacy shared choices while other built-in themes adopt their own defaults. */
internal object ThemeLayoutMigration : DataMigration<Preferences> {
    private val presentation = stringPreferencesKey("now_playing_layout")
    private val revision = longPreferencesKey("now_playing_layout_revision")
    private val tabOrder = stringPreferencesKey("library_tab_order")
    private val hiddenTabs = stringSetPreferencesKey("library_hidden_tabs")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        presentation in currentData || revision in currentData || tabOrder in currentData || hiddenTabs in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val builtIn = AppearanceSettings.builtInTheme(currentData)
        // Cover Focus and Control Deck already used their own defaults before the migration.
        val owner = when (builtIn) {
            ThemeMode.COVER_FOCUS, ThemeMode.CONTROL_DECK -> ThemeMode.SYSTEM
            else -> builtIn
        }
        val themes = listOf(owner.stored) + CustomThemesSettings.stateOf(currentData).themes.map(CustomTheme::id)
        val oldPresentation = currentData[presentation]?.let(NowPlayingLayout::fromStored)
        val oldTabs = if (tabOrder in currentData || hiddenTabs in currentData) {
            LibraryTabLayout.fromStored(currentData[tabOrder].orEmpty().split(','), currentData[hiddenTabs].orEmpty())
        } else null

        return currentData.toMutablePreferences().apply {
            for (themeId in themes) {
                val layoutKey = AppearanceSettings.themeLayoutKey(themeId)
                if (oldPresentation != null && layoutKey !in this) this[layoutKey] = oldPresentation.stored
                if (oldTabs != null && LibraryTabsSettings.savedPanel(this, themeId) == null) {
                    val panel = builtInThemeLayout(themeId)?.library ?: LibraryPanelLayout()
                    LibraryTabsSettings.storePanel(this, themeId, panel.copy(tabs = oldTabs))
                }
            }
            remove(presentation)
            remove(revision)
            remove(tabOrder)
            remove(hiddenTabs)
        }
    }

    override suspend fun cleanUp() = Unit
}
