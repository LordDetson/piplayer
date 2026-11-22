package io.github.lorddetson.piplayer.settings.librarytabs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.lorddetson.piplayer.settings.MemoryDataStore
import io.github.lorddetson.piplayer.settings.ThemeLayoutMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryTabsSettingsTest {

    @Test
    fun `older installs keep their familiar order and saved selection`() = runBlocking {
        val file = MemoryDataStore()
        val layout = LibraryTabsSettings(file).layout.first()

        assertEquals(standard, layout.order)
        assertEquals(standard, layout.visible)
        assertEquals("songs", layout.selectedTab(null))
        assertEquals("folders", layout.selectedTab("FOLDERS"))
        assertEquals("genres", layout.selectedTab("genres"))
        assertEquals(emptyMap<Any, Any>(), file.data.first().asMap())
    }

    @Test
    fun `hidden tabs can be moved and restored at their new position after reopen`() = runBlocking {
        val untouched = booleanPreferencesKey("some_other_setting")
        val file = MemoryDataStore(mutablePreferencesOf(untouched to true))
        val settings = LibraryTabsSettings(file)

        assertEquals(LibraryTabWrite.STORED, settings.move("folders", 1))
        assertEquals(LibraryTabWrite.STORED, settings.setVisible("artists", visible = false))
        assertEquals(LibraryTabWrite.STORED, settings.move("artists", 0))

        val reopened = LibraryTabsSettings(MemoryDataStore(file.data.first()))
        val order = listOf("artists", "songs", "folders", "albums", "playlists", "genres")
        assertEquals(order, reopened.layout.first().order)
        assertEquals(order.drop(1), reopened.layout.first().visible)
        assertEquals(true, file.data.first()[untouched])

        assertEquals(LibraryTabWrite.STORED, reopened.setVisible("artists", visible = true))
        assertEquals(order, reopened.layout.first().visible)
    }

    @Test
    fun `hidden or unavailable active tabs fall back to the first visible tab`() = runBlocking {
        val settings = LibraryTabsSettings(MemoryDataStore())
        settings.move("genres", 0)
        settings.setVisible("songs", visible = false)
        val layout = settings.layout.first()

        assertEquals("folders", layout.selectedTab("folders"))
        assertEquals("genres", layout.selectedTab("songs"))
        assertEquals("genres", layout.selectedTab("SONGS"))
        assertEquals("genres", layout.selectedTab("removed"))
        assertEquals("genres", layout.selectedTab(null))
    }

    @Test
    fun `hiding the last visible tab leaves settings intact`() = runBlocking {
        val file = MemoryDataStore()
        val settings = LibraryTabsSettings(file)
        standard.dropLast(1).forEach { settings.setVisible(it, visible = false) }
        val before = file.data.first()

        assertEquals(LibraryTabWrite.LAST_VISIBLE, settings.setVisible("folders", visible = false))
        assertEquals(listOf("folders"), settings.layout.first().visible)
        assertEquals(before, file.data.first())
        assertEquals(LibraryTabWrite.STORED, settings.setVisible("songs", visible = true))
        assertEquals(LibraryTabWrite.STORED, settings.setVisible("folders", visible = false))
        assertEquals(listOf("songs"), settings.layout.first().visible)
    }

    @Test
    fun `saved duplicates and missing entries recover a usable order and visibility`() = runBlocking {
        val preferences = mutablePreferencesOf(
            ORDER to "missing,genres,songs,genres,,folders",
            HIDDEN to (standard + "missing").toSet(),
        )
        val file = MemoryDataStore(ThemeLayoutMigration.migrate(preferences))
        val layout = LibraryTabsSettings(file).layout.first()

        assertEquals(listOf("genres", "songs", "folders", "albums", "artists", "playlists"), layout.order)
        assertEquals(listOf("genres"), layout.visible)
        assertEquals("genres", layout.selectedTab("folders"))
    }

    @Test
    fun `invalid changes and repeated settings writes leave existing settings intact`() = runBlocking {
        val file = MemoryDataStore()
        val settings = LibraryTabsSettings(file)
        val before = file.data.first()

        assertEquals(LibraryTabWrite.UNKNOWN_TAB, settings.move("unknown", 0))
        assertEquals(LibraryTabWrite.UNKNOWN_TAB, settings.setVisible("unknown", visible = false))
        assertEquals(LibraryTabWrite.OUT_OF_BOUNDS, settings.move("songs", -1))
        assertEquals(LibraryTabWrite.OUT_OF_BOUNDS, settings.move("songs", standard.size))
        assertEquals(LibraryTabWrite.STORED, settings.move("songs", 0))
        assertEquals(LibraryTabWrite.STORED, settings.setVisible("songs", visible = true))
        assertEquals(before, file.data.first())
    }

    @Test
    fun `a future supported tab ID can participate without changing built-in IDs`() {
        val custom = "custom:9b71e0f7-7ba1-477f-b067-ae268f5b768a"
        val layout = LibraryTabLayout.fromStored(
            order = listOf(custom, "genres"),
            hidden = setOf("genres"),
            availableIds = standard + custom,
        )

        assertEquals(listOf(custom, "genres") + standard.filterNot { it == "genres" }, layout.order)
        assertEquals(custom, layout.selectedTab(custom))
        assertEquals(custom, layout.selectedTab("genres"))
    }

    @Test
    fun `a reorder and visibility edit can both persist when launched together`() = runBlocking {
        val settings = LibraryTabsSettings(MemoryDataStore())
        val move = async(Dispatchers.Default) { settings.move("folders", 0) }
        val hide = async(Dispatchers.Default) { settings.setVisible("artists", visible = false) }

        assertEquals(LibraryTabWrite.STORED, move.await())
        assertEquals(LibraryTabWrite.STORED, hide.await())
        val layout = settings.layout.first()
        assertEquals("folders", layout.order.first())
        assertEquals(setOf("artists"), layout.hidden)
    }

    private companion object {
        val standard = listOf("songs", "albums", "artists", "playlists", "genres", "folders")
        val ORDER = stringPreferencesKey("library_tab_order")
        val HIDDEN = stringSetPreferencesKey("library_hidden_tabs")
    }
}
