package io.github.lorddetson.piplayer.library.customtabs

import io.github.lorddetson.piplayer.library.lists.ListKind
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CustomTabDocumentTest {
    @Test
    fun `rule definitions round trip with six stable field tokens`() {
        val fields = listOf(TabTextField.TITLE, TabTextField.ARTIST, TabTextField.ALBUM, TabTextField.GENRE, TabTextField.COMPOSER)
        val rules = fields.map { TabTagRule.Text(it, TabTextComparison.CONTAINS, "Diana") } + TabTagRule.Year(1990, 2001)
        val set = requireNotNull(TabTagRuleSet.create(TagRuleMatch.ALL, rules))
        val definition = CustomTabDefinition(id, "Diana", CustomTabTemplateSource.Rules(set))
        val raw = Json.encodeToString(CustomTabDefinitionDocument.from(definition))
        for (field in listOf("title", "artist", "album", "genre", "composer", "year")) {
            assertTrue(raw.contains("\"field\":\"$field\""))
        }
        assertEquals(definition, Json.decodeFromString<CustomTabDefinitionDocument>(raw).toDefinition(id))
    }

    @Test
    fun `documents reject unknown references invalid rules and non-smart collections`() {
        val collection = CustomTabDefinition(id, "Playlist", CustomTabTemplateSource.Collection(ListKind.PLAYLIST))
        assertFalse(collection.isValid)
        assertNull(CustomTabDefinitionDocument.from(collection).toDefinition(id))
        val invalid = listOf(
            """{"name":"Mix","source":{"type":"playlist","sourceRef":"1-1-1-1-1","hint":"Mix"}}""",
            """{"name":"Mix","source":{"type":"folder","sourceRef":"04c31211-d885-4500-a449-a218e1680a9d","hint":" "}}""",
            """{"name":"Mix","source":{"type":"rules","match":"all","rules":[]}}""",
            """{"name":"Mix","source":{"type":"rules","match":"all","rules":[{"field":"year","from":2020,"through":1990}]}}""",
            """{"name":"Mix","source":{"type":"rules","match":"any","rules":[{"field":"artist","comparison":"matches","text":"A"}]}}""",
        )
        for (raw in invalid) assertNull(Json.decodeFromString<CustomTabDefinitionDocument>(raw).toDefinition(id))
        assertFalse(isCustomTabId(id.uppercase()))
        assertNull(customTabIdForComponent("tab:songs"))
    }

    companion object {
        private const val id = "custom-04c31211-d885-4500-a449-a218e1680a9d"
    }
}
