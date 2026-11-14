package io.github.lorddetson.piplayer.userdata

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The user database (docs/library.md §3.1): its exported schema, and the change counter of its user data. */
@RunWith(AndroidJUnit4::class)
class UserDatabaseTest {

    private val context = TestFiles.targetContext
    private val file = context.getDatabasePath("user-migration-test.db").also { it.delete() }

    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        file,
        BundledSQLiteDriver(),
        UserDatabase::class,
    )

    private val database = UserDatabase.open(context, name = null)
    private val rules = FolderRules(database)

    @After
    fun close() {
        database.close()
        context.deleteDatabase(file.name)
    }

    @Test
    fun versionOneIsCreatedAsItsExportedSchemaSays() = runBlocking<Unit> {
        // Fails when the entities and the committed app/schemas/.../1.json disagree.
        migrations.createDatabase(1).close()

        val opened = UserDatabase.open(context, file.name)
        try {
            assertThat(opened.user().folderRules()).isEmpty()
        } finally {
            opened.close()
        }
    }

    @Test
    fun versionOneMigratesToTwoKeepingItsRulesAndAddingIdentities() = runBlocking<Unit> {
        migrations.createDatabase(1).use { connection ->
            connection.prepare(
                "INSERT INTO library_folder_rule (volume_name, relative_path, mode) VALUES ('external_primary', 'Music/', 1)",
            ).use { it.step() }
            connection.prepare("INSERT INTO user_meta (id, change_counter) VALUES (1, 5)").use { it.step() }
        }

        // Checks the migrated schema against the committed app/schemas/.../2.json.
        migrations.runMigrationsAndValidate(2).close()

        val opened = UserDatabase.open(context, file.name)
        try {
            val dao = opened.user()
            assertThat(dao.folderRules()).containsExactly(FolderRuleEntity("external_primary", "Music/", FolderRuleEntity.INCLUDE))
            assertThat(dao.changeCounter()).isEqualTo(5)
            assertThat(opened.identities().identities()).isEmpty()
        } finally {
            opened.close()
        }
    }

    @Test
    fun versionTwoMigratesToThreeKeepingItsIdentitiesAndAddingOwnCovers() = runBlocking<Unit> {
        migrations.createDatabase(2).use { connection ->
            connection.prepare(
                "INSERT INTO track_identity (id, content_key, start_ms, duration_ms, created_at) VALUES (7, x'0102', 0, 1000, 1)",
            ).use { it.step() }
            connection.prepare("INSERT INTO user_meta (id, change_counter) VALUES (1, 3)").use { it.step() }
        }

        // Checks the migrated schema against the committed app/schemas/.../3.json.
        migrations.runMigrationsAndValidate(3).close()

        val opened = UserDatabase.open(context, file.name)
        try {
            assertThat(opened.identities().identities().map { it.id }).containsExactly(7L)
            assertThat(opened.user().changeCounter()).isEqualTo(3)
            val covers = opened.covers()
            assertThat(covers.trackCovers()).isEmpty()
            assertThat(covers.albumCovers()).isEmpty()
            covers.putTrackCover(7, "t.jpg", 10, 20, setAt = 5)
            covers.putAlbumCover("album", "a.jpg", 30, 40, setAt = 6)
            assertThat(covers.trackCover(7)).isEqualTo(TrackCoverEntity(7, "t.jpg", 10, 20, 5))
            assertThat(covers.albumCover("album")).isEqualTo(AlbumCoverEntity("album", "a.jpg", 30, 40, 6))
        } finally {
            opened.close()
        }
    }

    @Test
    fun versionThreeMigratesToFourKeepingItsDataAndCopyingTheCounterIntoTheRulesCounter() = runBlocking<Unit> {
        migrations.createDatabase(3).use { connection ->
            connection.prepare(
                "INSERT INTO track_identity (id, content_key, start_ms, duration_ms, created_at) VALUES (7, x'0102', 0, 1000, 1)",
            ).use { it.step() }
            connection.prepare("INSERT INTO album_cover (album_key, file_name, width, height, set_at) VALUES ('k', 'a.jpg', 1, 2, 3)")
                .use { it.step() }
            connection.prepare("INSERT INTO user_meta (id, change_counter) VALUES (1, 9)").use { it.step() }
        }

        // Checks the migrated schema against the committed app/schemas/.../4.json.
        migrations.runMigrationsAndValidate(4).close()

        val opened = UserDatabase.open(context, file.name)
        try {
            assertThat(opened.identities().identities().map { it.id }).containsExactly(7L)
            assertThat(opened.covers().albumCover("k")?.fileName).isEqualTo("a.jpg")
            assertThat(opened.user().changeCounter()).isEqualTo(9)
            // The rules mirrored under counter 9 stay mirrored (docs/library.md §3.1).
            assertThat(opened.user().rulesCounter()).isEqualTo(9)
            val favorites = Favorites(opened) { 50 }
            favorites.set(7, favorite = true)
            favorites.setAlbum("mbid:0b8a3a2e-1111-4222-8333-444455556666", favorite = true)
            PlayStats(opened).record(7, startedAt = 60, listenedMs = 70)
            assertThat(opened.favorites().favorites()).containsExactly(FavoriteEntity(7, 50))
            assertThat(opened.favorites().favoriteAlbums().single().releaseMbid)
                .isEqualTo(io.github.lorddetson.piplayer.library.identity.Hex.mbidBytes("0b8a3a2e-1111-4222-8333-444455556666"))
            assertThat(opened.plays().events()).containsExactly(PlayEventEntity(7, 60, 70))
            assertThat(opened.user().identitiesHoldingData()).containsExactly(7L)
        } finally {
            opened.close()
        }
    }

    @Test
    fun favoritesAndPlaysRaiseTheChangeCounterButNotTheRulesCounter() = runBlocking<Unit> {
        val dao = database.user()
        val favorites = Favorites(database)

        favorites.set(1, favorite = true)
        favorites.setAlbum("album", favorite = true)
        PlayStats(database).record(1, startedAt = 5, listenedMs = 6)
        PlayStats(database).setListened(1, startedAt = 5, listenedMs = 60)

        assertThat(dao.changeCounter()).isEqualTo(3)
        assertThat(dao.rulesCounter()).isZero()
        assertThat(database.plays().events().single().listenedMs).isEqualTo(60)

        rules.set("external_primary", "Music/", include = true)
        assertThat(dao.changeCounter()).isEqualTo(4)
        assertThat(dao.rulesCounter()).isEqualTo(1)
    }

    @Test
    fun anIdentityKeepsItsBlobsAndOneKeyHasOneIdentity() = runBlocking<Unit> {
        val dao = database.identities()
        val key = ByteArray(32) { it.toByte() }
        val mbid = ByteArray(16) { (it * 3).toByte() }
        val id = dao.add(
            TrackIdentityEntity(
                contentKey = key, durationMs = 1_000, recordingMbid = mbid, hintVolumeName = "v", hintRelativePath = "p/",
                hintDisplayName = "n", hintSize = 5, createdAt = 1, missingSince = null,
            ),
        )

        val stored = dao.identities().single()
        assertThat(stored.id).isEqualTo(id)
        assertThat(stored.contentKey).isEqualTo(key)
        assertThat(stored.recordingMbid).isEqualTo(mbid)
        assertThat(stored.startMs).isZero()
        org.assertj.core.api.Assertions.assertThatThrownBy {
            runBlocking { dao.add(stored.copy(id = 0)) }
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun everyChangeOfAFolderRuleCountsAsAChangeOfUserData() = runBlocking<Unit> {
        val dao = database.user()
        assertThat(dao.changeCounter()).isZero()

        rules.set("external_primary", "Music/", include = true)
        rules.set("external_primary", "", include = false)
        rules.set("external_primary", "Music/", include = false)
        rules.remove("external_primary", "")

        assertThat(dao.changeCounter()).isEqualTo(4)
        assertThat(rules.all()).containsExactly(FolderRuleEntity("external_primary", "Music/", FolderRuleEntity.EXCLUDE))
    }
}
