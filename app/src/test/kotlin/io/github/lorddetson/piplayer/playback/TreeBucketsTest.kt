package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.lists.SongOutline
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How long lists of the library tree split into buckets and pages (docs/playback.md §10.2). */
class TreeBucketsTest {

    /** Sections of a list: [counts] entries of each section in turn. */
    private fun sections(vararg counts: Pair<String?, Int>): List<String?> = counts.flatMap { (section, count) -> List(count) { section } }

    private fun buckets(sections: List<String?>, max: Int = TreeBuckets.MAX_CHILDREN) = TreeBuckets.of(sections.size, sections::get, max = max)

    @Test
    fun aListThatFitsInOneNodeHasNoBuckets() {
        assertThat(buckets(sections("A" to 300, "B" to 200))).isEmpty()
        assertThat(buckets(emptyList())).isEmpty()
    }

    @Test
    fun aLongListSplitsByItsSectionsInListOrder() {
        val list = sections("#" to 20, "A" to 300, "B" to 250, "Я" to 31)

        val result = buckets(list)

        assertThat(result).containsExactly(
            TreeBucket("#", 1, 1, 0, 20),
            TreeBucket("A", 1, 1, 20, 320),
            TreeBucket("B", 1, 1, 320, 570),
            TreeBucket("Я", 1, 1, 570, 601),
        )
        assertThat(result.map { it.title }).containsExactly("#", "A", "B", "Я")
    }

    @Test
    fun aSectionOfMoreThanTheLimitSplitsIntoNumberedParts() {
        val result = buckets(sections("A" to 1_200, "B" to 10))

        assertThat(result).containsExactly(
            TreeBucket("A", 1, 3, 0, 500),
            TreeBucket("A", 2, 3, 500, 1_000),
            TreeBucket("A", 3, 3, 1_000, 1_200),
            TreeBucket("B", 1, 1, 1_200, 1_210),
        )
        assertThat(result.map { it.title }).containsExactly("A (1)", "A (2)", "A (3)", "B")
    }

    @Test
    fun aSectionThatStartsTwoRunsCountsItsPartsOn() {
        // Pinyin: the Latin letters have a run among the Han characters and one among the Latin ones.
        val result = buckets(sections("A" to 300, "B" to 300, "A" to 300))

        assertThat(result).containsExactly(
            TreeBucket("A", 1, 2, 0, 300),
            TreeBucket("B", 1, 1, 300, 600),
            TreeBucket("A", 2, 2, 600, 900),
        )
        assertThat(result.map { it.title }).containsExactly("A (1)", "B", "A (2)")
    }

    @Test
    fun entriesWithoutSectionsSplitIntoRunsOfRows() {
        val result = buckets(sections(null to 1_200))

        assertThat(result).containsExactly(
            TreeBucket("", 1, 3, 0, 500),
            TreeBucket("", 2, 3, 500, 1_000),
            TreeBucket("", 3, 3, 1_000, 1_200),
        )
        assertThat(result.map { it.title }).containsExactly("1-500", "501-1000", "1001-1200")
    }

    @Test
    fun anEntryWithoutASectionAmongOthersCountsAsMissing() {
        val result = buckets(sections("1999" to 400, null to 200))

        assertThat(result.map { it.section }).containsExactly("1999", "?")
    }

    @Test
    fun tooManySectionsSplitIntoRunsOfRows() {
        val list = List(600) { it.toString() }

        val result = buckets(list)

        assertThat(result).containsExactly(TreeBucket("", 1, 2, 0, 500), TreeBucket("", 2, 2, 500, 600))
        // At the limit itself they still split by section.
        assertThat(buckets(List(10) { it.toString() }, max = 5)).hasSize(2)
        assertThat(buckets(List(10) { (it / 2).toString() }, max = 5)).hasSize(5)
    }

    @Test
    fun noBucketIsLargerThanTheLimit() {
        val list = sections("A" to 501, "B" to 2, "C" to 999, null to 0)

        val result = buckets(list)

        assertThat(result).allSatisfy { assertThat(it.to - it.from).isBetween(1, TreeBuckets.MAX_CHILDREN) }
        assertThat(result.first().from).isZero()
        assertThat(result.last().to).isEqualTo(list.size)
        assertThat(result.zipWithNext()).allSatisfy { (a, b) -> assertThat(a.to).isEqualTo(b.from) }
    }

    @Test
    fun aListThatFitsByCountButNotByBytesSplitsToo() {
        val list = sections("A" to 200, "B" to 200)
        // 1 000 bytes an entry: 240 entries fit the budget of 240 KB.
        val weighed = TreeBuckets.of(list.size, list::get, { 1_024 })

        assertThat(weighed).containsExactly(TreeBucket("A", 1, 1, 0, 200), TreeBucket("B", 1, 1, 200, 400))
        val heavy = TreeBuckets.of(list.size, list::get, { 2_048 })
        assertThat(heavy.map { it.title }).containsExactly("A (1)", "A (2)", "B (1)", "B (2)")
        assertThat(heavy.map { it.to - it.from }).containsExactly(120, 80, 120, 80)
        assertThat(heavy).allSatisfy { assertThat((it.to - it.from) * 2_048).isLessThanOrEqualTo(TreeBuckets.MAX_BYTES) }
        // Light entries stay one node.
        assertThat(TreeBuckets.of(list.size, list::get, { 100 })).isEmpty()
        // Runs of rows are cut by bytes as well, and an entry heavier than the budget still gets a bucket of its own.
        assertThat(TreeBuckets.of(3, { null }, { if (it == 1) 300_000 else 10 }, max = 2).map { it.to - it.from }).containsExactly(1, 1, 1)
    }

    @Test
    fun noBucketHoldsBothNodesAndSongs() {
        // An artist's albums of 2020 at the end, their songs in reverse year order starting with 2020.
        val list = sections("2019" to 3, "2020" to 2, "2020" to 400, "2019" to 200)

        val result = TreeBuckets.of(list.size, list::get, boundary = 5)

        assertThat(result.map { it.from to it.to }).containsExactly(0 to 3, 3 to 5, 5 to 405, 405 to 605)
        assertThat(result.map { it.title }).containsExactly("2019 (1)", "2020 (1)", "2020 (2)", "2019 (2)")
        // Runs of rows too.
        assertThat(TreeBuckets.of(600, { null }, boundary = 3).map { it.from to it.to }).containsExactly(0 to 3, 3 to 503, 503 to 600)
    }

    @Test
    fun anItemWeighsItsIdAndTextsAsAParcelWritesThem() {
        val bare = TreeBuckets.weight(0, null, null, null)
        assertThat(TreeBuckets.weight(10, null, null, null) - bare).isEqualTo(20)
        // Texts in UTF-8: Latin one byte, Cyrillic two, Chinese three, a surrogate pair four.
        assertThat(TreeBuckets.weight(0, "abc", null, null) - bare).isEqualTo(12 + 3)
        assertThat(TreeBuckets.weight(0, "Ёж", "", "中文") - bare).isEqualTo(12 + 4 + 12 + 6)
        assertThat(TreeBuckets.weight(0, "🎵", null, null) - bare).isEqualTo(12 + 4)
        // The artwork URI of step 13, in UTF-16 as a parcel writes a URI.
        assertThat(TreeBuckets.weight(0, null, null, null, artworkUriLength = 40) - bare).isEqualTo(12 + 80)
    }

    @Test
    fun theWeightFromAnOutlinesBytesIsTheWeightFromTheTexts() {
        val texts = listOf(null, "", "Alpha", "Ёжик в тумане", "夜に駆ける", "🎵 tunes", "x".repeat(300))
        for (title in texts) {
            for (subtitle in texts) {
                val description = texts[(title?.length ?: 0) % texts.size]
                fun bytes(text: String?) = text?.toByteArray(Charsets.UTF_8)?.size ?: 0
                assertThat(SongOutline.utf8Bytes(title)).isEqualTo(bytes(title))
                assertThat(TreeBuckets.weightOfBytes(57, bytes(title), bytes(subtitle), bytes(description), 40))
                    .describedAs("$title / $subtitle / $description")
                    .isEqualTo(TreeBuckets.weight(57, title, subtitle, description, 40))
            }
        }
    }

    @Test
    fun aPageIsCutFromTheChildren() {
        assertThat(TreeBuckets.page(10, 0, 4)).isEqualTo(0..3)
        assertThat(TreeBuckets.page(10, 1, 4)).isEqualTo(4..7)
        assertThat(TreeBuckets.page(10, 2, 4)).isEqualTo(8..9)
        assertThat(TreeBuckets.page(10, 3, 4).isEmpty()).isTrue()
        // Legacy browsers ask for everything at once.
        assertThat(TreeBuckets.page(500, 0, Int.MAX_VALUE)).isEqualTo(0..499)
        assertThat(TreeBuckets.page(500, 1, Int.MAX_VALUE).isEmpty()).isTrue()
        assertThat(TreeBuckets.page(500, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty()).isTrue()
        assertThat(TreeBuckets.page(0, 0, 10).isEmpty()).isTrue()
        assertThat(TreeBuckets.page(10, -1, 10).isEmpty()).isTrue()
        assertThat(TreeBuckets.page(10, 0, 0).isEmpty()).isTrue()
    }
}
