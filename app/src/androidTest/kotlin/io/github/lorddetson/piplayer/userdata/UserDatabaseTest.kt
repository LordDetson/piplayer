package io.github.lorddetson.piplayer.userdata

import androidx.room3.PooledConnection
import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
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

    /** An identity [id] for user data to refer to: a write for an identity that does not exist goes nowhere. */
    private fun identity(id: Long) = TrackIdentityEntity(
        id = id, contentKey = ByteArray(32) { id.toByte() }, durationMs = 1_000, recordingMbid = null, hintVolumeName = null,
        hintRelativePath = null, hintDisplayName = null, hintSize = null, createdAt = 1, missingSince = null,
    )

    /** The rows [sql] reads, as text. */
    private suspend fun rows(db: UserDatabase, sql: String): List<List<String?>> = db.useReaderConnection { it.query(sql) }

    private suspend fun PooledConnection.query(sql: String): List<List<String?>> = usePrepared(sql) { statement ->
        buildList {
            while (statement.step()) {
                add(List(statement.getColumnCount()) { if (statement.isNull(it)) null else statement.getText(it) })
            }
        }
    }

    /** Each `play_stats` row: identity, play count and the folded listening counts (docs/library.md §15.7). */
    private suspend fun counts(db: UserDatabase) =
        rows(db, "SELECT identity_id, play_count, early_skips, late_skips, finished FROM play_stats ORDER BY identity_id")
            .map { row -> row.map { it!!.toLong() } }

    private suspend fun execute(db: UserDatabase, sql: String) = db.useWriterConnection { c -> c.usePrepared(sql) { it.step() } }

    @Test
    fun freshDatabaseHasWriteHistoryAndBackups() = runBlocking<Unit> {
        assertThat(rows(database, "PRAGMA user_version")).containsExactly(listOf("7"))
        assertThat(rows(database, "SELECT name FROM sqlite_master WHERE name IN ('applied_change', 'tag_backup') ORDER BY name"))
            .containsExactly(listOf("applied_change"), listOf("tag_backup"))
    }

    @Test
    fun versionSixMigratesWithoutChangingExistingUserState() = runBlocking<Unit> {
        migrations.createDatabase(6).use { connection ->
            connection.prepare("INSERT INTO user_meta (id, change_counter) VALUES (1, 37)").use { it.step() }
        }
        migrations.runMigrationsAndValidate(7).close()

        val opened = UserDatabase.open(context, file.name)
        try {
            assertThat(rows(opened, "SELECT change_counter FROM user_meta WHERE id=1")).containsExactly(listOf("37"))
            assertThat(rows(opened, "SELECT id FROM applied_change")).isEmpty()
            assertThat(rows(opened, "SELECT id FROM tag_backup")).isEmpty()
        } finally {
            opened.close()
        }
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
    fun versionFourMigratesToFiveKeepingItsDataAndAddingPlaylists() = runBlocking<Unit> {
        migrations.createDatabase(4).use { connection ->
            connection.prepare(
                "INSERT INTO track_identity (id, content_key, start_ms, duration_ms, created_at) VALUES (7, x'0102', 0, 1000, 1)",
            ).use { it.step() }
            connection.prepare("INSERT INTO favorite (identity_id, added_at) VALUES (7, 2)").use { it.step() }
            connection.prepare("INSERT INTO user_meta (id, change_counter, rules_counter) VALUES (1, 9, 4)").use { it.step() }
        }

        // Checks the migrated schema against the committed app/schemas/.../5.json.
        migrations.runMigrationsAndValidate(5).close()

        val opened = UserDatabase.open(context, file.name)
        try {
            assertThat(opened.identities().identities().map { it.id }).containsExactly(7L)
            assertThat(opened.favorites().favorites()).containsExactly(FavoriteEntity(7, 2))
            assertThat(opened.user().changeCounter()).isEqualTo(9)
            assertThat(opened.user().rulesCounter()).isEqualTo(4)
            val playlists = Playlists(opened) { 50 }
            assertThat(playlists.playlists()).isEmpty()
            val id = playlists.create("Road trip")
            opened.identities().add(identity(8))
            playlists.add(id, listOf(NewEntry(8, "external_primary", "Music/", "a.mp3", 1_000)))
            assertThat(playlists.entries(id).map { it.identityId }).containsExactly(8L)
            // An identity a playlist refers to holds user data (docs/library.md §16.2).
            assertThat(opened.user().identitiesHoldingData()).containsExactlyInAnyOrder(7L, 8L)
        } finally {
            opened.close()
        }
    }

    @Test
    fun versionFiveMigratesToSixKeepingAllItsDataAndAddingEmptyDecisionTables() = runBlocking<Unit> {
        migrations.createDatabase(5).use { connection ->
            for (sql in listOf(
                "INSERT INTO library_folder_rule (volume_name, relative_path, mode) VALUES ('external_primary', 'Music/', 1)",
                "INSERT INTO track_identity (id, content_key, start_ms, duration_ms, recording_mbid, hint_volume_name, " +
                    "hint_relative_path, hint_display_name, hint_size, created_at) VALUES (7, x'0102', 0, 1000, x'0a0b', " +
                    "'external_primary', 'Music/', 'a.mp3', 10, 1)",
                "INSERT INTO track_cover (identity_id, file_name, width, height, set_at) VALUES (7, 't.jpg', 1, 2, 3)",
                "INSERT INTO album_cover (album_key, file_name, width, height, set_at) VALUES ('k', 'a.jpg', 1, 2, 3)",
                "INSERT INTO favorite (identity_id, added_at) VALUES (7, 2)",
                "INSERT INTO favorite_album (album_key, release_mbid, added_at) VALUES ('k', NULL, 3)",
                "INSERT INTO album_member (album_key, identity_id) VALUES ('k', 7)",
                "INSERT INTO play_event (identity_id, started_at, listened_ms) VALUES (7, 4, 5)",
                "INSERT INTO play_stats (identity_id, play_count, listened_ms, last_played_at) VALUES (7, 6, 7, 8)",
                "INSERT INTO playlist (id, name, created_at, updated_at) VALUES (3, 'Road trip', 1, 2)",
                "INSERT INTO playlist_entry (id, playlist_id, position, identity_id, hint_volume_name, hint_relative_path, " +
                    "hint_display_name, hint_duration_ms) VALUES (1, 3, 0, 7, 'external_primary', 'Music/', 'a.mp3', 1000)",
                "INSERT INTO user_meta (id, change_counter, rules_counter) VALUES (1, 11, 4)",
            )) {
                connection.prepare(sql).use { it.step() }
            }
        }

        // Checks the migrated schema against the committed app/schemas/.../6.json.
        migrations.runMigrationsAndValidate(6).close()

        val opened = UserDatabase.open(context, file.name)
        try {
            assertThat(opened.user().folderRules()).containsExactly(FolderRuleEntity("external_primary", "Music/", FolderRuleEntity.INCLUDE))
            assertThat(opened.identities().identities().single().hintDisplayName).isEqualTo("a.mp3")
            assertThat(opened.covers().trackCover(7)?.fileName).isEqualTo("t.jpg")
            assertThat(opened.covers().albumCover("k")?.fileName).isEqualTo("a.jpg")
            assertThat(opened.favorites().favorites()).containsExactly(FavoriteEntity(7, 2))
            assertThat(opened.favorites().favoriteAlbums().single().albumKey).isEqualTo("k")
            assertThat(opened.favorites().members()).containsExactly(AlbumMemberEntity("k", 7))
            assertThat(opened.plays().events()).containsExactly(PlayEventEntity(7, 4, 5))
            assertThat(Playlists(opened).entries(3).map { it.identityId }).containsExactly(7L)
            assertThat(opened.user().changeCounter()).isEqualTo(11)
            assertThat(opened.user().rulesCounter()).isEqualTo(4)
            // The new tables start empty, and work.
            val decisions = opened.decisions()
            assertThat(decisions.liveValueDecisions()).isEmpty()
            assertThat(decisions.releaseChoiceCount()).isZero()
            assertThat(decisions.fileStates(7)).isEmpty()
            assertThat(decisions.savedCover(1)).isNull()
            // So do the listening outcomes (docs/library.md §15.7); the rows of play_stats get listening counts of 0.
            assertThat(opened.listens().events()).isEmpty()
            assertThat(counts(opened)).containsExactly(listOf(7L, 6L, 0L, 0L, 0L))
            assertThat(rows(opened, "SELECT name FROM sqlite_master WHERE name = 'listen_stats'")).isEmpty()
            // play_event keeps its rows under its new key, without row IDs and without its index on the start.
            val playEvent = rows(opened, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'play_event'").single().single()!!
            assertThat(playEvent).contains("PRIMARY KEY(`started_at`, `identity_id`)").contains("WITHOUT ROWID")
            assertThat(rows(opened, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'play_event'")).isEmpty()
            assertThat(opened.plays().totalOf(7)).isEqualTo(PlayTotalRow(playCount = 7, lastPlayedAt = 8))
            assertThat(Listens(opened).record(7, startedAt = 9, listenedMs = 10, durationMs = 1_000, ListenOutcome.EARLY_SKIP)).isEqualTo(7)
            assertThat(opened.listens().events()).containsExactly(ListenEventEntity(9, 7, 10, 1_000, ListenOutcome.EARLY_SKIP.code))
            assertThat(opened.user().changeCounter()).isEqualTo(11)
            opened.identities().add(identity(8).copy(contentKey = io.github.lorddetson.piplayer.library.identity.Hex.bytes("%064x".format(8))))
            val applied = checkNotNull(Decisions(opened).apply(DecisionFixtures.request("%064x".format(8))))
            assertThat(applied.counter).isEqualTo(12)
            assertThat(opened.user().identitiesHoldingData()).containsExactlyInAnyOrder(7L, 8L)
        } finally {
            opened.close()
        }
    }

    @Test
    fun favoritesAndPlaysRaiseTheChangeCounterButNotTheRulesCounter() = runBlocking<Unit> {
        val dao = database.user()
        val favorites = Favorites(database)
        database.identities().add(identity(1))

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

    @Test
    fun severalFavoritesAreSetInOneTransactionThatRaisesTheChangeCounterOnce() = runBlocking<Unit> {
        // Selected songs made favorites at once (docs/library.md §20.3).
        val dao = database.user()
        val favorites = Favorites(database)
        for (id in 1L..4L) database.identities().add(identity(id))
        favorites.set(2, favorite = true)
        val before = dao.changeCounter()

        // Song 109 has identity 9, which does not exist and has no key that leads anywhere: it is gone. Song 102 is a
        // favorite already and does not count as changed; song 103 stands twice (a playlist may hold it twice) and changes once.
        val written = favorites.setAll(listOf(song(101, 1), song(102, 2), song(103, 3), song(109, 9), song(103, 3)), favorite = true)

        assertThat(written).isEqualTo(Favorites.Written(changed = 2, gone = 1))
        assertThat(dao.changeCounter()).isEqualTo(before + 1)
        assertThat(dao.rulesCounter()).isZero()
        assertThat(favorites.favoriteIdentities(listOf(1L, 2L, 3L, 4L, 9L))).containsExactlyInAnyOrder(1L, 2L, 3L)

        // An identity deleted since the read goes to the identity that holds its key now (§8.1): 7 is 4, not a favorite.
        assertThat(favorites.setAll(listOf(song(101, 1), song(103, 3), song(104, 7, ByteArray(32) { 4 })), favorite = false))
            .isEqualTo(Favorites.Written(changed = 2, gone = 0))
        assertThat(favorites.favoriteIdentities(listOf(1L, 2L, 3L, 4L))).containsExactly(2L)
        assertThat(dao.changeCounter()).isEqualTo(before + 2)

        // Nothing changed raises nothing: a gone identity, and a favorite made one again.
        assertThat(favorites.setAll(listOf(song(109, 9), song(102, 2)), favorite = true)).isEqualTo(Favorites.Written(changed = 0, gone = 1))
        assertThat(dao.changeCounter()).isEqualTo(before + 2)
    }

    @Test
    fun copiesThatShareOneIdentityCountAsTheSongsSelected() = runBlocking<Unit> {
        // Songs 105 and 106 are byte-identical copies, so they share identity 4 (docs/library.md §8.1); both were selected.
        val favorites = Favorites(database)
        for (id in 1L..4L) database.identities().add(identity(id))

        assertThat(favorites.setAll(listOf(song(105, 4), song(106, 4)), favorite = true)).isEqualTo(Favorites.Written(changed = 2, gone = 0))
        assertThat(favorites.favoriteIdentities(listOf(4L))).containsExactly(4L)

        // Counted the same way when the identity they were read by went: both copies are still being read.
        assertThat(favorites.setAll(listOf(song(105, 9), song(106, 9)), favorite = false)).isEqualTo(Favorites.Written(changed = 0, gone = 2))

        // An identity deleted since the read that now leads to 4, as the other copy's identity does: both changed.
        assertThat(favorites.setAll(listOf(song(105, 4), song(106, 8, ByteArray(32) { 4 })), favorite = false))
            .isEqualTo(Favorites.Written(changed = 2, gone = 0))
        assertThat(favorites.favoriteIdentities(listOf(4L))).isEmpty()
    }

    private fun song(fileId: Long, identityId: Long, contentKey: ByteArray? = null) = Favorites.Song(fileId, identityId, contentKey)

    @Test
    fun favoritesAreReadInChunksOfAnyLength() = runBlocking<Unit> {
        val favorites = Favorites(database)
        database.identities().addAll(
            (1L..1_200L).map { id -> identity(id).copy(contentKey = ByteArray(32).also { java.nio.ByteBuffer.wrap(it).putLong(id) }) },
        )
        favorites.setAll((1L..1_200L step 2).map { song(it, it) }, favorite = true)

        val read = favorites.favoriteIdentities((1L..1_200L).toList())

        assertThat(read).hasSize(600).allMatch { it % 2 == 1L }
    }

    @Test
    fun aListeningOutcomeIsWrittenOnceByItsIdentityAndRaisesNoCounter() = runBlocking<Unit> {
        val dao = database.user()
        val listens = Listens(database)
        database.identities().add(identity(1))
        database.identities().add(identity(4))

        assertThat(listens.record(1, startedAt = 5, listenedMs = 29_900, durationMs = 180_000, ListenOutcome.EARLY_SKIP)).isEqualTo(1)
        // A playback keeps its first outcome (docs/playback.md §12.3).
        assertThat(listens.record(1, startedAt = 5, listenedMs = 180_000, durationMs = 180_000, ListenOutcome.FINISHED)).isEqualTo(1)
        assertThat(listens.record(1, startedAt = 6, listenedMs = 90_000, durationMs = null, ListenOutcome.LATE_SKIP)).isEqualTo(1)
        // An identity deleted since the read goes to the identity that holds its key now (§8.1); with none, nothing.
        assertThat(listens.record(7, startedAt = 8, listenedMs = 200_000, durationMs = 200_000, ListenOutcome.FINISHED, ByteArray(32) { 4 }))
            .isEqualTo(4)
        assertThat(listens.record(9, startedAt = 9, listenedMs = 1_000, durationMs = 200_000, ListenOutcome.EARLY_SKIP)).isNull()
        assertThat(listens.record(9, startedAt = 9, listenedMs = 1_000, durationMs = 200_000, ListenOutcome.EARLY_SKIP, ByteArray(32) { 9 }))
            .isNull()

        assertThat(database.listens().events()).containsExactly(
            ListenEventEntity(5, 1, 29_900, 180_000, ListenOutcome.EARLY_SKIP.code),
            ListenEventEntity(6, 1, 90_000, null, ListenOutcome.LATE_SKIP.code),
            ListenEventEntity(8, 4, 200_000, 200_000, ListenOutcome.FINISHED.code),
        )
        // Nothing of it is mirrored.
        assertThat(dao.changeCounter()).isZero()
        assertThat(dao.rulesCounter()).isZero()

        listens.clear()
        assertThat(database.listens().events()).isEmpty()
    }

    @Test
    fun listeningsOlderThanNinetyDaysAreFoldedIntoTheCountsOfPlayStats() = runBlocking<Unit> {
        val day = 24L * 60 * 60 * 1_000
        val now = 1_800_000_000_000L
        val listens = Listens(database)
        for (id in 1L..3L) database.identities().add(identity(id))
        // Identity 1 has an earlier fold of plays and listenings: its counts add up.
        execute(
            database,
            "INSERT INTO play_stats (identity_id, play_count, listened_ms, last_played_at, early_skips, late_skips, finished) " +
                "VALUES (1, 5, 1000, 7, 4, 3, 2)",
        )
        val old = now - 91 * day
        listens.record(1, old, 10_000, 180_000, ListenOutcome.EARLY_SKIP)
        listens.record(1, old + 1, 60_000, 180_000, ListenOutcome.LATE_SKIP)
        listens.record(1, old + 2, 180_000, 180_000, ListenOutcome.FINISHED)
        listens.record(2, old + 3, 5_000, null, ListenOutcome.EARLY_SKIP)
        // Exactly 90 days old stays, as a play does (docs/library.md §15.5).
        listens.record(2, now - 90 * day, 7_000, 180_000, ListenOutcome.EARLY_SKIP)
        listens.record(3, now - day, 180_000, 180_000, ListenOutcome.FINISHED)
        PlayStats(database).record(1, old, 120_000)
        val counter = database.user().changeCounter()

        PlayStats(database).fold(now)

        // Identity 2 has folded listenings but no play: a row with a play count of 0.
        assertThat(counts(database)).containsExactly(listOf(1L, 6L, 5L, 4L, 3L), listOf(2L, 0L, 1L, 0L, 0L))
        assertThat(rows(database, "SELECT listened_ms, last_played_at FROM play_stats WHERE identity_id = 2"))
            .containsExactly(listOf("0", null))
        assertThat(database.listens().events().map { it.identityId to it.startedAt })
            .containsExactly(2L to now - 90 * day, 3L to now - day)
        // Such a row is no play: the mirror and the song details read only rows with one (docs/library.md §15.5).
        assertThat(database.plays().playedKeys().map { it.identityId to it.playCount }).containsExactly(1L to 6L)
        assertThat(database.plays().totalOf(2)).isEqualTo(PlayTotalRow(playCount = 0, lastPlayedAt = null))
        // The fold changes nothing mirrored.
        assertThat(database.user().changeCounter()).isEqualTo(counter)
    }

    @Test
    fun listeningOutcomesKeepNoIdentity() = runBlocking<Unit> {
        for (id in 1L..4L) database.identities().add(identity(id))
        Listens(database).record(1, startedAt = 5, listenedMs = 1_000, durationMs = 180_000, ListenOutcome.EARLY_SKIP)
        execute(
            database,
            "INSERT INTO play_stats (identity_id, play_count, listened_ms, last_played_at, early_skips, late_skips, finished) " +
                "VALUES (2, 0, 0, NULL, 1, 0, 0), (3, 2, 1000, 7, 1, 0, 0)",
        )
        PlayStats(database).record(4, startedAt = 6, listenedMs = 120_000)

        // Like album members, they are no user data the attach pass keeps an identity for (docs/library.md §8.1, §15.7):
        // only a row with a play and a play event hold theirs.
        assertThat(database.user().identitiesHoldingData()).containsExactlyInAnyOrder(3L, 4L)
    }
}
