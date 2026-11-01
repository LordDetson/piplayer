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
