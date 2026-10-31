package io.github.lorddetson.piplayer.library.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsonRowsTest {

    @Test
    fun `rows become one array of arrays`() {
        val json = JsonRows().apply {
            row { text("a"); long(1); flag(true); text(null) }
            row { text("b"); long(-2); flag(false); long(null) }
        }.toString()

        assertThat(json).isEqualTo("""[["a",1,1,null],["b",-2,0,null]]""")
    }

    @Test
    fun `no rows make an empty array`() {
        assertThat(JsonRows().toString()).isEqualTo("[]")
    }

    @Test
    fun `text that JSON must escape survives a round trip`() {
        val tricky = listOf(
            "He said \"hi\"",
            "back\\slash \\\\",
            "line\nbreak\r\ttab",
            "control \u0001\u001F end",
            "O'Brien – Ёжик в тумане 🎵 ｱ 𠀀",
            "",
        )
        val json = JsonRows().apply { tricky.forEach { value -> row { text(value) } } }.toString()

        val parsed = Json.parseToJsonElement(json).jsonArray.map { it.jsonArray.single().jsonPrimitive.content }
        assertThat(parsed).containsExactlyElementsOf(tricky)
    }

    @Test
    fun `an unpaired surrogate becomes the replacement character`() {
        // A UTF-16 tag cut off after the first half of a pair: SQLite would read the next character as its other half.
        val json = JsonRows().apply {
            row { text("cut" + Char(0xD83D)) }
            row { text(Char(0xDE00) + "alone") }
            row { text("a" + Char(0xD83D) + "\"quoted" + Char(0xD83D)) }
            row { text("pair " + Char(0xD83D) + Char(0xDE00)) }
        }.toString()

        val parsed = Json.parseToJsonElement(json).jsonArray.map { it.jsonArray.single().jsonPrimitive.content }
        assertThat(parsed).containsExactly(
            "cut\uFFFD", "\uFFFDalone", "a\uFFFD\"quoted\uFFFD", "pair \uD83D\uDE00",
        )
    }

    @Test
    fun `a missing value is JSON null`() {
        val json = JsonRows().apply { row { text(null); long(null); flag(null) } }.toString()

        assertThat((Json.parseToJsonElement(json).jsonArray.single() as JsonArray).toList()).containsOnly(JsonNull)
    }
}
