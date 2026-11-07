package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The kinds of lists whose order the user chooses (docs/library.md §6.3); [id] names them in the settings file. */
enum class ListName(val id: String, val fields: List<String>, val offersGrid: Boolean = false) {
    SONGS("songs", listOf("title", "artist", "album", "length")),
    ALBUMS("albums", listOf("title", "artist", "year"), offersGrid = true),
    ARTISTS("artists", listOf("name", "songs"), offersGrid = true),

    /** The Folders tab and folder pages: subfolders by name, songs by file name or title. */
    FOLDERS("folders", listOf("file", "title")),
    ALBUM_PAGE("album_page", listOf("track")),
    ARTIST_PAGE("artist_page", listOf("year", "title")),
    ;

    val defaultField: String get() = fields.first()
}

/** A list's chosen order: one of its [ListName.fields], and whether it is reversed. */
data class ListOrder(val field: String, val reversed: Boolean = false)

/**
 * The order of each kind of list and the choice of list or grid, kept in the `settings` file that goes into backups
 * (docs/library.md §3.2, §6.3, §11.3).
 */
class ListSorts(context: Context) {

    private val file = SettingsFiles.settings(context)

    fun order(list: ListName): Flow<ListOrder> = file.data.map { orderOf(it, list) }.distinctUntilChanged()

    suspend fun set(list: ListName, order: ListOrder) {
        SettingsFiles.edit(file) {
            it[fieldKey(list)] = order.field
            it[reversedKey(list)] = order.reversed
        }
    }

    /** Whether [list] shows as a grid (docs/library.md §11.3); a list by default. */
    fun grid(list: ListName): Flow<Boolean> =
        file.data.map { list.offersGrid && it[gridKey(list)] == true }.distinctUntilChanged()

    suspend fun setGrid(list: ListName, grid: Boolean) {
        if (!list.offersGrid) return
        SettingsFiles.edit(file) { it[gridKey(list)] = grid }
    }

    /** Back to the default order of every list. */
    suspend fun clear() {
        SettingsFiles.edit(file) { preferences ->
            for (list in ListName.entries) {
                preferences.remove(fieldKey(list))
                preferences.remove(reversedKey(list))
                preferences.remove(gridKey(list))
            }
        }
    }

    private fun orderOf(preferences: Preferences, list: ListName): ListOrder {
        // A field a later version no longer offers falls back to the default.
        val field = preferences[fieldKey(list)]?.takeIf { it in list.fields } ?: list.defaultField
        return ListOrder(field, preferences[reversedKey(list)] ?: false)
    }

    private fun fieldKey(list: ListName) = stringPreferencesKey("sort_${list.id}")

    private fun reversedKey(list: ListName) = booleanPreferencesKey("sort_${list.id}_reversed")

    private fun gridKey(list: ListName) = booleanPreferencesKey("grid_${list.id}")
}
