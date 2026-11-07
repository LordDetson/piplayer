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
