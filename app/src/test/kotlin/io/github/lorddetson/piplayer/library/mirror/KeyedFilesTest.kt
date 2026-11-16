package io.github.lorddetson.piplayer.library.mirror

import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The keyed files a whole run of the decision mirror compares before it holds off the sync, and reads again under the
 * hold (TI §10.3): the files whose keys moved in between are compared again.
 */
class KeyedFilesTest {

    private fun files(vararg pairs: Pair<Long, String>) = KeyedFiles(pairs.map { it.first }.toLongArray(), pairs.map { it.second }.toTypedArray())

    @Test
    fun `files keyed alike in both reads did not move`() {
        val read = files(1L to "a", 4L to "b", 9L to "a")

        assertThat(read.moved(files(1L to "a", 4L to "b", 9L to "a"))).isEmpty()
        assertThat(files().moved(files())).isEmpty()
    }

    @Test
    fun `a file keyed in one read only, or with another key, moved`() {
        val before = files(1L to "a", 4L to "b", 9L to "a")

        // 2 got a key, 4 lost its key, 9 got another one.
        assertThat(before.moved(files(1L to "a", 2L to "c", 9L to "b"))).containsExactlyInAnyOrder(2L, 4L, 9L)
        assertThat(before.moved(files())).containsExactlyInAnyOrder(1L, 4L, 9L)
        assertThat(files().moved(before)).containsExactlyInAnyOrder(1L, 4L, 9L)
        assertThat(files(1L to "a").moved(files(0L to "a", 7L to "a"))).containsExactlyInAnyOrder(0L, 1L, 7L)
    }

    @Test
    fun `each file's values are those of its key`() {
        val a = mapOf(DecidedField.TITLE to Decided("A", 2, 1))
        val b = mapOf(DecidedField.TITLE to Decided("B", 1, 2))
        val read = files(1L to "a", 4L to "b", 9L to "a")
        val byKey = mapOf("a" to a, "b" to b)

        assertThat(read.values(byKey)).containsExactly(a, b, a)
        assertThat(read.valuesOf(4, byKey)).isEqualTo(b)
        assertThat(read.valuesOf(5, byKey)).isEmpty()
    }

    @Test
    fun `the files must come in ascending order, each once, with a key each`() {
        assertThatThrownBy { files(4L to "a", 1L to "b") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { files(1L to "a", 1L to "b") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { KeyedFiles(longArrayOf(1), emptyArray()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
