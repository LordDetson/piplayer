package io.github.lorddetson.piplayer.library.customtabs

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.values.FieldOverrides
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.library.values.TrackTags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CustomTabRulesTest {
    @Test
    fun `equivalent validated rules have value identity`() {
        val first = rules(TagRuleMatch.ALL, exact(TabTextField.TITLE, "Luna"))
        val same = rules(TagRuleMatch.ALL, exact(TabTextField.TITLE, "Luna"))
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
    }


    @Test
    fun `all and any evaluate each rule against its own displayed field`() {
        val positive = exact(TabTextField.ARTIST, "Luna")
        val negative = contains(TabTextField.ALBUM, "Luna")
        val row = DisplayedTabTags(title = "Luna", artists = listOf("Luna"), album = "Something else")
        assertTrue(rules(TagRuleMatch.ALL, positive).matches(row))
        assertFalse(rules(TagRuleMatch.ALL, positive, negative).matches(row))
        assertTrue(rules(TagRuleMatch.ANY, negative, positive).matches(row))
        assertFalse(rules(TagRuleMatch.ANY, negative).matches(row))
    }

    @Test
    fun `text matches canonically equivalent spelling and treats punctuation literally`() {
        val row = DisplayedTabTags(title = "Café C++", album = "Studio 100%_ Mix")
        assertTrue(rules(TagRuleMatch.ALL, exact(TabTextField.TITLE, "  CAFE\u0301 C++  ")).matches(row))
        assertFalse(rules(TagRuleMatch.ALL, exact(TabTextField.TITLE, "Café C")).matches(row))
        assertFalse(rules(TagRuleMatch.ALL, exact(TabTextField.TITLE, "Café")).matches(row))
        assertTrue(rules(TagRuleMatch.ALL, contains(TabTextField.TITLE, "FÉ c+")).matches(row))
        assertTrue(rules(TagRuleMatch.ALL, contains(TabTextField.ALBUM, "%_")).matches(row))
        assertFalse(rules(TagRuleMatch.ALL, contains(TabTextField.TITLE, "%")).matches(row))
    }

    @Test
    fun `artist genre and composer rules can match any one displayed value`() {
        val row = DisplayedTabTags(
            artists = listOf("DJ Blue", "Mina"),
            genres = listOf("Hip-Hop/Rap", "Ambient"),
            composers = listOf("Ana Silva", "Ryo"),
        )
        assertTrue(rules(
            TagRuleMatch.ALL,
            exact(TabTextField.ARTIST, "mina"),
            contains(TabTextField.GENRE, "Hop/Rap"),
            exact(TabTextField.COMPOSER, "Ryo"),
        ).matches(row))
        assertFalse(rules(TagRuleMatch.ALL, contains(TabTextField.COMPOSER, "Silva Ryo")).matches(row))
        assertFalse(rules(TagRuleMatch.ALL, exact(TabTextField.GENRE, "Rap")).matches(row))
    }

    @Test
    fun `numeric year range is inclusive and optional ends do not admit unknown dates`() {
        val closed = rules(TagRuleMatch.ALL, TabTagRule.Year(1990, 2000))
        assertTrue(closed.matches(DisplayedTabTags(year = 1990)))
        assertTrue(closed.matches(DisplayedTabTags(year = 2000)))
        assertFalse(closed.matches(DisplayedTabTags(year = 1989)))
        assertFalse(closed.matches(DisplayedTabTags(year = 2001)))
        assertFalse(closed.matches(DisplayedTabTags()))
        assertTrue(rules(TagRuleMatch.ALL, TabTagRule.Year(null, 1999)).matches(DisplayedTabTags(year = 1961)))
        assertTrue(rules(TagRuleMatch.ALL, TabTagRule.Year(2012, null)).matches(DisplayedTabTags(year = 2020)))
    }

    @Test
    fun `empty metadata does not match text predicates`() {
        val ruleSet = rules(TagRuleMatch.ALL, contains(TabTextField.COMPOSER, "writer"))
        assertTrue(ruleSet.matches(DisplayedTabTags(composers = listOf("", "A Writer"))))
        assertFalse(ruleSet.matches(DisplayedTabTags(composers = listOf("  ", ""))))
        assertFalse(ruleSet.matches(DisplayedTabTags()))
    }

    @Test
    fun `empty reversed and unbounded rules cannot create a predicate`() {
        assertNull(TabTagRuleSet.create(TagRuleMatch.ALL, emptyList()))
        val invalid = listOf(
            contains(TabTextField.TITLE, "  "),
            contains(TabTextField.TITLE, "x".repeat(4096)),
            TabTagRule.Year(null, null),
            TabTagRule.Year(2025, 1988),
            TabTagRule.Year(0, 1972),
            TabTagRule.Year(1972, 10_000),
        )
        for (rule in invalid) {
            assertNull(TabTagRuleSet.create(TagRuleMatch.ANY, listOf(exact(TabTextField.TITLE, "Valid"), rule)))
        }
        assertNull(TabTagRuleSet.create(TagRuleMatch.ALL, List(4096) { exact(TabTextField.TITLE, "Valid") }))
    }

    @Test
    fun `the predicate follows updated merged values and never falls back to stale index or file values`() {
        val displayed = MergedValues.of(
            displayName = "Initial filename.mp3",
            index = IndexValues(title = "Initial index", artist = "Index artist", genre = "Old genre"),
            tags = TrackTags(
                title = "Initial file tag",
                artist = "Tag artist",
                album = "Tag album",
                genres = listOf("New genre"),
                date = "1996",
            ),
            read = true,
            overrides = FieldOverrides(title = "Confirmed title", artist = "Edited artist", album = "Edited album"),
        )
        val row = DisplayedTabTags.fromDisplayed(displayed, composers = listOf("Edited composer"))
        val current = rules(
            TagRuleMatch.ALL,
            exact(TabTextField.TITLE, "Confirmed title"),
            exact(TabTextField.ARTIST, "Edited artist"),
            exact(TabTextField.ALBUM, "Edited album"),
            exact(TabTextField.GENRE, "New genre"),
            exact(TabTextField.COMPOSER, "Edited composer"),
            TabTagRule.Year(1994, 1999),
        )
        assertTrue(current.matches(row))
        assertFalse(rules(TagRuleMatch.ANY,
            contains(TabTextField.TITLE, "Initial"),
            exact(TabTextField.ARTIST, "Tag artist"),
            exact(TabTextField.GENRE, "Old genre"),
        ).matches(row))
        assertFalse(current.matches(row.copy(composers = emptyList())))
    }

    @Test
    fun `playlist folder and collection sources reuse their stable library query identities`() {
        assertEquals(SongQuery.playlist(123), CustomTabSource.Playlist(123).existingSongQuery())
        val folder = CustomTabSource.Folder(FolderKey("0123-4567", "Music/Live/"))
        assertEquals(SongQuery.folder("0123-4567", "Music/Live/"), folder.existingSongQuery())
        assertFalse(folder.existingSongQuery() == CustomTabSource.Folder(FolderKey("primary", "Music/Live/")).existingSongQuery())
        assertEquals(SongQuery.favorites(), CustomTabSource.Collection(ListKind.FAVORITES).existingSongQuery())
        assertNull(CustomTabSource.Collection(ListKind.PLAYLIST).existingSongQuery())
        assertNull(CustomTabSource.Rules(rules(TagRuleMatch.ALL, exact(TabTextField.TITLE, "Title"))).existingSongQuery())
    }

    private fun exact(field: TabTextField, text: String) = TabTagRule.Text(field, TabTextComparison.EXACT, text)

    private fun contains(field: TabTextField, text: String) = TabTagRule.Text(field, TabTextComparison.CONTAINS, text)

    private fun rules(match: TagRuleMatch, vararg rules: TabTagRule): TabTagRuleSet =
        requireNotNull(TabTagRuleSet.create(match, rules.toList()))
}
