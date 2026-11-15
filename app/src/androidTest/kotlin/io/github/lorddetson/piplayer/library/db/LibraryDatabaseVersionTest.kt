package io.github.lorddetson.piplayer.library.db

import android.database.sqlite.SQLiteDatabase
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library database version 13 (TI §10.1, §11.2 M3 "Library database version 13"): an older file is dropped and built
 * again, with the tables and columns of identification.
 */
@RunWith(AndroidJUnit4::class)
class LibraryDatabaseVersionTest {

    private val context = TestFiles.targetContext
    private val file = File(context.cacheDir, "library-version-test.db")

    @After
    fun delete() {
        SQLiteDatabase.deleteDatabase(file)
    }

    /** A file as version 12 left it, reduced to one of its tables with a row and a table version 13 no longer has. */
    private fun writeVersion12() {
        SQLiteDatabase.deleteDatabase(file)
        BundledSQLiteDriver().open(file.absolutePath).let { connection ->
            try {
                for (sql in listOf(
                    "CREATE TABLE audio_file (id INTEGER PRIMARY KEY, display_name TEXT)",
                    "INSERT INTO audio_file VALUES (1, 'old.mp3')",
                    "CREATE TABLE cover_cache (release_mbid TEXT PRIMARY KEY, path TEXT)",
                    "CREATE TABLE library_meta (id INTEGER PRIMARY KEY, mirrored_user_counter INTEGER NOT NULL)",
                    "INSERT INTO library_meta VALUES (1, 41)",
                    "PRAGMA user_version = 12",
                )) {
                    connection.prepare(sql).use { it.step() }
                }
            } finally {
                connection.close()
            }
        }
    }

    private fun LibraryDatabase.strings(sql: String): List<String> = runBlocking {
        useReaderConnection { connection ->
            connection.usePrepared(sql) { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }
        }
    }

    private fun LibraryDatabase.columns(table: String) = strings("SELECT name FROM pragma_table_info('$table') ORDER BY cid")

    @Test
    fun anOlderLibraryDatabaseIsDroppedAndBuiltAgainAtVersion13() = runBlocking<Unit> {
        writeVersion12()
        val database = LibraryDatabase.open(context, file)
        try {
            assertThat(database.library().files()).isEmpty()
            assertThat(database.library().mirroredRulesCounter()).isZero()
            assertThat(database.strings("PRAGMA user_version")).containsExactly("13")

            val tables = database.strings("SELECT name FROM sqlite_master WHERE type = 'table'")
            assertThat(tables).contains(
                "audio_file", "track_display", "track_mirror", "fingerprint", "library_meta",
                "lookup_job", "candidate", "identification_trace", "release_cache", "api_cache", "decided_mirror",
            )
            assertThat(tables).doesNotContain("cover_cache")

            assertThat(database.columns("fingerprint")).contains("content_key", "fingerprint", "format", "acoustid_id", "decoder_path", "computed_at")
            assertThat(database.columns("track_display")).contains("missing_info_reasons", "missing_info_decision", "badge_state")
            assertThat(database.columns("library_meta")).contains("mirrored_rules_counter", "mirrored_change_counter")
                .doesNotContain("mirrored_user_counter")
            assertThat(database.columns("decided_mirror")).containsExactly("file_id", "field", "value", "level", "decision_id")
            assertThat(database.columns("release_cache")).containsExactly("release_mbid", "payload_json", "fetched_at")
            assertThat(database.columns("api_cache")).containsExactly("key", "status", "body", "fetched_at", "ttl", "busy_in_a_row")
            // The working rows of identification are kept by content key, not by file ID (TI §3.1, §10.1).
            for (table in listOf("lookup_job", "candidate", "identification_trace")) {
                assertThat(database.columns(table)).contains("content_key").doesNotContain("file_id")
            }
            assertThat(database.columns("candidate")).containsExactly(
                "content_key", "position", "source", "recording_mbid", "release_mbid", "score", "reason", "payload_json",
            )
            assertThat(database.columns("lookup_job")).containsExactly(
                "content_key", "batch_uuid", "phase", "status", "attempts", "next_attempt_at", "last_error",
            )
            assertThat(database.columns("identification_trace")).containsExactly(
                "content_key", "run_id", "position", "started_at", "stage", "provider", "http_status", "result_count",
                "best_score", "elapsed_ms", "note",
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun aDisplayRowStartsWithoutADecisionOrABadge() = runBlocking<Unit> {
        val database = LibraryDatabase.open(context, null)
        try {
            // As the writers of display rows write them: naming only their own columns.
            database.withWriteTransaction {
                usePrepared(
                    "INSERT INTO track_display (file_id, title, search_text, title_sort, file_sort) VALUES (1, 'Song', 'song', x'01', x'01')",
                ) { it.step() }
            }
            val display = database.library().displays().single()
            assertThat(display.missingInfoDecision).isEqualTo(TrackDisplayEntity.DECISION_NONE)
            assertThat(display.badgeState).isEqualTo(TrackDisplayEntity.BADGE_NONE)
        } finally {
            database.close()
        }
    }

    @Test
    fun aCachedFingerprintKeepsItsAcoustIdTrackAndDecoderPath() = runBlocking<Unit> {
        val database = LibraryDatabase.open(context, null)
        try {
            val key = "ab".repeat(32)
            val stored = FingerprintEntity(key, byteArrayOf(0, 0, 0, 1), "mpeg", 1_000, acoustIdId = "acoustid-track", decoderPath = "platform")
            database.library().saveFingerprint(stored)
            assertThat(database.library().fingerprint(key)).isEqualTo(stored)
            val bare = FingerprintEntity("cd".repeat(32), byteArrayOf(0, 0, 0, 2), "flac", 2_000)
            database.library().saveFingerprint(bare)
            assertThat(database.library().fingerprint(bare.contentKey)!!.acoustIdId).isNull()
            assertThat(database.library().fingerprint(bare.contentKey)!!.decoderPath).isNull()
        } finally {
            database.close()
        }
    }
}
