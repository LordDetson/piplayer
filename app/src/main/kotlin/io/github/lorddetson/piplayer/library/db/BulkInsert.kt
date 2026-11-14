package io.github.lorddetson.piplayer.library.db

import androidx.room3.PooledConnection
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Rows of values as the SQL literals of one multi-row `VALUES` list, appended to [sql] (docs/library.md §1.3). SQLite
 * parses literals about three times as fast as it takes the same values out of a JSON parameter, which the bulk writes
 * used before.
 */
internal class SqlRows(private val sql: StringBuilder = StringBuilder()) {

    private var rows = 0

    /** The values of one row, in column order. */
    inner class Row {
        private var values = 0

        fun text(value: String?) {
            separate()
            if (value == null) sql.append(NULL) else quote(value)
        }

        fun long(value: Long?) {
            separate()
            if (value == null) sql.append(NULL) else sql.append(value)
        }

        /** True as 1 and false as 0, as SQLite stores them. */
        fun flag(value: Boolean?) {
            separate()
            sql.append(
                when (value) {
                    null -> NULL
                    true -> "1"
                    false -> "0"
                },
            )
        }

        fun blob(value: ByteArray?) {
            separate()
            if (value == null) {
                sql.append(NULL)
            } else {
                sql.append("X'")
                hex(value)
                sql.append('\'')
            }
        }

        internal fun start() {
            values = 0
        }

        private fun separate() {
            if (values++ > 0) sql.append(',')
        }
    }

    /** One for all rows: a first sync writes 80 000 of them. */
    private val current = Row()

    fun row(values: Row.() -> Unit) {
        if (rows++ > 0) sql.append(',')
        sql.append('(')
        current.start()
        current.values()
        sql.append(')')
    }

    override fun toString(): String = sql.toString()

    private fun hex(bytes: ByteArray) {
        for (byte in bytes) {
            val b = byte.toInt() and 0xff
            sql.append(HEX[b ushr 4]).append(HEX[b and 0xf])
        }
    }

    /**
     * A quote is doubled. An unpaired surrogate (a UTF-16 tag cut off in the middle of a pair) becomes U+FFFD, as SQLite
     * cannot store it. SQL text ends at a NUL character, so a text that holds one goes as the bytes of its UTF-8.
     */
    private fun quote(value: String) {
        // Most values need no care: those go in whole.
        if (value.none(::needsCare)) {
            sql.append('\'').append(value).append('\'')
            return
        }
        val paired = pairedOnly(value)
        if ('\u0000' in paired) {
            sql.append("CAST(X'")
            hex(paired.encodeToByteArray())
            sql.append("' AS TEXT)")
            return
        }
        sql.append('\'')
        for (c in paired) {
            if (c == '\'') sql.append("''") else sql.append(c)
        }
        sql.append('\'')
    }

    private fun pairedOnly(value: String): String {
        if (value.none { it.isSurrogate() }) return value
        val out = StringBuilder(value.length)
        for ((i, c) in value.withIndex()) {
            when {
                c.isHighSurrogate() && value.getOrNull(i + 1)?.isLowSurrogate() != true -> out.append(REPLACEMENT)
                c.isLowSurrogate() && value.getOrNull(i - 1)?.isHighSurrogate() != true -> out.append(REPLACEMENT)
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** A quote, a NUL, or half of a surrogate pair, which may be unpaired. */
    private fun needsCare(c: Char) = c == '\'' || c == '\u0000' || c.isSurrogate()

    private companion object {
        const val NULL = "NULL"
        const val REPLACEMENT = '�'
        const val HEX = "0123456789abcdef"
    }
}

/** The value of column [i] of the row being written, for the computed columns and the join of [replaceAll]. */
internal fun rowValue(i: Int): String = "v.column${i + 1}"

/**
 * Writes [rows] into [table] with `INSERT OR REPLACE`, [ROWS_PER_STATEMENT] rows per statement, each statement holding
 * its rows as literals ([SqlRows]): binding every value on its own made up most of a first sync of 20 000 files (docs/
 * library.md §1.3). [values] gives one row's [columns] in order. [computed] adds columns whose values are SQL
 * expressions; they may read the row's values as [rowValue] and, through [join], the rows the table held before the
 * statement.
 */
internal suspend fun <T> PooledConnection.replaceAll(
    table: String,
    columns: List<String>,
    rows: List<T>,
    computed: Map<String, String> = emptyMap(),
    join: String = "",
    values: SqlRows.Row.(T) -> Unit,
) = replaceInChunks(table, columns, rows.chunked(ROWS_PER_STATEMENT).map { chunk -> suspend { chunk } }, computed, join, values)

/**
 * [replaceAll] for rows that [chunks] give, one statement per chunk, written in order; a chunk may wait for its rows. The
 * next statements are built on other threads while SQLite runs the one before: built on the writing thread, they took
 * a third of the writes of a first sync (docs/library.md §1.3). [values] must not touch the database.
 */
internal suspend fun <T> PooledConnection.replaceInChunks(
    table: String,
    columns: List<String>,
    chunks: List<suspend () -> List<T>>,
    computed: Map<String, String> = emptyMap(),
    join: String = "",
    values: SqlRows.Row.(T) -> Unit,
) {
    val names = (columns + computed.keys).joinToString(",")
    // The plain form as long as nothing reads the rows; otherwise the rows are a table `v` with columns column1, column2...
    val (head, tail) = if (computed.isEmpty() && join.isEmpty()) {
        "INSERT OR REPLACE INTO $table ($names) VALUES " to ""
    } else {
        val selected = (columns.indices.map(::rowValue) + computed.values).joinToString(",")
        "INSERT OR REPLACE INTO $table ($names) SELECT $selected FROM (VALUES " to ") AS v $join"
    }
    coroutineScope {
        // Null for a chunk without rows, which makes no statement.
        val ahead = ArrayDeque<Deferred<String?>>()
        var next = 0
        fun buildAhead() {
            while (next < chunks.size && ahead.size < CHUNKS_AHEAD) {
                val chunk = chunks[next++]
                // Not on the default threads, which the sync's resolving may keep busy (TrackDisplayWriter.resolving).
                ahead += async(Dispatchers.IO) {
                    val rows = chunk()
                    if (rows.isEmpty()) return@async null
                    val sql = StringBuilder(head)
                    SqlRows(sql).apply { rows.forEach { row -> row { values(row) } } }
                    sql.append(tail).toString()
                }
            }
        }
        buildAhead()
        while (ahead.isNotEmpty()) {
            val sql = ahead.removeFirst().await()
            buildAhead()
            if (sql != null) {
                usePrepared(sql) { it.step() }
                forgetLastStatement()
            }
        }
    }
}

/**
 * Room keeps the statements a connection prepared, by their text, the last [ROOM_STATEMENT_CACHE] of them. A bulk
 * statement's text is its rows, so it never comes again; touching as many small statements of fixed text after it pushes
 * it out of that cache, which closes it, instead of keeping its megabyte of text and compiled rows.
 */
private suspend fun PooledConnection.forgetLastStatement() {
    for (sql in EVICTING) usePrepared(sql) { }
}

/** Room's default size of the cache of prepared statements of a connection (BulkInsertTest checks it). */
internal const val ROOM_STATEMENT_CACHE = 25

private val EVICTING = List(ROOM_STATEMENT_CACHE) { "SELECT $it" }

/**
 * Runs [block] with the indexes of [tables] dropped, then creates them again as they were: an index built once over all
 * rows is quicker than one kept up to date row by row, when the tables start empty (docs/library.md §1.3). Only inside a
 * transaction, so that a failure keeps them. An index SQLite made itself for a key stays.
 */
internal suspend fun PooledConnection.withIndexesBuiltAfter(tables: List<String>, block: suspend () -> Unit) {
    val indexes = usePrepared(
        "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL " +
            "AND tbl_name IN (SELECT value FROM json_each(?)) ORDER BY name",
    ) { statement ->
        statement.bindText(1, tables.joinToString(",", "[", "]") { "\"$it\"" })
        buildList { while (statement.step()) add(statement.getText(0) to statement.getText(1)) }
    }
    for ((name, _) in indexes) usePrepared("DROP INDEX `$name`") { it.step() }
    block()
    for ((_, sql) in indexes) usePrepared(sql) { it.step() }
}

private const val ROWS_PER_STATEMENT = 2_000

/** How many statements are built ahead of the one SQLite runs; each holds a megabyte or two. */
private const val CHUNKS_AHEAD = 3
