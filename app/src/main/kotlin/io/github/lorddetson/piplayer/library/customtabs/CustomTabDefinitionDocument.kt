package io.github.lorddetson.piplayer.library.customtabs

import io.github.lorddetson.piplayer.library.lists.ListKind
import kotlinx.serialization.Serializable

/** Only portable fields belong in this document. A catalog supplies the tab ID as its map key. */
@Serializable
internal data class CustomTabDefinitionDocument(val name: String, val source: CustomTabTemplateSourceDocument) {
    fun toDefinition(id: String): CustomTabDefinition? =
        source.toSource()?.let { CustomTabDefinition(id, name, it).takeIf(CustomTabDefinition::isValid) }

    companion object {
        fun from(definition: CustomTabDefinition) =
            CustomTabDefinitionDocument(definition.name, CustomTabTemplateSourceDocument.from(definition.source))
    }
}

@Serializable
internal data class CustomTabTemplateSourceDocument(
    val type: String,
    val sourceRef: String? = null,
    val hint: String? = null,
    val kind: String? = null,
    val match: String? = null,
    val rules: List<TabTagRuleDocument>? = null,
) {
    fun toSource(): CustomTabTemplateSource? {
        if (type == "playlist" || type == "folder") {
            if (sourceRef == null || hint == null || kind != null || match != null || rules != null) return null
            return if (type == "playlist") CustomTabTemplateSource.Playlist(sourceRef, hint)
            else CustomTabTemplateSource.Folder(sourceRef, hint)
        }
        if (sourceRef != null || hint != null) return null
        if (type == "collection" && match == null && rules == null) {
            return ListKind.entries.firstOrNull { it.token == kind && it.smart }?.let(CustomTabTemplateSource::Collection)
        }
        if (type == "rules" && kind == null && rules != null) {
            val mode = TagRuleMatch.entries.firstOrNull { it.token == match } ?: return null
            return TabTagRuleSet.create(mode, rules.map { it.toRule() ?: return null })?.let(CustomTabTemplateSource::Rules)
        }
        return null
    }

    companion object {
        fun from(source: CustomTabTemplateSource): CustomTabTemplateSourceDocument = when (source) {
            is CustomTabTemplateSource.Playlist -> CustomTabTemplateSourceDocument("playlist", source.sourceRef, source.hint)
            is CustomTabTemplateSource.Folder -> CustomTabTemplateSourceDocument("folder", source.sourceRef, source.hint)
            is CustomTabTemplateSource.Collection -> CustomTabTemplateSourceDocument("collection", kind = source.kind.token)
            is CustomTabTemplateSource.Rules -> CustomTabTemplateSourceDocument(
                "rules",
                match = source.rules.match.token,
                rules = source.rules.rules.map(TabTagRuleDocument::from),
            )
        }
    }
}

@Serializable
internal data class TabTagRuleDocument(
    val field: String,
    val comparison: String? = null,
    val text: String? = null,
    val from: Int? = null,
    val through: Int? = null,
) {
    fun toRule(): TabTagRule? {
        if (field == "year") return if (comparison == null && text == null) TabTagRule.Year(from, through) else null
        if (text == null || from != null || through != null) return null
        val textField = TabTextField.entries.firstOrNull { it.token == field } ?: return null
        val operation = TabTextComparison.entries.firstOrNull { it.token == comparison } ?: return null
        return TabTagRule.Text(textField, operation, text)
    }

    companion object {
        fun from(rule: TabTagRule): TabTagRuleDocument = when (rule) {
            is TabTagRule.Text -> TabTagRuleDocument(
                rule.field.token,
                rule.comparison.token,
                rule.text,
            )
            is TabTagRule.Year -> TabTagRuleDocument("year", from = rule.from, through = rule.through)
        }
    }
}
