package io.github.lorddetson.piplayer.library

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A counter's flow asks for work only when the counter changes (docs/library.md §3.1, §15.4). */
class CounterChangesTest {

    @Test
    fun `the first value and repeats of the same value are no changes`() = runBlocking<Unit> {
        // Room emits again on each write to the table: 4 stays 4 while the other counter of the row moves.
        val changes = flowOf(4L, 4L, 4L, 5L, 5L, 6L).counterChanges().toList()

        assertThat(changes).containsExactly(5L, 6L)
    }
}
