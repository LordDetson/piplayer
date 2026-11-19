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

    /**
     * The identities that user data refers to, which the attach pass never deletes (docs/library.md §8.1, §15, §16.2):
     * decisions, undone ones too, `file_state` rows and the tag-write journal count (TI §10.2). The listening outcomes do not (§15.7): neither
     * `listen_event` nor a `play_stats` row without a play keeps an identity.
     */
    @Query(
        "SELECT identity_id FROM track_cover UNION SELECT identity_id FROM favorite UNION SELECT identity_id FROM play_event " +
            "UNION SELECT identity_id FROM play_stats WHERE play_count > 0 " +
            "UNION SELECT identity_id FROM playlist_entry WHERE identity_id IS NOT NULL " +
            "UNION SELECT identity_id FROM decision UNION SELECT identity_id FROM file_state " +
            "UNION SELECT identity_id FROM applied_change UNION SELECT identity_id FROM tag_write_archive_ref " +
            "UNION SELECT identity_id FROM track_legacy_encoding WHERE name IS NULL OR name <> ''",
    )
    suspend fun identitiesHoldingData(): List<Long>
}

/** Version 4 copies the change counter into the new rules counter, so that the rules mirrored already stay mirrored. */
class RulesCounterMigration : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.prepare("UPDATE user_meta SET rules_counter = change_counter").use { it.step() }
    }
}

class CompletedTagApplyMigration : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.prepare(
            "INSERT INTO tag_apply_progress (plan_id, written) " +
                "SELECT plan_id, COUNT(*) FROM tag_apply_item WHERE result = 'written' GROUP BY plan_id",
        ).use { it.step() }
        connection.prepare("DELETE FROM tag_apply_item WHERE result = 'written'").use { it.step() }
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
        DecisionEntity::class, ReleaseChoiceEntity::class, TrackMetadataEntity::class, FileStateEntity::class,
        SavedCoverEntity::class, ListenEventEntity::class, TagBackupEntity::class, AppliedChangeEntity::class, TagApplyItem::class,
        TagApplyProgress::class, TagWriteArchivePage::class, TagWriteArchiveRef::class, TagWriteArchiveBatch::class,
        TagWriteRefresh::class, TrackLegacyEncoding::class,
    ],
    version = 11,
    exportSchema = true,
    // Version 2 adds the track identities (docs/library.md §8.1), version 3 the own covers (§13.2), version 4 favorites,
    // album members, plays and the rules counter (§15.1), version 5 playlists (§16.1), version 6 the decisions of
    // identification and their values (TI §10.2) and the listening outcomes, with play events keyed by their start
    // without row IDs (§15.7), version 7 the tag-write journal and snapshots (TI §10.2), version 8 the apply queue,
    // version 9 the compacted journal and apply progress, version 10 the library refresh queue, and version 11 the
    // per-track legacy encoding preferences.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = RulesCounterMigration::class),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = CompletedTagApplyMigration::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
    ],
)
abstract class UserDatabase : RoomDatabase() {

    abstract fun user(): UserDao

    abstract fun identities(): TrackIdentityDao
    abstract fun trackLegacyEncodings(): TrackLegacyEncodingDao

    abstract fun covers(): OwnCoverDao

    abstract fun favorites(): FavoriteDao

    abstract fun plays(): PlayDao

    abstract fun playlists(): PlaylistDao

    /** The decisions of identification and their values (TI §10.2). */
    abstract fun decisions(): DecisionDao

    /** The outcome of every listening (docs/library.md §15.7). */
    abstract fun listens(): ListenDao

    /** The journal that survives interrupted writes (TI §7.2). */
    abstract fun tagWrites(): TagWriteDao

    /** Selected files waiting for a write or new consent (TI §7.2, §8). */
    abstract fun tagApplyQueue(): TagApplyDao

    abstract fun tagWriteArchive(): TagWriteArchiveDao

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
