package io.github.lorddetson.piplayer.userdata

import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Entries put back into a playlist at once: each playlist renumbered in one pass, not once per entry (library.md §20.3). */
class PlaylistRestorePlanTest {

    /** The playlist as putting back [restored] (old positions) one at a time into [count] entries makes it. */
    private fun oneAtATime(count: Int, restored: List<Int>): List<String> {
        val list = MutableList(count) { "c$it" }
        restored.sorted().forEachIndexed { index, position -> list.add(position.coerceIn(0, list.size), "r$index") }
        return list
    }

    /** The playlist as [Playlists.restorePlan] makes it: the shifts run in their order as statements, then the inserts. */
    private fun planned(count: Int, restored: List<Int>): List<String> {
        val plan = Playlists.restorePlan(count, restored.sorted())
        val positions = IntArray(count) { it }
        for (shift in plan.shifts) {
            for (i in positions.indices) if (positions[i] >= shift.from && positions[i] < shift.to) positions[i] += shift.by
        }
        val byPosition = HashMap<Int, String>()
        positions.forEachIndexed { index, position -> assertThat(byPosition.put(position, "c$index")).isNull() }
        plan.positions.forEachIndexed { index, position -> assertThat(byPosition.put(position, "r$index")).isNull() }
        assertThat(byPosition.keys).containsExactlyInAnyOrderElementsOf(0 until count + restored.size)
        return (0 until count + restored.size).map { byPosition.getValue(it) }
    }

    @Test
    fun `entries go back where one at a time would put them, past the end too`() {
        assertThat(planned(4, listOf(0, 2, 5))).isEqualTo(oneAtATime(4, listOf(0, 2, 5)))
        assertThat(planned(0, listOf(3, 7))).containsExactly("r0", "r1")
        assertThat(planned(3, listOf(1, 2, 3))).isEqualTo(oneAtATime(3, listOf(1, 2, 3)))
        val random = Random(63)
        repeat(200) {
            val count = random.nextInt(0, 40)
            val restored = (0 until count + 20).shuffled(random).take(random.nextInt(1, 15))
            assertThat(planned(count, restored)).describedAs("$count, $restored").isEqualTo(oneAtATime(count, restored))
        }
    }

    @Test
    fun `each entry that stays moves once, in at most one shift per entry put back`() {
        val plan = Playlists.restorePlan(20_000, (0 until 20_000 step 20).toList())

        assertThat(plan.shifts.size).isLessThanOrEqualTo(1_000)
        assertThat(plan.shifts.sumOf { it.to.coerceAtMost(20_000) - it.from }).isLessThanOrEqualTo(20_000)
    }
}
