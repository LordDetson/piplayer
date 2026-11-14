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

    /** The Genres tab and the genre pages (docs/library.md §19.2, §19.3). */
    GENRES("genres", listOf("name", "songs")),
    GENRE_PAGE("genre_page", listOf("artist", "title", "album", "length")),

    /** The smart lists (docs/library.md §17.1), each with its own order; the three lists of 100 in their own order only. */
    FAVORITES("favorites", listOf("title", "artist", "album", "length")),
    RECENTLY_ADDED("recently_added", listOf("position")),
    RECENTLY_PLAYED("recently_played", listOf("position")),
    MOST_PLAYED("most_played", listOf("position")),
    MISSING_INFO("missing_info", listOf("title", "artist", "album", "length")),
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

    companion object {
        /** The order of [list] in [preferences]; a field a later version no longer offers falls back to the default. */
        internal fun orderOf(preferences: Preferences, list: ListName): ListOrder {
            val field = preferences[fieldKey(list)]?.takeIf { it in list.fields } ?: list.defaultField
            return ListOrder(field, preferences[reversedKey(list)] ?: false)
        }

        internal fun fieldKey(list: ListName) = stringPreferencesKey("sort_${list.id}")

        internal fun reversedKey(list: ListName) = booleanPreferencesKey("sort_${list.id}_reversed")

        private fun gridKey(list: ListName) = booleanPreferencesKey("grid_${list.id}")
    }
}
