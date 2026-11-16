package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.library.lists.MissingInfoDecision
import io.github.lorddetson.piplayer.library.values.DecidedField.ALBUM
import io.github.lorddetson.piplayer.library.values.DecidedField.ARTIST
import io.github.lorddetson.piplayer.library.values.DecidedField.ARTIST_CREDITS
import io.github.lorddetson.piplayer.library.values.DecidedField.ARTIST_SORT
import io.github.lorddetson.piplayer.library.values.DecidedField.TITLE
import io.github.lorddetson.piplayer.library.values.DecisionKind.APPLY
import io.github.lorddetson.piplayer.library.values.DecisionKind.EDIT
import io.github.lorddetson.piplayer.library.values.DecisionKind.KEEP_AS_IS
import io.github.lorddetson.piplayer.library.values.DecisionKind.Companion.LEVEL_IDENTIFICATION
import io.github.lorddetson.piplayer.library.values.DecisionKind.Companion.LEVEL_USER_EDIT
import io.github.lorddetson.piplayer.library.values.DecisionKind.REVERT_FIELD
import io.github.lorddetson.piplayer.library.values.DecisionKind.SKIP
import io.github.lorddetson.piplayer.settings.ProtectedField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Levels 1 and 2 of TI §7.1: the newest decision that is not undone wins per field, a revert hides older values, and a
 * field is decided as a whole (TI §11.1 M3 "Decided values per field" and "Database-only undo").
 */
class DecisionResolverTest {

    /** An identification at [at] of "<name> title", "<name> artist" and "<name> album". */
    private fun identification(id: Long, at: Long, name: String, undone: Boolean = false) = DecisionValues(
        id, APPLY, at,
        fields = setOf(ProtectedField.TITLE, ProtectedField.ARTIST, ProtectedField.ALBUM),
        values = mapOf(
            TITLE to "$name title", ARTIST to "$name artist",
            ARTIST_CREDITS to DecidedField.credits(listOf("$name artist" to "")), ARTIST_SORT to "$name sort",
            ALBUM to "$name album",
        ),
        undone = undone,
    )

    private fun edit(id: Long, at: Long, values: Map<DecidedField, String?>, undone: Boolean = false) =
        DecisionValues(id, EDIT, at, values.keys.mapTo(HashSet()) { it.group }, values, undone)

    private fun revert(id: Long, at: Long, vararg fields: ProtectedField, undone: Boolean = false) =
        DecisionValues(id, REVERT_FIELD, at, fields.toSet(), undone = undone)

    private fun List<DecisionValues>.winners() = DecisionResolver.resolve(this)

    @Test
    fun `no decision decides nothing`() {
        assertThat(emptyList<DecisionValues>().winners()).isEmpty()
    }

    @Test
    fun `an identification decides every part of its ticked fields at level 2`() {
        val winners = listOf(identification(1, 10, "A")).winners()

        assertThat(winners[TITLE]).isEqualTo(Decided("A title", LEVEL_IDENTIFICATION, 1))
        assertThat(winners[ARTIST]).isEqualTo(Decided("A artist", LEVEL_IDENTIFICATION, 1))
        assertThat(winners[ARTIST_SORT]).isEqualTo(Decided("A sort", LEVEL_IDENTIFICATION, 1))
        assertThat(winners[ALBUM]).isEqualTo(Decided("A album", LEVEL_IDENTIFICATION, 1))
        // Unticked fields stay the file's.
        assertThat(winners).doesNotContainKey(DecidedField.DATE)
        assertThat(winners.keys).allSatisfy { assertThat(it.group).isIn(ProtectedField.TITLE, ProtectedField.ARTIST, ProtectedField.ALBUM) }
    }

    @Test
    fun `a part a ticked field lacks is decided empty, so the parts of two decisions never mix`() {
        val older = identification(1, 10, "A")
        // The newer identification has no sort name for the artist.
        val newer = identification(2, 20, "B").let { it.copy(values = it.values - ARTIST_SORT) }

        val winners = listOf(older, newer).winners()

        assertThat(winners[ARTIST]).isEqualTo(Decided("B artist", LEVEL_IDENTIFICATION, 2))
        assertThat(winners[ARTIST_SORT]).isEqualTo(Decided(null, LEVEL_IDENTIFICATION, 2))
    }

    @Test
    fun `apply then undo leaves nothing decided`() {
        assertThat(listOf(identification(1, 10, "A", undone = true)).winners()).isEmpty()
    }

    @Test
    fun `undoing the older of two identifications changes nothing, undoing the newer brings the older back`() {
        val both = listOf(identification(1, 10, "A"), identification(2, 20, "B"))
        val olderUndone = listOf(identification(1, 10, "A", undone = true), identification(2, 20, "B"))
        val newerUndone = listOf(identification(1, 10, "A"), identification(2, 20, "B", undone = true))

        assertThat(olderUndone.winners()).isEqualTo(both.winners())
        assertThat(newerUndone.winners()[TITLE]).isEqualTo(Decided("A title", LEVEL_IDENTIFICATION, 1))
        assertThat(newerUndone.winners()[ALBUM]).isEqualTo(Decided("A album", LEVEL_IDENTIFICATION, 1))
    }

    @Test
    fun `an edit on top of an identification wins its field only, and its undo brings the identification back`() {
        val identified = identification(1, 10, "A")
        val edited = edit(2, 20, mapOf(TITLE to "My title"))

        val winners = listOf(identified, edited).winners()
        assertThat(winners[TITLE]).isEqualTo(Decided("My title", LEVEL_USER_EDIT, 2))
        assertThat(winners[ARTIST]).isEqualTo(Decided("A artist", LEVEL_IDENTIFICATION, 1))

        val undone = listOf(identified, edited.copy(undone = true)).winners()
        assertThat(undone[TITLE]).isEqualTo(Decided("A title", LEVEL_IDENTIFICATION, 1))
    }

    @Test
    fun `the newest decision wins whatever its level, so an identification after an edit wins over it`() {
        val winners = listOf(edit(1, 10, mapOf(TITLE to "My title")), identification(2, 20, "B")).winners()

        assertThat(winners[TITLE]).isEqualTo(Decided("B title", LEVEL_IDENTIFICATION, 2))
    }

    @Test
    fun `decisions made at the same time are ordered by their row ID`() {
        val winners = listOf(identification(5, 10, "Later row"), identification(4, 10, "Earlier row")).winners()

        assertThat(winners[TITLE]?.decisionId).isEqualTo(5)
    }

    @Test
    fun `decisions are ordered by row ID, so a later row made at an earlier time wins`() {
        // The clock was set back between the two decisions, or the older one came from a device whose clock ran ahead.
        val older = identification(1, 20, "Older row")
        val later = identification(2, 10, "Later row")

        assertThat(listOf(older, later).winners()[TITLE]).isEqualTo(Decided("Later row title", LEVEL_IDENTIFICATION, 2))
        assertThat(listOf(older, edit(3, 5, mapOf(TITLE to "Mine"))).winners()[TITLE]?.decisionId).isEqualTo(3)
        // Undoing the later row brings the older one back.
        assertThat(listOf(older, later.copy(undone = true)).winners()[TITLE]?.decisionId).isEqualTo(1)
    }

    @Test
    fun `an edit of the artist sets every part of the field, empty where it gives none`() {
        val winners = listOf(identification(1, 10, "A"), edit(2, 20, mapOf(ARTIST to "Me"))).winners()

        assertThat(winners[ARTIST]).isEqualTo(Decided("Me", LEVEL_USER_EDIT, 2))
        assertThat(winners[ARTIST_CREDITS]).isEqualTo(Decided(null, LEVEL_USER_EDIT, 2))
        assertThat(winners[ARTIST_SORT]).isEqualTo(Decided(null, LEVEL_USER_EDIT, 2))
    }

    @Test
    fun `an edit that clears a field wins with an empty value`() {
        val winners = listOf(identification(1, 10, "A"), edit(2, 20, mapOf(ALBUM to null))).winners()

        assertThat(winners).containsEntry(ALBUM, Decided(null, LEVEL_USER_EDIT, 2))
    }

    @Test
    fun `a revert hides the older values of its field only, so the file's value wins there`() {
        val winners = listOf(identification(1, 10, "A"), edit(2, 20, mapOf(TITLE to "My title")), revert(3, 30, ProtectedField.TITLE))
            .winners()

        assertThat(winners).doesNotContainKey(TITLE)
        assertThat(winners[ALBUM]).isEqualTo(Decided("A album", LEVEL_IDENTIFICATION, 1))
    }

    @Test
    fun `a decision newer than a revert wins over it`() {
        val winners = listOf(identification(1, 10, "A"), revert(2, 20, ProtectedField.TITLE), edit(3, 30, mapOf(TITLE to "Again")))
            .winners()

        assertThat(winners[TITLE]).isEqualTo(Decided("Again", LEVEL_USER_EDIT, 3))
    }

    @Test
    fun `undoing a revert brings the hidden values back`() {
        val winners = listOf(identification(1, 10, "A"), revert(2, 20, ProtectedField.ARTIST, undone = true)).winners()

        assertThat(winners[ARTIST]).isEqualTo(Decided("A artist", LEVEL_IDENTIFICATION, 1))
    }

    @Test
    fun `Keep as-is and Skip decide no value`() {
        val winners = listOf(
            identification(1, 10, "A"),
            DecisionValues(2, KEEP_AS_IS, 20, emptySet()),
            DecisionValues(3, SKIP, 30, emptySet()),
        ).winners()

        assertThat(winners[TITLE]).isEqualTo(Decided("A title", LEVEL_IDENTIFICATION, 1))
    }

    private fun keep(id: Long, at: Long = id * 10, undone: Boolean = false) = DecisionValues(id, KEEP_AS_IS, at, emptySet(), undone = undone)

    private fun skip(id: Long, at: Long = id * 10, undone: Boolean = false, lapsed: Boolean = false) =
        DecisionValues(id, SKIP, at, emptySet(), undone = undone, lapsed = lapsed)

    private fun List<DecisionValues>.missingInfo() = DecisionResolver.missingInfoOf(this)

    private fun kept(id: Long) = Decided(MissingInfoDecision.KEEP_AS_IS_VALUE, DecisionResolver.MISSING_INFO_LEVEL, id)

    private fun skipped(id: Long) = Decided(MissingInfoDecision.SKIPPED_VALUE, DecisionResolver.MISSING_INFO_LEVEL, id)

    @Test
    fun `without Keep as-is or Skip the list state is none`() {
        assertThat(emptyList<DecisionValues>().missingInfo()).isNull()
        assertThat(listOf(identification(1, 10, "A"), edit(2, 20, mapOf(TITLE to "T")), revert(3, 30, ProtectedField.TITLE)).missingInfo())
            .isNull()
    }

    @Test
    fun `the newest Keep as-is or Skip by row ID counts, whatever value decisions lie between`() {
        assertThat(listOf(keep(1)).missingInfo()).isEqualTo(kept(1))
        assertThat(listOf(skip(1)).missingInfo()).isEqualTo(skipped(1))
        assertThat(listOf(keep(1), identification(2, 20, "A"), skip(3), edit(4, 40, mapOf(TITLE to "T"))).missingInfo()).isEqualTo(skipped(3))
        assertThat(listOf(skip(1), revert(2, 20, ProtectedField.TITLE), keep(3), identification(4, 40, "B")).missingInfo()).isEqualTo(kept(3))
        // By row ID, not by the time: a Skip made after the clock was set back still replaces the Keep as-is.
        assertThat(listOf(keep(3, at = 100), skip(7, at = 1)).missingInfo()).isEqualTo(skipped(7))
        assertThat(listOf(skip(7, at = 1), keep(3, at = 100)).missingInfo()).isEqualTo(skipped(7))
    }

    @Test
    fun `undoing the newer Keep as-is or Skip brings the older back, and undoing both leaves none`() {
        assertThat(listOf(keep(1), skip(2, undone = true)).missingInfo()).isEqualTo(kept(1))
        assertThat(listOf(skip(1), keep(2, undone = true)).missingInfo()).isEqualTo(skipped(1))
        assertThat(listOf(keep(1, undone = true), skip(2, undone = true)).missingInfo()).isNull()
        // Undoing the older changes nothing.
        assertThat(listOf(keep(1, undone = true), skip(2)).missingInfo()).isEqualTo(skipped(2))
    }

    @Test
    fun `a lapsed Skip counts for nothing, so an older Keep as-is counts again`() {
        assertThat(listOf(skip(1, lapsed = true)).missingInfo()).isNull()
        assertThat(listOf(keep(1), skip(2, lapsed = true)).missingInfo()).isEqualTo(kept(1))
        assertThat(listOf(skip(1, lapsed = true), skip(2)).missingInfo()).isEqualTo(skipped(2))
    }

    /** The per-field table: decisions in order, which ones are undone, and the expected winner of the title. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("titleTable")
    fun `the title's winner per table row`(row: String, decisions: List<DecisionValues>, expected: Decided?) {
        assertThat(decisions.winners()[TITLE]).describedAs(row).isEqualTo(expected)
    }

    @Test
    fun `the badge follows the levels that win`() {
        assertThat(DecisionResolver.badgeOf(emptyMap())).isEqualTo(DecisionResolver.BADGE_NONE)
        assertThat(DecisionResolver.badgeOf(listOf(identification(1, 10, "A")).winners())).isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
        assertThat(DecisionResolver.badgeOf(listOf(identification(1, 10, "A"), edit(2, 20, mapOf(TITLE to "T"))).winners()))
            .isEqualTo(DecisionResolver.BADGE_EDITED)
        // An edit hidden by a newer identification does not make the track edited.
        assertThat(DecisionResolver.badgeOf(listOf(edit(1, 10, mapOf(TITLE to "T")), identification(2, 20, "B")).winners()))
            .isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
    }

    @Test
    fun `every field of the protected-fields list has parts with distinct codes and names`() {
        assertThat(ProtectedField.entries).allSatisfy { assertThat(DecidedField.partsOf(it)).isNotEmpty() }
        assertThat(DecidedField.entries.map { it.code }).doesNotHaveDuplicates()
        assertThat(DecidedField.entries.map { it.stored }).doesNotHaveDuplicates()
        // The codes of TI §10.2.
        assertThat(DecidedField.entries.map { it.code }).isEqualTo((1..27).toList())
        assertThat(DecidedField.entries).allSatisfy { assertThat(DecidedField.ofCode(it.code)).isEqualTo(it) }
        assertThat(DecidedField.entries).allSatisfy { assertThat(DecidedField.ofName(it.stored)).isEqualTo(it) }
    }

    companion object {
        private fun ident(id: Long, at: Long, title: String, undone: Boolean = false) =
            DecisionValues(id, APPLY, at, setOf(ProtectedField.TITLE), mapOf(TITLE to title), undone)

        private fun edited(id: Long, at: Long, title: String?, undone: Boolean = false) =
            DecisionValues(id, EDIT, at, setOf(ProtectedField.TITLE), mapOf(TITLE to title), undone)

        private fun reverted(id: Long, at: Long, undone: Boolean = false) =
            DecisionValues(id, REVERT_FIELD, at, setOf(ProtectedField.TITLE), undone = undone)

        @JvmStatic
        fun titleTable(): List<Arguments> = listOf(
            Arguments.of("identification", listOf(ident(1, 1, "I")), Decided("I", LEVEL_IDENTIFICATION, 1)),
            Arguments.of("edit", listOf(edited(1, 1, "E")), Decided("E", LEVEL_USER_EDIT, 1)),
            Arguments.of("identification, edit", listOf(ident(1, 1, "I"), edited(2, 2, "E")), Decided("E", LEVEL_USER_EDIT, 2)),
            Arguments.of("edit, identification", listOf(edited(1, 1, "E"), ident(2, 2, "I")), Decided("I", LEVEL_IDENTIFICATION, 2)),
            Arguments.of("identification, edit undone", listOf(ident(1, 1, "I"), edited(2, 2, "E", undone = true)), Decided("I", LEVEL_IDENTIFICATION, 1)),
            Arguments.of("edit undone, identification undone", listOf(edited(1, 1, "E", true), ident(2, 2, "I", true)), null),
            Arguments.of("identification, revert", listOf(ident(1, 1, "I"), reverted(2, 2)), null),
            Arguments.of("identification, revert undone", listOf(ident(1, 1, "I"), reverted(2, 2, undone = true)), Decided("I", LEVEL_IDENTIFICATION, 1)),
            Arguments.of("revert, identification", listOf(reverted(1, 1), ident(2, 2, "I")), Decided("I", LEVEL_IDENTIFICATION, 2)),
            Arguments.of("identification, empty edit", listOf(ident(1, 1, "I"), edited(2, 2, null)), Decided(null, LEVEL_USER_EDIT, 2)),
            Arguments.of("two identifications, newer undone", listOf(ident(1, 1, "A"), ident(2, 2, "B", true)), Decided("A", LEVEL_IDENTIFICATION, 1)),
            Arguments.of("in any input order", listOf(ident(2, 2, "B"), ident(1, 1, "A")), Decided("B", LEVEL_IDENTIFICATION, 2)),
        )
    }
}
