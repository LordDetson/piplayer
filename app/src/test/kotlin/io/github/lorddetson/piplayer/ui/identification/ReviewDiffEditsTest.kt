package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.library.details.PictureSize
import io.github.lorddetson.piplayer.settings.ProtectedField
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The diff with the cover picker's image and the user's values of Edit fields (TI §6, review screen B). */
class ReviewDiffEditsTest {

    private val current = ReviewDiff.current(ReviewFixtures.currentDetails())
    private val proposed = ReviewDiff.proposed(ReviewFixtures.harborDetails)
    private val lines = ReviewDiff.lines(current, proposed, emptySet())

    private fun chosen(size: PictureSize?, origin: ProposedCover.Origin = ProposedCover.Origin.RELEASE_INDEX) =
        ProposedCover.Chosen(ByteArray(8), size, origin)

    @Test
    fun `a chosen image starts by the size rule against the cover shown now`() = runTest {
        val shown600 = ShownCover.Known(600, 600, 48_000)
        assertThat(ReviewDiff.chosenLine(shown600, chosen(PictureSize(500, 500)))!!.ticked).isFalse()
        assertThat(ReviewDiff.chosenLine(shown600, chosen(PictureSize(600, 800)))!!.ticked).isTrue()
        assertThat(ReviewDiff.chosenLine(shown600, chosen(PictureSize(1200, 1200)))!!.ticked).isTrue()
        // A size the header does not say (WebP) cannot be shown not to be smaller than a known one.
        assertThat(ReviewDiff.chosenLine(shown600, chosen(null))!!.ticked).isFalse()
        assertThat(ReviewDiff.chosenLine(ShownCover.Thumbnail, chosen(null))!!.ticked).isTrue()
        assertThat(ReviewDiff.chosenLine(ShownCover.Thumbnail, chosen(PictureSize(100, 100)))!!.ticked).isTrue()
        assertThat(ReviewDiff.chosenLine(ShownCover.None, chosen(null, ProposedCover.Origin.EMBEDDED))!!.ticked).isTrue()
    }

    @Test
    fun `a chosen image under an own cover starts unticked and keeps its note, and none with the cover protected`() = runTest {
        val line = ReviewDiff.chosenLine(ShownCover.Own(song = false), chosen(PictureSize(1200, 1200)))!!
        assertThat(line.ticked).isFalse()
        assertThat(line.ownStaysOnTop).isTrue()
        assertThat(line.chosen).isTrue()
        assertThat(ReviewDiff.chosenLine(ShownCover.None, chosen(null), setOf(ProtectedField.COVER))).isNull()
    }

    @Test
    fun `the proposed front image is no chosen one`() = runTest {
        assertThat(ReviewDiff.coverLine(ShownCover.None, ReviewFixtures.cover())!!.chosen).isFalse()
    }

    @Test
    fun `an edited field holds the user's value, ticked and marked, and an edit equal to the release's is none`() {
        val edited = ReviewDiff.withEdits(
            lines,
            current,
            proposed,
            mapOf(
                ProtectedField.TITLE to FieldValue.Text("Paper Lanterns (edit)"),
                ProtectedField.ALBUM to proposed[ProtectedField.ALBUM],
            ),
        )

        val title = edited.single { it.field == ProtectedField.TITLE }
        assertThat(title).isEqualTo(DiffLine(ProtectedField.TITLE, FieldValue.Text("track_07"), FieldValue.Text("Paper Lanterns (edit)"), ticked = true, edited = true))
        assertThat(edited.single { it.field == ProtectedField.ALBUM }).isEqualTo(lines.single { it.field == ProtectedField.ALBUM })
        assertThat(edited.map { it.field }).isEqualTo(lines.map { it.field })
    }

    @Test
    fun `an edit of a field without a line adds one in the list's order, and an emptied field clears it`() {
        val edited = ReviewDiff.withEdits(
            lines,
            current,
            proposed,
            mapOf(ProtectedField.DISC_SUBTITLE to FieldValue.Text("Side B"), ProtectedField.ALBUM to null),
        )

        assertThat(lines.map { it.field }).doesNotContain(ProtectedField.DISC_SUBTITLE)
        val fields = edited.map { it.field }
        assertThat(fields).containsSubsequence(ProtectedField.DISC_NUMBER, ProtectedField.DISC_SUBTITLE, ProtectedField.LABEL)
        assertThat(edited.single { it.field == ProtectedField.DISC_SUBTITLE })
            .isEqualTo(DiffLine(ProtectedField.DISC_SUBTITLE, null, FieldValue.Text("Side B"), ticked = true, edited = true))
        assertThat(edited.single { it.field == ProtectedField.ALBUM })
            .isEqualTo(DiffLine(ProtectedField.ALBUM, FieldValue.Text("Harbor Lights"), null, ticked = true, edited = true))
    }

    @Test
    fun `an emptied field that has no value either way has no line`() {
        val edited = ReviewDiff.withEdits(lines, current, proposed, mapOf(ProtectedField.DISC_SUBTITLE to null))
        assertThat(edited).isEqualTo(lines)
    }

    @Test
    fun `Apply splits the release's ticked values from the user's and the chosen cover`() = runTest {
        val edited = ReviewDiff.withEdits(lines, current, proposed, mapOf(ProtectedField.TITLE to FieldValue.Text("Mine"), ProtectedField.LABEL to FieldValue.Text("Own label")))
            .map { if (it.field == ProtectedField.LABEL) it.copy(ticked = false) else it }
        val front = ReviewDiff.coverLine(ShownCover.None, ReviewFixtures.cover())!!

        val withFront = ReviewDiff.split(edited, front)
        assertThat(withFront.identification).contains(ProtectedField.ALBUM, ProtectedField.COVER).doesNotContain(ProtectedField.TITLE, ProtectedField.LABEL)
        assertThat(withFront.edits).containsExactlyEntriesOf(mapOf(ProtectedField.TITLE to FieldValue.Text("Mine")))
        assertThat(withFront.chosen).isNull()
        assertThat(withFront.hasEdit).isTrue()

        val picked = chosen(PictureSize(1200, 1200))
        val withChosen = ReviewDiff.split(edited, ReviewDiff.chosenLine(ShownCover.None, picked)!!)
        assertThat(withChosen.identification).doesNotContain(ProtectedField.COVER)
        assertThat(withChosen.chosen).isSameAs(picked)

        val unticked = ReviewDiff.split(lines, ReviewDiff.chosenLine(ShownCover.None, picked)!!.copy(ticked = false))
        assertThat(unticked.chosen).isNull()
        assertThat(unticked.hasEdit).isFalse()
        assertThat(unticked.identification).isEqualTo(ReviewDiff.fields(lines, null))
    }
}
