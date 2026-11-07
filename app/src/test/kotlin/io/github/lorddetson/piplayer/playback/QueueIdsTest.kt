package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.playback.EntryKey.Added
import io.github.lorddetson.piplayer.playback.EntryKey.Opened
import io.github.lorddetson.piplayer.playback.EntryKey.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The media IDs of queue items (docs/playback.md §9.4). */
class QueueIdsTest {

    private val opened = SavedOpenItem(2, OpenedFile("content://files/1", "Opened"))

    @Test
    fun anAddedEntryNamesItsFileAndItsNumber() {
        val id = QueueIds.added(number = 7, fileId = 1234)

        assertThat(id).isEqualTo("1234/a7")
        assertThat(QueueIds.fileId(id)).isEqualTo(1234)
        assertThat(QueueIds.addedNumber(id)).isEqualTo(7)
    }

    @Test
    fun aLibrarySongIsItsFileId() {
        assertThat(QueueIds.fileId("1234")).isEqualTo(1234)
        assertThat(QueueIds.addedNumber("1234")).isNull()
    }

    @Test
    fun otherMediaIdsNameNoFile() {
        listOf("", "content://files/1", "q2/songs?key=", "12/b3", "12/a", "/a3", "a/a3", "12/a3/x", "-5/a1").forEach { id ->
            assertThat(QueueIds.fileId(id)).describedAs(id).isNull()
            assertThat(QueueIds.addedNumber(id)).describedAs(id).isNull()
        }
    }

    @Test
    fun keysNameSongsAddedEntriesAndOpenedFilesAtTheirRows() {
        val ids = listOf("10", "10/a1", "content://files/1", "20/a2")

        assertThat(QueueIds.keys(ids, listOf(opened))).containsExactly(Song(10), Added(1), Opened(0), Added(2))
    }

    @Test
    fun anUnknownItemOrAnOpenedFileAtAnotherRowHasNoKeys() {
        assertThat(QueueIds.keys(listOf("10", "file:///sdcard/x.mp3"), emptyList())).isNull()
        assertThat(QueueIds.keys(listOf("content://files/1", "10"), listOf(opened))).isNull()
    }
}
