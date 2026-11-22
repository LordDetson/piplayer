package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.library.lists.ListKind
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal enum class PlaylistSection { MANUAL, SMART }

internal data class PlaylistCatalogOrder(
    private val smartFirst: Boolean = false,
    private val manualOrder: List<Long> = emptyList(),
    private val smartOrder: List<ListKind> = emptyList(),
) {
    val sections: List<PlaylistSection> get() = if (smartFirst) PlaylistSection.entries.reversed() else PlaylistSection.entries

    fun manual(shown: List<Long>): List<Long> = manualOrder.followedByNew(shown)

    fun smart(shown: List<ListKind>): List<ListKind> = smartOrder.followedByNew(shown)
}

internal class PlaylistCatalogOrderSettings(context: Context) {
    private val file = SettingsFiles.settings(context)

    val order = file.data.map(::read).distinctUntilChanged()

    suspend fun moveSectionToTop(section: PlaylistSection): Boolean =
        SettingsFiles.edit(file) { it[smartFirstKey] = section == PlaylistSection.SMART } != null

    suspend fun moveManual(shown: List<Long>, id: Long, by: Int): Boolean = SettingsFiles.edit(file) { preferences ->
        preferences[manualKey] = read(preferences).manual(shown).movedBy(id, by).joinToString(",")
    } != null

    suspend fun moveSmart(kind: ListKind, by: Int): Boolean = SettingsFiles.edit(file) { preferences ->
        preferences[smartKey] = read(preferences).smart(ListKind.SMART).movedBy(kind, by).joinToString(",", transform = ListKind::token)
    } != null

    companion object {
        internal val smartFirstKey = booleanPreferencesKey("playlist_catalog_smart_first")
        internal val manualKey = stringPreferencesKey("playlist_catalog_manual_order")
        internal val smartKey = stringPreferencesKey("playlist_catalog_smart_order")

        internal fun read(preferences: Preferences): PlaylistCatalogOrder = PlaylistCatalogOrder(
            smartFirst = preferences[smartFirstKey] == true,
            manualOrder = preferences[manualKey].orEmpty().split(',').mapNotNull(String::toLongOrNull).distinct(),
            smartOrder = preferences[smartKey].orEmpty().split(',').mapNotNull(ListKind::of).filter(ListKind::smart).distinct(),
        )
    }
}

private fun <T> List<T>.followedByNew(shown: List<T>): List<T> {
    val present = shown.toHashSet()
    val saved = toHashSet()
    return filter { it in present } + shown.filterNot { it in saved }
}

internal fun <T> List<T>.movedBy(item: T, by: Int): List<T> {
    val from = indexOf(item)
    val to = from + by
    if (from < 0 || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
