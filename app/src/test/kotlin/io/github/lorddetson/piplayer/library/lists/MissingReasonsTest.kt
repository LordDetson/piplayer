package io.github.lorddetson.piplayer.library.lists

import io.github.lorddetson.piplayer.library.db.TrackDisplayEntity
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.MergedValues
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The reasons of Songs missing information as the list looks them up (docs/library.md §17.1, §17.4). */
class MissingReasonsTest {

    @Test
    fun `the reasons are the bits the resolver stores`() {
        assertThat(MissingReasons.ORDER).containsExactly(
            MergedValues.TITLE_FROM_FILE_NAME, MergedValues.NO_ARTIST, MergedValues.NO_ALBUM, MergedValues.NO_COVER,
            MergedValues.NO_RECORDING_ID,
        )
        assertThat(MissingReasons.ALL).isEqualTo(31)
        // Title, artist and album are on by default; cover and MusicBrainz ID are not.
        assertThat(MissingReasons.DEFAULT).isEqualTo(MissingReasons.TITLE or MissingReasons.ARTIST or MissingReasons.ALBUM)
    }

    @Test
    fun `the stored sets of one reason are every set that holds it`() {
        assertThat(MissingReasons.storedSets(MissingReasons.TITLE)).containsExactly(1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 29, 31)
        assertThat(MissingReasons.storedSets(MissingReasons.RECORDING_ID)).containsExactlyElementsOf(16..31)
    }

    @Test
    fun `the stored sets of several reasons hold at least one of them, in ascending order`() {
        val sets = MissingReasons.storedSets(MissingReasons.DEFAULT)

        assertThat(sets).containsExactlyElementsOf((1..31).filter { it and 7 != 0 })
        assertThat(sets).hasSize(28).doesNotContain(0, 8, 16, 24)
        assertThat(MissingReasons.storedSets(MissingReasons.ALL)).containsExactlyElementsOf(1..31)
    }

    @Test
    fun `no chosen reason has no stored set, and neither has a file without reasons`() {
        assertThat(MissingReasons.storedSets(0)).isEmpty()
        assertThat(MissingReasons.storedSets(MissingReasons.ALL)).doesNotContain(0)
    }

    @Test
    fun `Keep as-is and Skip are the codes of track_display and the values of their decided_mirror row`() {
        // TI §10.1: none, keep_as_is or skipped as 0, 1 and 2, the codes the display row stores.
        assertThat(MissingInfoDecision.NONE).isEqualTo(TrackDisplayEntity.DECISION_NONE).isEqualTo(0)
        assertThat(MissingInfoDecision.KEEP_AS_IS).isEqualTo(TrackDisplayEntity.DECISION_KEEP_AS_IS).isEqualTo(1)
        assertThat(MissingInfoDecision.SKIPPED).isEqualTo(TrackDisplayEntity.DECISION_SKIPPED).isEqualTo(2)
        assertThat(MissingInfoDecision.FIELD).isEqualTo("missing_info_decision")
        // The field is no part of a decided field, so it never mixes with them.
        assertThat(DecidedField.ofName(MissingInfoDecision.FIELD)).isNull()

        assertThat(MissingInfoDecision.ofValue("keep_as_is")).isEqualTo(MissingInfoDecision.KEEP_AS_IS)
        assertThat(MissingInfoDecision.ofValue("skipped")).isEqualTo(MissingInfoDecision.SKIPPED)
        assertThat(MissingInfoDecision.ofValue(null)).isEqualTo(MissingInfoDecision.NONE)
        // A value of a later build counts as none.
        assertThat(MissingInfoDecision.ofValue("later")).isEqualTo(MissingInfoDecision.NONE)
        for (code in listOf(MissingInfoDecision.KEEP_AS_IS, MissingInfoDecision.SKIPPED)) {
            assertThat(MissingInfoDecision.ofValue(MissingInfoDecision.valueOf(code))).isEqualTo(code)
        }
        assertThat(MissingInfoDecision.valueOf(MissingInfoDecision.NONE)).isNull()
    }

}
