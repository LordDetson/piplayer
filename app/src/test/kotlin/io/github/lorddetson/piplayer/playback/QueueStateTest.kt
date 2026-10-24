package io.github.lorddetson.piplayer.playback

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueueStateTest {

    private val request = QueueRequest(startSongId = 10, startRow = 0)

    private fun opened(name: String) = OpenedFile(
        uri = "content://open/$name",
        title = name,
        artist = null,
        album = null,
        durationMs = 30_000,
    )

    private fun saved(row: Int, name: String) = SavedOpenItem(row, opened(name))

    @Test
    fun `a library queue gains opened files right after the current song`() {
        val state = QueueState(request, listOf(saved(3, "old")))

        val inserted = state.withInserted(currentIndex = 1, files = listOf(opened("first"), opened("second")))

        assertThat(inserted.library).isEqualTo(request)
        assertThat(inserted.openItems.map { it.index to it.file.title })
            .containsExactly(2 to "first", 3 to "second", 5 to "old")
    }

    @Test
    fun `opened files before the current song stay where they are`() {
        val state = QueueState(request, listOf(saved(0, "before"), saved(2, "after")))

        val inserted = state.withInserted(currentIndex = 1, files = listOf(opened("new")))

        assertThat(inserted.openItems.map { it.index to it.file.title })
            .containsExactly(0 to "before", 2 to "new", 3 to "after")
    }

    @Test
    fun `a queue of opened files stays contiguous when more are added`() {
        val state = QueueState(null, listOf(saved(0, "first"), saved(1, "second")))

        val inserted = state.withInserted(currentIndex = 1, files = listOf(opened("third"), opened("fourth")))

        assertThat(inserted.library).isNull()
        assertThat(inserted.openItems.map { it.index to it.file.title })
            .containsExactly(0 to "first", 1 to "second", 2 to "third", 3 to "fourth")
    }

    @Test
    fun `an empty player starts a queue of just the opened files`() {
        val state = QueueState(request, emptyList())

        val inserted = state.withInserted(currentIndex = -1, files = listOf(opened("first"), opened("second")))

        assertThat(inserted.library).isNull()
        assertThat(inserted.openItems.map { it.index to it.file.title })
            .containsExactly(0 to "first", 1 to "second")
    }

    @Test
    fun `a queue of opened files cannot have gaps`() {
        assertThat(
            runCatching { QueueState(null, listOf(saved(1, "second"))) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a queue needs something to play`() {
        assertThat(
            runCatching { QueueState(null, emptyList()) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }
}
