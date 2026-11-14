package io.github.lorddetson.piplayer.userdata

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/** A folder the user included or excluded, with all its subfolders (docs/library.md §3.4). */
@Entity(tableName = "library_folder_rule", primaryKeys = ["volume_name", "relative_path"])
data class FolderRuleEntity(
    @ColumnInfo(name = "volume_name") val volumeName: String,
    /** As the media index gives it: empty for the volume's root, otherwise ending with `/`. */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    /** [INCLUDE] or [EXCLUDE]. */
    val mode: Int,
) {
    companion object {
        const val EXCLUDE = 0
        const val INCLUDE = 1
    }
}

/** The one row whose counter goes up with every write of user data (docs/library.md §3.1). */
@Entity(tableName = "user_meta")
data class UserMetaEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "change_counter") val changeCounter: Long,
    /** Goes up only with the folder rules, which the sync applies again when it changes (docs/library.md §3.1). */
    @ColumnInfo(name = "rules_counter", defaultValue = "0") val rulesCounter: Long = 0,
)

@Dao
interface UserDao {

    @Query("SELECT * FROM library_folder_rule ORDER BY volume_name, relative_path")
    suspend fun folderRules(): List<FolderRuleEntity>

    @Query("SELECT * FROM library_folder_rule ORDER BY volume_name, relative_path")
    fun folderRulesFlow(): Flow<List<FolderRuleEntity>>

    @Query("INSERT OR REPLACE INTO library_folder_rule (volume_name, relative_path, mode) VALUES (:volume, :path, :mode)")
    suspend fun putFolderRule(volume: String, path: String, mode: Int)

    @Query("DELETE FROM library_folder_rule WHERE volume_name = :volume AND relative_path = :path")
    suspend fun removeFolderRule(volume: String, path: String)

    @Query("SELECT COALESCE((SELECT change_counter FROM user_meta WHERE id = 1), 0)")
    suspend fun changeCounter(): Long

    @Query("SELECT COALESCE((SELECT change_counter FROM user_meta WHERE id = 1), 0)")
    fun changeCounterFlow(): Flow<Long>

    @Query(
        "INSERT INTO user_meta (id, change_counter) VALUES (1, 1) " +
            "ON CONFLICT(id) DO UPDATE SET change_counter = change_counter + 1",
    )
    suspend fun countChange()

    @Query("SELECT COALESCE((SELECT rules_counter FROM user_meta WHERE id = 1), 0)")
    suspend fun rulesCounter(): Long

    @Query("SELECT COALESCE((SELECT rules_counter FROM user_meta WHERE id = 1), 0)")
    fun rulesCounterFlow(): Flow<Long>

    /** A change of the folder rules; [countChange] goes up with it. */
    @Query(
        "INSERT INTO user_meta (id, change_counter, rules_counter) VALUES (1, 0, 1) " +
            "ON CONFLICT(id) DO UPDATE SET rules_counter = rules_counter + 1",
    )
    suspend fun countRulesChange()

    /** The identities that user data refers to, which the attach pass never deletes (docs/library.md §8.1, §15, §16.2). */
    @Query(
        "SELECT identity_id FROM track_cover UNION SELECT identity_id FROM favorite UNION SELECT identity_id FROM play_event " +
            "UNION SELECT identity_id FROM play_stats UNION SELECT identity_id FROM playlist_entry WHERE identity_id IS NOT NULL",
    )
    suspend fun identitiesHoldingData(): List<Long>
}

/** Version 4 copies the change counter into the new rules counter, so that the rules mirrored already stay mirrored. */
class RulesCounterMigration : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.prepare("UPDATE user_meta SET rules_counter = change_counter").use { it.step() }
    }
}

/**
 * The user database (docs/library.md §3.1): what the user decided, never rebuilt. Its schema is exported and every new
 * version needs a migration; there is no destructive fallback.
 */
@Database(
    entities = [
        FolderRuleEntity::class, UserMetaEntity::class, TrackIdentityEntity::class, TrackCoverEntity::class,
        AlbumCoverEntity::class, FavoriteEntity::class, FavoriteAlbumEntity::class, AlbumMemberEntity::class,
        PlayEventEntity::class, PlayStatsEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class,
    ],
    version = 5,
    exportSchema = true,
    // Version 2 adds the track identities (docs/library.md §8.1), version 3 the own covers (§13.2), version 4 favorites,
    // album members, plays and the rules counter (§15.1), version 5 playlists (§16.1).
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = RulesCounterMigration::class),
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class UserDatabase : RoomDatabase() {

    abstract fun user(): UserDao

    abstract fun identities(): TrackIdentityDao

    abstract fun covers(): OwnCoverDao

    abstract fun favorites(): FavoriteDao

    abstract fun plays(): PlayDao

    abstract fun playlists(): PlaylistDao

    companion object {
        const val NAME = "user.db"

        /** The database [name] in the app's database directory, or in memory when it is null (tests). */
        fun open(context: Context, name: String?): UserDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder<UserDatabase>(context)
            } else {
                Room.databaseBuilder<UserDatabase>(context, context.getDatabasePath(name).absolutePath)
            }
            return builder
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        }
    }
}
