package io.github.lorddetson.piplayer.settings.librarytabs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.builtInThemeLayout
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The stock order and IDs are independent of resource labels and enum ordinals. */
internal enum class BuiltInLibraryTab(val id: String) {
    SONGS("songs"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    PLAYLISTS("playlists"),
    GENRES("genres"),
    FOLDERS("folders"),
    ;

    companion object {
        val ids = entries.map(BuiltInLibraryTab::id)
    }
}

internal data class LibraryTabLayout(val order: List<String>, val hidden: Set<String>) {
    val visible: List<String> = order.filterNot(hidden::contains)

    /** Activity state from earlier versions used the built-in enum's uppercase names. */
    fun selectedTab(savedId: String?): String {
        val id = BuiltInLibraryTab.entries.firstOrNull { it.name == savedId }?.id ?: savedId
        return id?.takeIf(visible::contains) ?: visible.first()
    }

    companion object {
        val DEFAULT = LibraryTabLayout(BuiltInLibraryTab.ids, emptySet())

        fun fromStored(
            order: List<String>,
            hidden: Set<String>,
            availableIds: List<String> = BuiltInLibraryTab.ids,
        ): LibraryTabLayout {
            require(availableIds.isNotEmpty()) { "A library needs at least one tab" }
            val available = availableIds.toSet()
            val fullOrder = (order.filter(available::contains) + availableIds).distinct()
            val knownHidden = hidden intersect available
            val resolvedHidden = if (knownHidden.size == fullOrder.size) knownHidden - fullOrder.first() else knownHidden
            return LibraryTabLayout(fullOrder, resolvedHidden)
        }
    }
}

internal enum class LibraryTabWrite { STORED, UNKNOWN_TAB, OUT_OF_BOUNDS, LAST_VISIBLE, THEME_CHANGED, WRITE_FAILED }

internal data class LibraryPanelState(val themeId: String, val layout: LibraryPanelLayout)

/** The library editor's tab order and visibility in the backed-up settings file (library.md §6.1). */
internal class LibraryTabsSettings(private val file: DataStore<Preferences>) {
    constructor(context: Context) : this(SettingsFiles.settings(context))

    val panel: Flow<LibraryPanelState> = file.data.map(::panelOf).distinctUntilChanged()
    val layout: Flow<LibraryTabLayout> = panel.map { it.layout.tabs }.distinctUntilChanged()

    /** Moves [id] to its final index in the editor, whose order includes hidden rows. */
    suspend fun move(id: String, toIndex: Int): LibraryTabWrite = moveTo(id) { toIndex }

    /** Moves a visible tab to the current position of another visible tab. */
    suspend fun moveToTab(id: String, target: String, themeId: String? = null): LibraryTabWrite = moveTo(id, themeId) { layout ->
        layout.order.indexOf(target).takeIf { id in layout.visible && target in layout.visible }
    }

    private suspend fun moveTo(
        id: String,
        themeId: String? = null,
        destination: (LibraryTabLayout) -> Int?,
    ): LibraryTabWrite = update(themeId) { preferences, panel ->
        val layout = panel.layout.tabs
        val from = layout.order.indexOf(id)
        if (from < 0) return@update LibraryTabWrite.UNKNOWN_TAB
        val toIndex = destination(layout) ?: return@update LibraryTabWrite.UNKNOWN_TAB
        if (toIndex !in layout.order.indices) return@update LibraryTabWrite.OUT_OF_BOUNDS
        if (from != toIndex) {
            val reordered = layout.order.moved(from, toIndex)
            storePanel(preferences, panel.themeId, panel.layout.copy(tabs = layout.copy(order = reordered)))
        }
        LibraryTabWrite.STORED
    }

    suspend fun setVisible(id: String, visible: Boolean, themeId: String? = null): LibraryTabWrite = update(themeId) { preferences, panel ->
        val layout = panel.layout.tabs
        if (id !in layout.order) return@update LibraryTabWrite.UNKNOWN_TAB
        if (!visible && layout.visible.singleOrNull() == id) return@update LibraryTabWrite.LAST_VISIBLE
        val hidden = if (visible) layout.hidden - id else layout.hidden + id
        if (hidden != layout.hidden) {
            storePanel(preferences, panel.themeId, panel.layout.copy(tabs = layout.copy(hidden = hidden)))
        }
        LibraryTabWrite.STORED
    }

    suspend fun moveButton(id: String, target: String, themeId: String): LibraryTabWrite = update(themeId) { preferences, panel ->
        val buttons = panel.layout.buttons
        if (id !in buttons.visible || target !in buttons.visible) return@update LibraryTabWrite.UNKNOWN_TAB
        if (id != target) {
            val reordered = buttons.order.moved(buttons.order.indexOf(id), buttons.order.indexOf(target))
            storePanel(preferences, themeId, panel.layout.copy(buttons = buttons.copy(order = reordered)))
        }
        LibraryTabWrite.STORED
    }

    suspend fun setButtonVisible(id: String, visible: Boolean, themeId: String): LibraryTabWrite = update(themeId) { preferences, panel ->
        val buttons = panel.layout.buttons
        if (id !in buttons.order) return@update LibraryTabWrite.UNKNOWN_TAB
        val hidden = if (visible) buttons.hidden - id else buttons.hidden + id
        if (hidden != buttons.hidden) {
            storePanel(preferences, themeId, panel.layout.copy(buttons = buttons.copy(hidden = hidden)))
        }
        LibraryTabWrite.STORED
    }

    private suspend fun update(
        themeId: String?,
        change: (MutablePreferences, LibraryPanelState) -> LibraryTabWrite,
    ): LibraryTabWrite {
        var result = LibraryTabWrite.WRITE_FAILED
        val stored = SettingsFiles.edit(file) { preferences ->
            val panel = panelOf(preferences)
            result = if (themeId == null || themeId == panel.themeId) change(preferences, panel) else LibraryTabWrite.THEME_CHANGED
        }
        return if (stored == null) LibraryTabWrite.WRITE_FAILED else result
    }

    companion object {
        fun panelOf(preferences: Preferences): LibraryPanelState {
            val themeId = AppearanceSettings.panelThemeId(preferences)
            val layout = savedPanel(preferences, themeId) ?: builtInThemeLayout(themeId)?.library ?: LibraryPanelLayout()
            return LibraryPanelState(themeId, layout)
        }

        fun savedPanel(preferences: Preferences, themeId: String): LibraryPanelLayout? = try {
            preferences[panelKey(themeId)]?.let { Json.decodeFromString<LibraryPanelDocument>(it).toLayout() }
        } catch (_: SerializationException) {
            null
        }

        fun storePanel(preferences: MutablePreferences, themeId: String, layout: LibraryPanelLayout) {
            preferences[panelKey(themeId)] = Json.encodeToString(LibraryPanelDocument.from(layout))
        }

        private fun panelKey(themeId: String) = stringPreferencesKey("library_panel_$themeId")

        private fun List<String>.moved(from: Int, to: Int): List<String> = toMutableList().apply { add(to, removeAt(from)) }
    }
}
