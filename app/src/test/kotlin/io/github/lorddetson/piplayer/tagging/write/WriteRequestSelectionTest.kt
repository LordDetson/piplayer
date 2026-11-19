package io.github.lorddetson.piplayer.tagging.write

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WriteRequestSelectionTest {
    @Test
    fun `all 500 files fit in one consent request`() {
        val files = (1..500).toList()

        assertThat(writeRequestGroups(files, maxUris = 2_000)).containsExactly(files)
    }

    @Test
    fun `requests are deduplicated in order and use the fewest groups within the platform limit`() {
        assertThat(writeRequestGroups(listOf(5, 1, 5, 3, 2, 1, 4), maxUris = 2))
            .containsExactly(listOf(5, 1), listOf(3, 2), listOf(4))
    }

    @Test
    fun `an empty selection never asks for consent`() {
        assertThat(writeRequestGroups(emptyList<Int>(), maxUris = 2_000)).isEmpty()
    }
}
