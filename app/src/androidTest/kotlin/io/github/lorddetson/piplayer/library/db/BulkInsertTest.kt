package io.github.lorddetson.piplayer.library.db

import androidx.room3.PooledConnection
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bulk writes of a sync (docs/library.md §1.7) through the real SQLite: every value arrives as it was given, whatever
 * form the rows travel in.
 */
@RunWith(AndroidJUnit4::class)
class BulkInsertTest {

    private val database = LibraryDatabase.open(TestFiles.targetContext, file = null)

    @After
    fun close() = database.close()

    private class Values(val id: Long, val text: String? = null, val number: Long? = null, val flag: Boolean? = null, val blob: ByteArray? = null)

    private suspend fun PooledConnection.write(rows: List<Values>, computed: Map<String, String> = emptyMap(), join: String = "") =
        replaceAll("t", listOf("id", "s", "n", "f", "b"), rows, computed, join) { row ->
            long(row.id)
            text(row.text)
            long(row.number)
            flag(row.flag)
            blob(row.blob)
        }

    private fun writeAll(rows: List<Values>) = runBlocking {
        database.withWriteTransaction {
            usePrepared("CREATE TABLE t (id INTEGER PRIMARY KEY, s TEXT, n INTEGER, f INTEGER, b BLOB, kept TEXT)") { it.step() }
            write(rows)
        }
    }

    /** Each row's columns as SQLite holds them: the hex of the text's bytes, the types, the numbers and the blob's hex. */
    private fun stored(sql: String = ROWS) = runBlocking { database.useReaderConnection { it.query(sql) } }

    private suspend fun PooledConnection.query(sql: String): List<List<String?>> = usePrepared(sql) { statement ->
        buildList {
            while (statement.step()) {
                add(List(statement.getColumnCount()) { if (statement.isNull(it)) null else statement.getText(it) })
            }
        }
    }

    private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

    @Test
    fun textArrivesByteForByteWhateverItHolds() {
        val texts = listOf(
            "plain",
            "O'Brien's 'quoted' ''",
            "'",
            "He said \"hi\" \\ back\\slash",
            "line\nbreak\r\ttab",
            "control \u0001\u001F\u007F end",
            "before\u0000after",
            "\u0000",
            "Ёжик в тумане – Beyoncé 🎵 ｱ 𠀀",
            "",
            "value->>0 ); DROP TABLE t; --",
        )
        writeAll(texts.mapIndexed { i, text -> Values(i + 1L, text = text) })

        val rows = stored()
        assertThat(rows.map { it[1] }).containsExactlyElementsOf(texts.map { hexOf(it.encodeToByteArray()) })
        assertThat(rows.map { it[2] }).containsOnly("text")
    }

    @Test
    fun anUnpairedSurrogateBecomesTheReplacementCharacter() {
        val texts = listOf("cut" + Char(0xD83D), Char(0xDE00) + "alone", "a" + Char(0xD83D) + "'q" + Char(0xD83D), "pair " + Char(0xD83D) + Char(0xDE00))
        writeAll(texts.mapIndexed { i, text -> Values(i + 1L, text = text) })

        assertThat(stored().map { it[1] }).containsExactlyElementsOf(
            listOf("cut�", "�alone", "a�'q�", "pair 😀").map { hexOf(it.encodeToByteArray()) },
        )
    }

    @Test
    fun numbersFlagsAndMissingValuesKeepTheirTypes() {
        writeAll(
            listOf(
                Values(1, number = Long.MIN_VALUE, flag = true),
                Values(2, number = Long.MAX_VALUE, flag = false),
                Values(3, number = -1),
                Values(4, number = 0),
                Values(5),
            ),
        )

        val rows = stored()
        assertThat(rows.map { it[3] }).containsExactly(Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(), "-1", "0", null)
        assertThat(rows.map { it[4] }).containsExactly("integer", "integer", "integer", "integer", "null")
        assertThat(rows.map { it[5] }).containsExactly("1", "0", null, null, null)
        assertThat(rows.map { it[6] }).containsExactly("integer", "integer", "null", "null", "null")
        assertThat(rows.map { it[2] }).containsOnly("null")
    }

    @Test
    fun blobsArriveAsBlobsAlsoWhenEmpty() {
        val all = ByteArray(256) { it.toByte() }
        writeAll(listOf(Values(1, blob = all), Values(2, blob = ByteArray(0)), Values(3, blob = null)))

        val rows = stored()
        // hex() of NULL is empty too; the types tell them apart.
        assertThat(rows.map { it[7] }).containsExactly(hexOf(all), "", "")
        assertThat(rows.map { it[8] }).containsExactly("blob", "blob", "null")
    }

    @Test
    fun manyRowsArriveInOrderAcrossStatements() {
        val count = 4_321
        writeAll(List(count) { i -> Values(i + 1L, text = "row ${i + 1}'", number = i * 3L) })

        val rows = stored()
        assertThat(rows).hasSize(count)
        assertThat(rows.map { it[0] }).containsExactlyElementsOf((1..count).map { it.toString() })
        assertThat(rows[count - 1][1]).isEqualTo(hexOf("row $count'".encodeToByteArray()))
        assertThat(rows[count - 1][3]).isEqualTo(((count - 1) * 3L).toString())
    }

    /** Counts the statements of [counted] text that are prepared and not closed yet. */
    private class CountingDriver(private val driver: SQLiteDriver, private val counted: (String) -> Boolean) : SQLiteDriver by driver {
        val live = AtomicInteger()
        val prepared = AtomicInteger()

        override fun open(fileName: String): SQLiteConnection = CountingConnection(driver.open(fileName))

        private inner class CountingConnection(private val connection: SQLiteConnection) : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                if (!counted(sql)) return statement
                live.incrementAndGet()
                prepared.incrementAndGet()
                return object : SQLiteStatement by statement {
                    override fun close() {
                        live.decrementAndGet()
                        statement.close()
                    }
                }
            }
        }
    }

    @Test
    fun noBulkStatementStaysPreparedAfterItsWrite() {
        // Room keeps prepared statements by their text; a bulk statement's text is its rows and never comes again.
        val driver = CountingDriver(BundledSQLiteDriver()) { it.startsWith("INSERT OR REPLACE INTO t ") }
        val counted = Room.inMemoryDatabaseBuilder<LibraryDatabase>(TestFiles.targetContext)
            .setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            runBlocking {
                counted.withWriteTransaction {
                    usePrepared("CREATE TABLE t (id INTEGER PRIMARY KEY, s TEXT, n INTEGER, f INTEGER, b BLOB, kept TEXT)") { it.step() }
                    write(List(4_321) { i -> Values(i + 1L, text = "row $i") })
                    assertThat(driver.live.get()).describedAs("bulk statements still prepared").isZero()
                }
            }
            assertThat(driver.prepared.get()).describedAs("bulk statements").isEqualTo(3)
        } finally {
            counted.close()
        }
    }

    @Test
    fun aChunkWithoutRowsWritesNothingAndTheOthersArrive() {
        writeAll(emptyList())
        runBlocking {
            database.withWriteTransaction {
                // As the artists of a chunk of files that have none.
                val chunks = listOf(listOf(Values(1, text = "a")), emptyList(), listOf(Values(2, text = "b")))
                replaceInChunks("t", listOf("id", "s"), chunks.map { suspend { it } }) { row ->
                    long(row.id)
                    text(row.text)
                }
            }
        }

        assertThat(stored("SELECT id, s FROM t ORDER BY id")).containsExactly(listOf("1", "a"), listOf("2", "b"))
    }

    @Test
    fun computedColumnsReadTheRowAndTheRowsTheTableHeldBefore() {
        writeAll(listOf(Values(1, text = "a", number = 10), Values(2, text = "b", number = 20)))
        runBlocking {
            database.withWriteTransaction {
                usePrepared("UPDATE t SET kept = 'read ' || id") { it.step() }
                // Row 1 keeps what it had while its number stays the same; row 2 changed; row 3 is new.
                write(
                    listOf(Values(1, text = "a2", number = 10), Values(2, text = "b2", number = 21), Values(3, text = "c", number = 30)),
                    computed = mapOf("kept" to "o.kept"),
                    join = "LEFT JOIN t o ON o.id = ${rowValue(0)} AND o.n = ${rowValue(2)}",
                )
            }
        }

        val rows = stored("SELECT id, s, kept FROM t ORDER BY id")
        assertThat(rows).containsExactly(listOf("1", "a2", "read 1"), listOf("2", "b2", null), listOf("3", "c", null))
    }

    private fun schema() = stored(SCHEMA)

    @Test
    fun indexesBuiltAfterTheRowsAreTheIndexesOfTheSchema() {
        val before = schema()
        runBlocking {
            database.withWriteTransaction {
                withIndexesBuiltAfter(listOf("audio_file", "track_display", "display_artist")) {
                    assertThat(query(SCHEMA).map { it[1] }).doesNotContain("index_track_display_album_key")
                }
            }
        }

        assertThat(schema()).containsExactlyElementsOf(before)
        assertThat(before.map { it[1] }).contains("index_track_display_title_sort_search_text", "index_audio_file_media_store_id")
        assertThat(stored("PRAGMA integrity_check")).containsExactly(listOf("ok"))
    }

    @Test
    fun aFailureWhileTheIndexesAreDroppedKeepsThem() {
        val before = schema()

        assertThatThrownBy {
            runBlocking {
                database.withWriteTransaction {
                    withIndexesBuiltAfter(listOf("audio_file", "track_display", "display_artist")) {
                        error("The write failed")
                    }
                }
            }
        }.hasMessageContaining("The write failed")

        assertThat(schema()).containsExactlyElementsOf(before)
    }

    private companion object {
        const val ROWS = "SELECT id, hex(s), typeof(s), n, typeof(n), f, typeof(f), hex(b), typeof(b), kept FROM t ORDER BY id"
        const val SCHEMA = "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name"
    }
}
