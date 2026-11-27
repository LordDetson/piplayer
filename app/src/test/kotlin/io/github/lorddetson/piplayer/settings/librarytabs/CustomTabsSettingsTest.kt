package io.github.lorddetson.piplayer.settings.librarytabs

import androidx.datastore.preferences.core.edit
import io.github.lorddetson.piplayer.library.customtabs.CustomTabSource
import io.github.lorddetson.piplayer.library.customtabs.CustomTabSourceDraft
import io.github.lorddetson.piplayer.library.customtabs.CustomTabTemplateSource
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.CustomTheme
import io.github.lorddetson.piplayer.settings.MemoryDataStore
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.UiCanvasBounds
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiLabelDisplay
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiThemeCanvases
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CustomTabsSettingsTest {
    private val file = MemoryDataStore()
    private val settings = CustomTabsSettings(file) { installation }
    private val layouts = LibraryTabsSettings(file)

    @Test
    fun `names and source edits retain id order and reject concurrent stale changes`() = runBlocking {
        assertEquals(CustomTabWrite.INVALID, settings.create("  ", playlist))
        assertEquals(CustomTabWrite.INVALID, settings.create("Unsafe", CustomTabSourceDraft.Collection(ListKind.SONGS)))
        assertEquals(CustomTabWrite.STORED, settings.create("  Listening  ", playlist))
        val original = settings.tabs.first().single()
        assertEquals("Listening", original.definition.name)
        assertEquals("tab:${original.definition.id}", original.definition.componentId)
        assertEquals(CustomTabSource.Playlist(12), original.resolvedSource)
        assertEquals(100L, original.playlistCreatedAt)
        val id = original.definition.id
        assertEquals(BuiltInLibraryTab.ids + id, layouts.layout.first().order)
        assertEquals(LibraryTabWrite.STORED, layouts.move(id, 0))
        settings.setSelected(id)

        assertEquals(CustomTabWrite.STORED, settings.edit(original, "Renamed"))
        assertEquals(CustomTabWrite.STALE, settings.edit(original, "Old editor"))
        val renamed = settings.tabs.first().single()
        assertEquals(original.definition.source, renamed.definition.source)
        assertEquals(CustomTabWrite.STORED, settings.edit(renamed, "Favorites", favorites))

        val reopened = CustomTabsSettings(MemoryDataStore(file.data.first())) { installation }
        val state = reopened.state.first()
        assertEquals(id, state.selectedId)
        assertEquals(id, state.panel.layout.tabs.order.first())
        assertEquals(id, state.tabs.single().definition.id)
        assertEquals(CustomTabSource.Collection(ListKind.FAVORITES), state.tabs.single().resolvedSource)
        assertEquals(CustomTabWrite.STALE, reopened.delete(renamed))
    }

    @Test
    fun `restored attachments fail closed and explicit rebind changes only one tab`() = runBlocking {
        settings.create("Playlist", playlist)
        settings.create("Folder", folder)
        settings.create("Stars", favorites)
        val original = settings.tabs.first()
        val restored = CustomTabsSettings(MemoryDataStore(file.data.first())) { restoredInstallation }
        val detached = restored.tabs.first().take(2)
        assertEquals(original.take(2).map { it.definition }, detached.map { it.definition })
        assertTrue(detached.all { it.resolvedSource == null })
        assertEquals(CustomTabSource.Collection(ListKind.FAVORITES), restored.tabs.first().last().resolvedSource)
        assertEquals(CustomTabWrite.INVALID, restored.rebind(detached.first(), folder))
        assertEquals(CustomTabWrite.STORED, restored.rebind(detached.first(), playlist.copy(id = 20, createdAt = 300)))
        assertEquals(CustomTabWrite.STALE, restored.delete(detached.first()))
        val rebound = restored.tabs.first()
        assertEquals(original.first().definition, rebound.first().definition)
        assertEquals(CustomTabSource.Playlist(20), rebound.first().resolvedSource)
        assertEquals(300L, rebound.first().playlistCreatedAt)
        assertNull(rebound[1].resolvedSource)
        assertEquals(CustomTabSource.Playlist(12), settings.tabs.first().first().resolvedSource)
    }

    @Test
    fun `unavailable installation marker detaches structural tabs and retains their portable definitions`() = runBlocking {
        settings.create("Playlist", playlist)
        settings.create("Folder", folder)
        val restored = CustomTabsSettings(MemoryDataStore(file.data.first())) { throw IOException("Unavailable") }
        val original = settings.tabs.first()
        val detached = restored.tabs.first()
        assertEquals(original.map { it.definition }, detached.map { it.definition })
        assertTrue(detached.all { it.resolvedSource == null })
        assertEquals(CustomTabWrite.WRITE_FAILED, restored.rebind(detached.first(), playlist))
    }

    @Test
    fun `unreadable local attachment leaves the definition available to rebind`() = runBlocking {
        settings.create("Playlist", playlist, select = true)
        val original = settings.tabs.first().single()
        val key = CustomTabsSettings.tabKey(original.definition.id)
        file.edit { preferences ->
            preferences[key] = requireNotNull(preferences[key]).replace("\"installation\":\"$installation\",", "")
        }
        val state = settings.state.first()
        val detached = state.tabs.single()
        assertEquals(original.definition, detached.definition)
        assertEquals(original.definition.id, state.selectedId)
        assertNull(detached.resolvedSource)
        assertEquals(CustomTabWrite.STORED, settings.rebind(detached, playlist))
    }

    @Test
    fun `portable updates keep explicit matching attachments and cannot infer replacements`() = runBlocking {
        settings.create("Folder", folder)
        val original = settings.tabs.first().single()
        val definition = original.definition
        val source = definition.source as CustomTabTemplateSource.Folder
        val portable = CustomTabsSettings.definitionsOf(file.data.first())
        val other = MemoryDataStore()
        other.edit { assertTrue(CustomTabsSettings.mergeDefinitions(it, portable)) }
        assertNull(CustomTabsSettings(other) { installation }.tabs.first().single().resolvedSource)

        val renamed = definition.copy(name = "Another name", source = source.copy(hint = "New hint"))
        file.edit { assertTrue(CustomTabsSettings.mergeDefinitions(it, mapOf(definition.id to renamed))) }
        assertEquals(original.resolvedSource, settings.tabs.first().single().resolvedSource)
        file.edit { assertTrue(CustomTabsSettings.mergeDefinitions(it, emptyMap())) }
        assertEquals(renamed, settings.tabs.first().single().definition)
        val unrelatedRef = source.copy(sourceRef = restoredInstallation)
        file.edit {
            assertTrue(CustomTabsSettings.mergeDefinitions(it, mapOf(definition.id to renamed.copy(source = unrelatedRef))))
        }
        assertNull(settings.tabs.first().single().resolvedSource)
    }

    @Test
    fun `theme layouts are independent and deletion prunes related settings everywhere`() = runBlocking {
        settings.create("Folder", folder)
        val record = settings.tabs.first().single()
        val id = record.definition.id
        val component = record.definition.componentId
        val firstTheme = ThemeMode.SYSTEM.stored
        val secondTheme = ThemeMode.GRAPHITE.stored
        BuiltInLibraryTab.ids.forEach { assertEquals(LibraryTabWrite.STORED, layouts.setVisible(it, false)) }
        assertEquals(LibraryTabWrite.LAST_VISIBLE, layouts.setVisible(id, false))
        settings.setSelected(id)
        AppearanceSettings(file).setTheme(ThemeMode.GRAPHITE)
        assertTrue("songs" in layouts.layout.first().visible)
        layouts.setVisible(id, false)
        AppearanceSettings(file).setTheme(ThemeMode.SYSTEM)
        assertEquals(listOf(id), layouts.layout.first().visible)
        file.edit { preferences ->
            val canvas = UiCanvasLayout(
                items = listOf(UiCanvasItem(component, UiCanvasBounds(0, 0, 40, 40))),
                hiddenStock = emptySet(),
                groups = listOf(setOf("tab:songs", "tab:albums", component), setOf("tab:genres", "tab:folders")),
                labelDisplays = mapOf(component to UiLabelDisplay.TEXT_ONLY),
            )
            for (theme in listOf(firstTheme, secondTheme)) {
                UiCanvasSettings.store(preferences, theme, UiThemeCanvases(mapOf(UiSurface.LIBRARY to canvas)))
            }
        }

        assertEquals(CustomTabWrite.STORED, settings.delete(record))
        assertEquals(listOf("songs"), layouts.layout.first().visible)
        assertNull(settings.state.first().selectedId)
        assertEquals(emptyList<Any>(), settings.tabs.first())
        for (theme in listOf(firstTheme, secondTheme)) {
            val saved = file.data.first()
            assertFalse(saved[LibraryTabsSettings.panelKey(theme)].orEmpty().contains(id))
            assertFalse(saved[UiCanvasSettings.key(theme)].orEmpty().contains(id))
            assertEquals(
                listOf(setOf("tab:genres", "tab:folders")),
                UiCanvasSettings.savedLayouts(saved, theme).panels.getValue(UiSurface.LIBRARY).groups,
            )
        }
        assertFalse(UiCanvasSettings(file).setLabelDisplay(firstTheme, UiSurface.LIBRARY, component, UiLabelDisplay.TEXT_ONLY))
        assertEquals(CustomTabWrite.STALE, settings.delete(record))
    }

    @Test
    fun `legacy standalone import leaves custom definitions and attachments intact`() = runBlocking {
        settings.create("Folder", folder)
        val original = settings.tabs.first().single()
        val theme = CustomTheme("Other", id = "a1466b1c-4c70-4cbb-9971-5ece342a2eef")
        val appearance = AppearanceSettings(file)
        appearance.applyCustomTheme(theme, panel = LibraryPanelLayout())
        assertEquals(original, settings.tabs.first().single())
        assertEquals(BuiltInLibraryTab.ids + original.definition.id, layouts.layout.first().order)
    }

    companion object {
        private const val installation = "a38e7477-0fb0-47b4-97d8-422413c3a1c4"
        private const val restoredInstallation = "04c31211-d885-4500-a449-a218e1680a9d"
        private val playlist = CustomTabSourceDraft.Playlist(12, 100, "Favorites")
        private val folder = CustomTabSourceDraft.Folder(FolderKey("primary", "Music/"), "Music")
        private val favorites = CustomTabSourceDraft.Collection(ListKind.FAVORITES)
    }
}
