package io.github.lorddetson.piplayer.library.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The rows of the bulk writes as SQL literals (docs/library.md §1.7); BulkInsertTest runs them through SQLite. */
class SqlRowsTest {

    private fun rows(vararg rows: SqlRows.Row.() -> Unit) = SqlRows().apply { rows.forEach { row(it) } }.toString()

    @Test
    fun `rows become one VALUES list`() {
        val sql = rows(
            { text("a"); long(1); flag(true); text(null) },
            { text("b"); long(-2); flag(false); long(null) },
        )

        assertThat(sql).isEqualTo("('a',1,1,NULL),('b',-2,0,NULL)")
    }

    @Test
    fun `rows follow what the builder already holds`() {
        val sql = StringBuilder("INSERT INTO t VALUES ")
        SqlRows(sql).apply { row { long(1) } }

        assertThat(sql.toString()).isEqualTo("INSERT INTO t VALUES (1)")
    }

    @Test
    fun `no rows make nothing`() {
        assertThat(SqlRows().toString()).isEmpty()
    }

    @Test
    fun `a missing value is NULL`() {
        assertThat(rows({ text(null); long(null); flag(null); blob(null) })).isEqualTo("(NULL,NULL,NULL,NULL)")
    }

    @Test
    fun `a blob is a hex literal, an empty one too`() {
        assertThat(rows({ blob(byteArrayOf(0, 1, 0x7f, -128, -1)); blob(ByteArray(0)) })).isEqualTo("(X'00017f80ff',X'')")
    }

    @Test
    fun `a quote is doubled and nothing else is escaped`() {
        assertThat(rows({ text("O'Brien's \"x\" \\ \n\t\u0001 Ёжик 🎵") }))
            .isEqualTo("('O''Brien''s \"x\" \\ \n\t\u0001 Ёжик 🎵')")
        assertThat(rows({ text("'") }, { text("") })).isEqualTo("(''''),('')")
    }

    @Test
    fun `a text with a NUL goes as the bytes of its UTF-8`() {
        // SQL text ends at a NUL character.
        assertThat(rows({ text("a\u0000'ё") })).isEqualTo("(CAST(X'610027d191' AS TEXT))")
    }

    @Test
    fun `an unpaired surrogate becomes the replacement character`() {
        // A UTF-16 tag cut off after the first half of a pair: SQLite cannot store it.
        val sql = rows(
            { text("cut" + Char(0xD83D)) },
            { text(Char(0xDE00) + "alone") },
            { text("a" + Char(0xD83D) + "'q" + Char(0xD83D)) },
            { text("pair " + Char(0xD83D) + Char(0xDE00)) },
            { text("nul\u0000" + Char(0xD83D)) },
        )

        assertThat(sql).isEqualTo(
            "('cut�'),('�alone'),('a�''q�'),('pair 😀'),(CAST(X'6e756c00efbfbd' AS TEXT))",
        )
    }

    @Test
    fun `text is written as quoting every character one by one writes it`() {
        // Most values need no care and go in whole; the result must not depend on that.
        val random = kotlin.random.Random(13)
        val alphabet = "aZ09 ёЖ-_/'\"\\\n\r\t\u0001\u001F\u007F 🎵" + Char(0xD83D) + Char(0xDE00) + Char(0xDC00)
        repeat(5_000) {
            val value = String(CharArray(random.nextInt(0, 12)) { alphabet[random.nextInt(alphabet.length)] })

            assertThat(rows({ text(value) })).describedAs(value.map { it.code.toString(16) }.toString()).isEqualTo("(${reference(value)})")
        }
    }

    /** Quoting character by character, with what the bulk writes did to unpaired surrogates since they were JSON. */
    private fun reference(value: String): String = buildString {
        append('\'')
        for ((i, c) in value.withIndex()) {
            when {
                c == '\'' -> append("''")
                c.isHighSurrogate() && value.getOrNull(i + 1)?.isLowSurrogate() != true -> append('�')
                c.isLowSurrogate() && value.getOrNull(i - 1)?.isHighSurrogate() != true -> append('�')
                else -> append(c)
            }
        }
        append('\'')
    }
}
