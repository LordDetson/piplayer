package io.github.lorddetson.piplayer.covers

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** When the loader forgets what it holds in memory (docs/library.md §13.3). */
class CoverInvalidationsTest {

    private fun count(revisions: List<Long>, running: List<Boolean>) = runBlocking {
        CoverInvalidations.of(revisions.asFlow(), running.asFlow()).toList().size
    }

    @Test
    fun eachNewRevisionAfterTheFirstCounts() {
        assertThat(count(listOf(5), emptyList())).isZero()
        assertThat(count(listOf(5, 5, 6, 7, 7), emptyList())).isEqualTo(2)
    }

    @Test
    fun theEndOfEachRunOfTheTagPassCounts() {
        assertThat(count(emptyList(), listOf(false, true, true, false, false, true, false))).isEqualTo(2)
        assertThat(count(emptyList(), listOf(false, false))).isZero()
        assertThat(count(emptyList(), listOf(true))).isZero()
    }

    @Test
    fun eachAttachPassThatChangedIdentitiesCounts() = runBlocking<Unit> {
        val count = CoverInvalidations.of(emptyList<Long>().asFlow(), emptyList<Boolean>().asFlow(), listOf(0L, 0L, 1L, 2L).asFlow())
            .toList().size

        assertThat(count).isEqualTo(2)
    }

    @Test
    fun eachChangeOfTheHiddenFilesCounts() = runBlocking<Unit> {
        // They decide which albums are an artist's (docs/library.md §13.1) and raise no revision of the library.
        val count = CoverInvalidations.of(
            emptyList<Long>().asFlow(),
            emptyList<Boolean>().asFlow(),
            hiddenFiles = listOf<Any>("a", "a", "b", "c", "c").asFlow(),
        ).toList().size

        assertThat(count).isEqualTo(2)
    }

    @Test
    fun bothCount() {
        assertThat(count(listOf(1, 2), listOf(true, false))).isEqualTo(2)
    }
}
