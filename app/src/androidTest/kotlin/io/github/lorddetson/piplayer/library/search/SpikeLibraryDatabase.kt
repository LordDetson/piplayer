package io.github.lorddetson.piplayer.library.search

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.RawQuery
import androidx.room3.RoomDatabase
import androidx.room3.RoomRawQuery

/**
 * The M0 Room spike: a cut-down `track_display` (TI §10.1) with the normalized search text and a sort key. The real
 * library schema comes with M2a.
 */
// Covering the search text too: a query walks this index in title order and never reads the table for rows that do
// not match.
@Entity(tableName = "track_search", indices = [Index("title_key", "search_text")])
data class TrackSearchRow(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "title_key") val titleKey: String,
    @ColumnInfo(name = "search_text") val searchText: String,
)

@Dao
interface TrackSearchDao {

    @Insert
    suspend fun insertAll(rows: List<TrackSearchRow>)

    @RawQuery
    suspend fun search(query: RoomRawQuery): List<TrackSearchRow>

    @RawQuery
    suspend fun plan(query: RoomRawQuery): List<QueryPlanRow>
}

/** One line of EXPLAIN QUERY PLAN. */
data class QueryPlanRow(val id: Int, val parent: Int, val notused: Int, val detail: String)

@Database(entities = [TrackSearchRow::class], version = 1, exportSchema = false)
abstract class SpikeLibraryDatabase : RoomDatabase() {
    abstract fun tracks(): TrackSearchDao
}
