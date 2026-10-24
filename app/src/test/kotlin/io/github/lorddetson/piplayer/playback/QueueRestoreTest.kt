package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.songs.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueueRestoreTest {

    private fun song(id: Long) = Song(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = "Song $id",
        artist = null,
        album = null,
        durationMs = 60_000,
    )

    private fun opened(row: Int, name: String) = SavedOpenItem(
        index = row,
        file = OpenedFile(
            uri = "content://open/$name",
            title = name,
            artist = null,
            album = null,
            durationMs = 30_000,
        ),
    )

    private fun librarySnapshot(songId: Long, row: Int, positionMs: Long = 12_345) = PlaybackSnapshot(
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
    fun `a library queue keeps its order`() {
        val order = QueueRestore.order(listOf(song(10), song(20), song(30)), librarySnapshot(songId = 20, row = 0))

        assertThat(order.map { (it as QueueSlot.Library).song.id }).containsExactly(10L, 20L, 30L)
    }

    @Test
    fun `a saved song that is still there keeps its position`() {
        val snapshot = librarySnapshot(songId = 20, row = 0)
        val order = QueueRestore.order(listOf(song(10), song(20), song(30)), snapshot)

        assertThat(QueueRestore.choose(order, snapshot))
            .isEqualTo(QueueStart(index = 1, positionMs = 12_345))
    }

    @Test
    fun `a saved song that is gone starts at its row without a position`() {
        val snapshot = librarySnapshot(songId = 99, row = 1)
        val order = QueueRestore.order(listOf(song(10), song(20), song(30)), snapshot)

        assertThat(QueueRestore.choose(order, snapshot)).isEqualTo(QueueStart(index = 1, positionMs = 0))
    }

    @Test
    fun `a row beyond the list starts at the last song`() {
        val snapshot = librarySnapshot(songId = 99, row = 20)
        val order = QueueRestore.order(listOf(song(10), song(20), song(30)), snapshot)

        assertThat(QueueRestore.choose(order, snapshot)).isEqualTo(QueueStart(index = 2, positionMs = 0))
    }

    @Test
    fun `an empty list has no start`() {
        assertThat(QueueRestore.choose(emptyList(), librarySnapshot(songId = 10, row = 0))).isNull()
    }

    @Test
    fun `opened files are put back at their saved rows`() {
        val snapshot = PlaybackSnapshot(
            queue = QueueRequest(startSongId = 10, startRow = 0),
            openItems = listOf(opened(1, "first"), opened(3, "second")),
            songId = 10,
            row = 0,
            positionMs = 0,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = "Song 10",
            artist = null,
            album = null,
            durationMs = 60_000,
            uri = null,
        )

        val order = QueueRestore.order(listOf(song(10), song(20), song(30)), snapshot)

        assertThat(order.map { slot ->
            when (slot) {
                is QueueSlot.Library -> "library ${slot.song.id}"
                is QueueSlot.Open -> slot.item.file.title
            }
        }).containsExactly("library 10", "first", "library 20", "second", "library 30")
    }

    @Test
    fun `a current opened file is found by its uri in a mixed queue`() {
        val openItems = listOf(opened(1, "first"), opened(3, "second"))
        val snapshot = PlaybackSnapshot(
            queue = QueueRequest(startSongId = 10, startRow = 0),
            openItems = openItems,
            songId = -1,
            row = 3,
            positionMs = 7_000,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = "second",
            artist = null,
            album = null,
            durationMs = 30_000,
            uri = openItems[1].uri,
        )

        val order = QueueRestore.order(listOf(song(10), song(20)), snapshot)

        assertThat(QueueRestore.choose(order, snapshot)).isEqualTo(QueueStart(index = 3, positionMs = 7_000))
        assertThat(QueueRestore.openItems(order).map { it.index }).containsExactly(1, 3)
    }

    @Test
    fun `an opened file that moved is still found by its uri`() {
        val openItems = listOf(opened(0, "first"))
        val snapshot = PlaybackSnapshot(
            queue = QueueRequest(startSongId = 10, startRow = 0),
            openItems = openItems,
            songId = -1,
            row = 0,
            positionMs = 4_000,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = "first",
            artist = null,
            album = null,
            durationMs = 30_000,
            uri = openItems[0].uri,
        )

        val order = QueueRestore.order(listOf(song(10), song(20)), snapshot)

        assertThat(QueueRestore.choose(order, snapshot)).isEqualTo(QueueStart(index = 0, positionMs = 4_000))
    }

    @Test
    fun `opened files move together when the library shrank`() {
        val openItems = listOf(opened(1, "first"), opened(3, "second"))
        val snapshot = PlaybackSnapshot(
            queue = QueueRequest(startSongId = 10, startRow = 0),
            openItems = openItems,
            songId = -1,
            row = 3,
            positionMs = 6_000,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = "second",
            artist = null,
            album = null,
            durationMs = 30_000,
            uri = openItems[1].uri,
        )

        val order = QueueRestore.order(listOf(song(10)), snapshot)

        assertThat(QueueRestore.openItems(order).map { it.index }).containsExactly(1, 2)
        assertThat(QueueRestore.choose(order, snapshot)).isEqualTo(QueueStart(index = 2, positionMs = 6_000))
    }
}
