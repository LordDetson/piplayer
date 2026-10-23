package io.github.lorddetson.piplayer.playback

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueueRestoreTest {

    private fun snapshot(songId: Long, row: Int, positionMs: Long = 12_345) = PlaybackSnapshot(
        queue = QueueRequest(startSongId = 10, startRow = 0),
        songId = songId,
        row = row,
        positionMs = positionMs,
        shuffle = false,
        repeatMode = PlaybackSnapshot.REPEAT_OFF,
        title = "Title",
        artist = null,
        album = null,
        durationMs = 60_000,
        uri = null,
    )

    @Test
    fun `a saved song that is still there keeps its position`() {
        assertThat(QueueRestore.choose(listOf(10L, 20L, 30L), snapshot(songId = 20, row = 0)))
            .isEqualTo(QueueStart(index = 1, positionMs = 12_345))
    }

    @Test
    fun `a saved song that is gone starts at its row without a position`() {
        assertThat(QueueRestore.choose(listOf(10L, 20L, 30L), snapshot(songId = 99, row = 1)))
            .isEqualTo(QueueStart(index = 1, positionMs = 0))
    }

    @Test
    fun `a row beyond the list starts at the last song`() {
        assertThat(QueueRestore.choose(listOf(10L, 20L, 30L), snapshot(songId = 99, row = 20)))
            .isEqualTo(QueueStart(index = 2, positionMs = 0))
    }

    @Test
    fun `an empty list has no start`() {
        assertThat(QueueRestore.choose(emptyList(), snapshot(songId = 10, row = 0))).isNull()
    }
}
