package io.github.lorddetson.piplayer.library.db

import androidx.room3.PooledConnection

/** Rows of values as one JSON array of arrays, the form `json_each` unpacks inside SQLite (docs/library.md §1.3). */
internal class JsonRows {

    private val json = StringBuilder("[")
    private var rows = 0

    /** The values of one row, in column order. */
    inner class Row {
        private var values = 0

        fun text(value: String?) {
            separate()
            if (value == null) json.append("null") else quote(value)
        }

        fun long(value: Long?) {
            separate()
            if (value == null) json.append("null") else json.append(value)
        }

        /** True as 1 and false as 0, as SQLite stores them. */
        fun flag(value: Boolean?) {
            separate()
            json.append(
                when (value) {
                    null -> "null"
                    true -> "1"
                    false -> "0"
                },
            )
        }

        private fun separate() {
            if (values++ > 0) json.append(',')
        }
    }

    fun row(values: Row.() -> Unit) {
        if (rows++ > 0) json.append(',')
        json.append('[')
        Row().values()
        json.append(']')
    }

    override fun toString(): String = "$json]"

    /**
     * An unpaired surrogate (a UTF-16 tag cut off in the middle of a pair) becomes U+FFFD: SQLite would take the next
     * character as its other half and swallow the closing quote, and the whole sync would fail on that one value.
     */
    private fun quote(value: String) {
        json.append('"')
        for ((i, c) in value.withIndex()) {
            when {
                c == '"' -> json.append("\\\"")
                c == '\\' -> json.append("\\\\")
                c == '\n' -> json.append("\\n")
                c == '\r' -> json.append("\\r")
                c == '\t' -> json.append("\\t")
                c < ' ' -> json.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                c.isHighSurrogate() && value.getOrNull(i + 1)?.isLowSurrogate() != true -> json.append(REPLACEMENT)
                c.isLowSurrogate() && value.getOrNull(i - 1)?.isHighSurrogate() != true -> json.append(REPLACEMENT)
                else -> json.append(c)
            }
        }
        json.append('"')
    }

    private companion object {
        const val REPLACEMENT = '\uFFFD'
    }
}

/**
 * Writes [rows] into [table] with `INSERT OR REPLACE`, [ROWS_PER_STATEMENT] rows per statement, each statement taking
 * its rows as one JSON parameter: binding every value on its own made up most of a first sync of 20 000 files (docs/
 * library.md §1.7). [values] gives one row's [columns] in order. [computed] adds columns whose values are SQL
 * expressions; they may read the row's values as `value->>i` (i its index in [columns]) and the rows the table held
 * before the statement.
 */
internal suspend fun <T> PooledConnection.replaceAll(
    table: String,
    columns: List<String>,
    rows: List<T>,
    computed: Map<String, String> = emptyMap(),
    values: JsonRows.Row.(T) -> Unit,
) {
    // `->>` gives the SQL value of each JSON value: integer, text or null.
    val sql = "INSERT OR REPLACE INTO $table (${(columns + computed.keys).joinToString(",")}) " +
        "SELECT ${(columns.indices.map { "value->>$it" } + computed.values).joinToString(",")} FROM json_each(?)"
    for (chunk in rows.chunked(ROWS_PER_STATEMENT)) {
        val json = JsonRows().apply { chunk.forEach { row -> row { values(row) } } }.toString()
        usePrepared(sql) { statement ->
            statement.bindText(1, json)
            statement.step()
        }
    }
}

private const val ROWS_PER_STATEMENT = 2_000
