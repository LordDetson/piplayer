package io.github.lorddetson.piplayer.settings.librarytabs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

internal enum class LibraryTabWrite { STORED, UNKNOWN_TAB, OUT_OF_BOUNDS, LAST_VISIBLE, WRITE_FAILED }

/** The library editor's tab order and visibility in the backed-up settings file (library.md §6.1). */
internal class LibraryTabsSettings(private val file: DataStore<Preferences>) {
    constructor(context: Context) : this(SettingsFiles.settings(context))

    val layout: Flow<LibraryTabLayout> = file.data.map(::layoutOf).distinctUntilChanged()

    /** Moves [id] to its final index in the editor, whose order includes hidden rows. */
    suspend fun move(id: String, toIndex: Int): LibraryTabWrite = update { preferences, layout ->
        val from = layout.order.indexOf(id)
        if (from < 0) return@update LibraryTabWrite.UNKNOWN_TAB
        if (toIndex !in layout.order.indices) return@update LibraryTabWrite.OUT_OF_BOUNDS
        if (from != toIndex) {
            val reordered = layout.order.toMutableList()
            reordered.removeAt(from)
            reordered.add(toIndex, id)
            preferences[ORDER] = mergeKnownOrder(preferences.savedOrder(), reordered).joinToString(",")
            // Keep the same visible tab when the old settings needed the all-hidden fallback.
            preferences.storeKnownHidden(layout.hidden)
        }
        LibraryTabWrite.STORED
    }

    suspend fun setVisible(id: String, visible: Boolean): LibraryTabWrite = update { preferences, layout ->
        if (id !in layout.order) return@update LibraryTabWrite.UNKNOWN_TAB
        if (!visible && layout.visible.singleOrNull() == id) return@update LibraryTabWrite.LAST_VISIBLE
        val hidden = if (visible) layout.hidden - id else layout.hidden + id
        if (hidden != layout.hidden) preferences.storeKnownHidden(hidden)
        LibraryTabWrite.STORED
    }

    private suspend fun update(change: (MutablePreferences, LibraryTabLayout) -> LibraryTabWrite): LibraryTabWrite {
        var result = LibraryTabWrite.WRITE_FAILED
        val stored = SettingsFiles.edit(file) { preferences -> result = change(preferences, layoutOf(preferences)) }
        return if (stored == null) LibraryTabWrite.WRITE_FAILED else result
    }

    private companion object {
        val ORDER = stringPreferencesKey("library_tab_order")
        val HIDDEN = stringSetPreferencesKey("library_hidden_tabs")

        fun layoutOf(preferences: Preferences): LibraryTabLayout =
            LibraryTabLayout.fromStored(preferences.savedOrder(), preferences[HIDDEN].orEmpty())

        fun Preferences.savedOrder(): List<String> = get(ORDER).orEmpty().split(',').filter(String::isNotBlank)

        /** Preserve stored IDs this version cannot display when it moves known tabs. */
        fun mergeKnownOrder(stored: List<String>, reordered: List<String>): List<String> = buildList {
            val known = reordered.toSet()
            val next = reordered.iterator()
            for (id in stored.distinct()) add(if (id in known) next.next() else id)
            while (next.hasNext()) add(next.next())
        }

        fun MutablePreferences.storeKnownHidden(hidden: Set<String>) {
            val updated = (get(HIDDEN).orEmpty() - BuiltInLibraryTab.ids.toSet()) + hidden
            if (updated.isEmpty()) remove(HIDDEN) else set(HIDDEN, updated)
        }
    }
}
