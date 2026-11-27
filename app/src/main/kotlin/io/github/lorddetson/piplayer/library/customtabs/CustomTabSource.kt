package io.github.lorddetson.piplayer.library.customtabs

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.sync.DisplayValues
import java.text.Normalizer
import java.util.Locale

internal sealed interface CustomTabSource {
    data class Playlist(val id: Long) : CustomTabSource
    data class Folder(val key: FolderKey) : CustomTabSource
    data class Collection(val kind: ListKind) : CustomTabSource
    data class Rules(val rules: TabTagRuleSet) : CustomTabSource

    /** The library query for a structural source. Rule sources must evaluate the merged tag values. */
    fun existingSongQuery(): SongQuery? = when (this) {
        is Playlist -> id.takeIf { it > 0 }?.let(SongQuery::playlist)
        is Folder -> if (FolderKey.parse(key.key) == key) SongQuery.folder(key.volume, key.relativePath) else null
        is Collection -> if (kind.smart) SongQuery.smart(kind) else null
        is Rules -> null
    }
}

internal enum class TabTextField(val token: String) {
    TITLE("title"), ARTIST("artist"), ALBUM("album"), GENRE("genre"), COMPOSER("composer"),
}

internal enum class TabTextComparison(val token: String) { EXACT("exact"), CONTAINS("contains") }

internal enum class TagRuleMatch(val token: String) { ALL("all"), ANY("any") }

internal sealed interface TabTagRule {
    data class Text(val field: TabTextField, val comparison: TabTextComparison, val text: String) : TabTagRule
    data class Year(val from: Int?, val through: Int?) : TabTagRule
}

internal data class DisplayedTabTags(
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val genres: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val year: Int? = null,
) {
    internal fun values(field: TabTextField): List<String> = when (field) {
        TabTextField.TITLE -> listOfNotNull(title)
        TabTextField.ARTIST -> artists
        TabTextField.ALBUM -> listOfNotNull(album)
        TabTextField.GENRE -> genres
        TabTextField.COMPOSER -> composers
    }

    companion object {
        fun fromDisplayed(values: DisplayValues, composers: List<String>): DisplayedTabTags = DisplayedTabTags(
            title = values.title,
            artists = (listOfNotNull(values.artist) + values.artists).distinct(),
            album = values.album,
            genres = values.genres,
            composers = composers.toList(),
            year = values.year,
        )
    }
}

/** Validated rules for a custom tab. A malformed set cannot accidentally select the whole library. */
internal class TabTagRuleSet private constructor(
    val match: TagRuleMatch,
    val rules: List<TabTagRule>,
    private val predicates: List<(DisplayedTabTags) -> Boolean>,
) {
    fun matches(tags: DisplayedTabTags): Boolean = when (match) {
        TagRuleMatch.ALL -> predicates.all { it(tags) }
        TagRuleMatch.ANY -> predicates.any { it(tags) }
    }

    override fun equals(other: Any?): Boolean = other is TabTagRuleSet && match == other.match && rules == other.rules

    override fun hashCode(): Int = 31 * match.hashCode() + rules.hashCode()

    companion object {
        private const val MAX_RULES = 32
        private const val MAX_TEXT_LENGTH = 512
        private val years = 1..9999

        fun create(match: TagRuleMatch, rules: List<TabTagRule>): TabTagRuleSet? {
            if (rules.size !in 1..MAX_RULES) return null
            val definition = rules.toList()
            val predicates = definition.map { rule ->
                when (rule) {
                    is TabTagRule.Text -> textPredicate(rule)
                    is TabTagRule.Year -> yearPredicate(rule)
                } ?: return null
            }
            return TabTagRuleSet(match, definition, predicates)
        }

        private fun textPredicate(rule: TabTagRule.Text): ((DisplayedTabTags) -> Boolean)? {
            val text = rule.text.trim()
            if (text.length !in 1..MAX_TEXT_LENGTH) return null
            val needle = comparableText(text)
            if (needle.length > MAX_TEXT_LENGTH) return null

            return { tags ->
                tags.values(rule.field).any { value ->
                    val displayed = comparableText(value)
                    when (rule.comparison) {
                        TabTextComparison.EXACT -> displayed == needle
                        TabTextComparison.CONTAINS -> needle in displayed
                    }
                }
            }
        }

        private fun yearPredicate(rule: TabTagRule.Year): ((DisplayedTabTags) -> Boolean)? {
            if (rule.from == null && rule.through == null) return null
            val start = rule.from ?: years.first
            val end = rule.through ?: years.last
            if (start !in years || end !in years || start > end) return null
            val range = start..end
            return { tags -> tags.year?.let(range::contains) == true }
        }

        // Canonical Unicode and case insensitivity keep literal punctuation significant in both comparisons.
        private fun comparableText(text: String): String =
            Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFC)
    }
}
