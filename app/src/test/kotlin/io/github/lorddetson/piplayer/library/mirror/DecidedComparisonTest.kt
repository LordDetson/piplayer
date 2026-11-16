package io.github.lorddetson.piplayer.library.mirror

import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The comparison of a whole run of the decision mirror (TI §10.3): the values each keyed file should hold against the
 * rows of `decided_mirror`, which come one by one in any order.
 */
class DecidedComparisonTest {

    private fun values(n: Long): Map<DecidedField, Decided> = linkedMapOf(
        DecidedField.TITLE to Decided("Song $n", 2, n),
        DecidedField.ARTIST to Decided("Ann", 2, n),
        DecidedField.DISC_SUBTITLE to Decided(null, 2, n),
        DecidedField.ISRC to Decided("XXAAA0000001", 1, n + 100),
    )

    /** One stored row of `decided_mirror`. */
    private data class Row(val fileId: Long, val field: String, val value: String?, val level: Int, val decisionId: Long)

    private fun rowsOf(fileId: Long, values: Map<DecidedField, Decided>) =
        values.map { (part, value) -> Row(fileId, part.stored, value.value, value.level, value.decisionId) }

    private fun compare(desired: Map<Long, Map<DecidedField, Decided>>, rows: List<Row>): List<Long> {
        val ids = desired.keys.sorted()
        val comparison = DecidedComparison(ids.toLongArray(), ids.map(desired::getValue))
        for (row in rows) comparison.row(row.fileId, row.field, row.value, row.level, row.decisionId)
        return comparison.differing()
    }

    private val desired = mapOf(1L to values(1), 5L to values(5), 9L to values(9))
    private val mirrored = desired.flatMap { (file, values) -> rowsOf(file, values) }

    @Test
    fun `files whose rows hold every desired value differ in nothing, in whatever order the rows come`() {
        assertThat(compare(desired, mirrored)).isEmpty()
        assertThat(compare(desired, mirrored.shuffled(Random(7)))).isEmpty()
        assertThat(compare(emptyMap(), emptyList())).isEmpty()
    }

    @Test
    fun `a file with decided values and no rows differs`() {
        assertThat(compare(desired, mirrored.filter { it.fileId != 5L })).containsExactly(5L)
    }

    @Test
    fun `a file that lacks one part differs`() {
        assertThat(compare(desired, mirrored.filterNot { it.fileId == 9L && it.field == "artist" })).containsExactly(9L)
    }

    @Test
    fun `a value, a level or a decision that differs makes its file differ`() {
        fun changed(change: (Row) -> Row) = mirrored.map { if (it.fileId == 5L && it.field == "title") change(it) else it }

        assertThat(compare(desired, changed { it.copy(value = "Stray") })).containsExactly(5L)
        assertThat(compare(desired, changed { it.copy(value = null) })).containsExactly(5L)
        assertThat(compare(desired, changed { it.copy(level = 1) })).containsExactly(5L)
        assertThat(compare(desired, changed { it.copy(decisionId = 999) })).containsExactly(5L)
    }

    @Test
    fun `an empty value and a missing one are told apart`() {
        val rows = mirrored.map { if (it.fileId == 1L && it.field == "disc_subtitle") it.copy(value = "") else it }

        assertThat(compare(desired, rows)).containsExactly(1L)
    }

    @Test
    fun `rows of a file that should hold none make it differ, with the files that differ in order`() {
        val rows = mirrored + Row(7, "title", "Stray", 2, 999) + Row(3, "album", null, 1, 4) + Row(7, "artist", "Stray", 2, 999)
        val stale = mirrored.map { if (it.fileId == 9L && it.field == "isrc") it.copy(level = 2) else it }

        assertThat(compare(desired, rows)).containsExactly(3L, 7L)
        assertThat(compare(desired, stale + Row(7, "title", "Stray", 2, 999) + Row(2, "title", null, 2, 1))).containsExactly(2L, 7L, 9L)
    }

    @Test
    fun `a row of a part this build does not know makes its file differ`() {
        assertThat(compare(desired, mirrored + Row(5, "mood", "Calm", 2, 5))).containsExactly(5L)
    }

    @Test
    fun `rows read in parts, a file's rows spread over several of them, compare as rows read at once`() {
        fun inParts(rows: List<Row>, parts: Int): List<Long> {
            val ids = desired.keys.sorted()
            val comparison = DecidedComparison(ids.toLongArray(), ids.map(desired::getValue))
            val readers = List(parts) { comparison.part() }
            rows.forEachIndexed { i, row -> readers[i % parts].row(row.fileId, row.field, row.value, row.level, row.decisionId) }
            return comparison.differing()
        }
        val shuffled = mirrored.shuffled(Random(3))
        val changed = shuffled.map { if (it.fileId == 9L && it.field == "title") it.copy(value = "Stray") else it }

        for (parts in 1..5) {
            assertThat(inParts(shuffled, parts)).describedAs("$parts parts").isEmpty()
            assertThat(inParts(shuffled.filterNot { it.fileId == 1L && it.field == "isrc" }, parts)).describedAs("$parts parts").containsExactly(1L)
            assertThat(inParts(changed + Row(4, "title", null, 2, 1), parts)).describedAs("$parts parts").containsExactly(4L, 9L)
        }
    }

    @Test
    fun `the desired values of a file that differs are at hand for its write`() {
        val ids = desired.keys.sorted()
        val comparison = DecidedComparison(ids.toLongArray(), ids.map(desired::getValue))

        assertThat(comparison.desiredOf(5)).isEqualTo(values(5))
        assertThat(comparison.desiredOf(7)).isEmpty()
    }

    @Test
    fun `the files must come in ascending order, each once, with their values`() {
        assertThatThrownBy { DecidedComparison(longArrayOf(5, 1), listOf(values(5), values(1))) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DecidedComparison(longArrayOf(1, 1), listOf(values(1), values(1))) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DecidedComparison(longArrayOf(1), emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
