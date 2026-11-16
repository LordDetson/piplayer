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
    fun `only a start at the saved song itself resumes its playback`() {
        val snapshot = librarySnapshot(songId = 20, row = 0)
        val order = QueueRestore.order(listOf(song(10), song(20), song(30)), snapshot)
        val found = QueueRestore.choose(order, snapshot)!!
        val gone = librarySnapshot(songId = 99, row = 1)
        val fallback = QueueRestore.choose(order, gone)!!

        assertThat(QueueRestore.resumesSavedItem(order, found, snapshot, currentFileId = 20)).isTrue()
        assertThat(QueueRestore.resumesSavedItem(order, fallback, gone, currentFileId = null)).isFalse()
        // The saved song resolved to another file than the one at the start.
        assertThat(QueueRestore.resumesSavedItem(order, QueueStart(0, 12_345), snapshot, currentFileId = 20)).isFalse()
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
                is QueueSlot.Added -> "added ${slot.number}"
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

    @Test
    fun `a song in the queue twice resumes at its saved row`() {
        val order = listOf(QueueSlot.Library(song(10)), QueueSlot.Added(1, song(20)), QueueSlot.Library(song(20)))

        assertThat(QueueRestore.choose(order, librarySnapshot(songId = 20, row = 2))).isEqualTo(QueueStart(2, 12_345))
        assertThat(QueueRestore.choose(order, librarySnapshot(songId = 20, row = 1))).isEqualTo(QueueStart(1, 12_345))
        assertThat(QueueRestore.choose(order, librarySnapshot(songId = 20, row = 0))).isEqualTo(QueueStart(1, 12_345))
    }

    @Test
    fun `opened files go back among added songs of a queue without library`() {
        val open = opened(1, "x")
        val snapshot = PlaybackSnapshot(
            queue = null,
            openItems = listOf(open),
            songId = -1,
            row = 1,
            positionMs = 0,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = null,
            artist = null,
            album = null,
            durationMs = 0,
            uri = open.uri,
            // The added songs are in the queue file.
            queueRevision = 3,
        )

        val order = QueueRestore.withOpened(listOf(QueueSlot.Added(1, song(10)), QueueSlot.Added(2, song(20))), snapshot)

        assertThat(order).containsExactly(QueueSlot.Added(1, song(10)), QueueSlot.Open(open), QueueSlot.Added(2, song(20)))
    }

    // The end of a song (docs/playback.md §4.3.1).

    private fun timed(id: Long, durationMs: Long?) = song(id).copy(durationMs = durationMs)

    private val three = listOf(QueueSlot.Library(timed(10, 60_000)), QueueSlot.Library(timed(20, 180_000)), QueueSlot.Library(timed(30, 600_000)))

    @Test
    fun `the end zone is 2 percent of the song at most 5 s`() {
        assertThat(QueueRestore.endZoneMs(30_000)).isEqualTo(600)
        assertThat(QueueRestore.endZoneMs(180_000)).isEqualTo(3_600)
        assertThat(QueueRestore.endZoneMs(250_000)).isEqualTo(5_000)
        assertThat(QueueRestore.endZoneMs(3_600_000)).isEqualTo(5_000)
    }

    @Test
    fun `a position at or past the end or inside the zone is at the end`() {
        assertThat(QueueRestore.isAtEnd(180_000, 180_000)).isTrue()
        assertThat(QueueRestore.isAtEnd(181_000, 180_000)).isTrue()
        assertThat(QueueRestore.isAtEnd(176_400, 180_000)).isTrue()
        assertThat(QueueRestore.isAtEnd(176_399, 180_000)).isFalse()
        assertThat(QueueRestore.isAtEnd(590_000, 600_000)).isFalse()
        assertThat(QueueRestore.isAtEnd(595_000, 600_000)).isTrue()
    }

    @Test
    fun `an unknown length is never at the end`() {
        assertThat(QueueRestore.isAtEnd(180_000, null)).isFalse()
        assertThat(QueueRestore.isAtEnd(180_000, 0)).isFalse()
    }

    @Test
    fun `a position at the end moves to the start of the next song`() {
        val start = QueueRestore.offTheEnd(three, QueueStart(1, 179_000), PlaybackSnapshot.REPEAT_OFF, playOrder = null)

        assertThat(start).isEqualTo(QueueStart(2, 0))
    }

    @Test
    fun `a position before the zone stays`() {
        val start = QueueRestore.offTheEnd(three, QueueStart(1, 170_000), PlaybackSnapshot.REPEAT_OFF, playOrder = null)

        assertThat(start).isEqualTo(QueueStart(1, 170_000))
    }

    @Test
    fun `the last song with repeat off starts again from its start`() {
        val start = QueueRestore.offTheEnd(three, QueueStart(2, 600_000), PlaybackSnapshot.REPEAT_OFF, playOrder = null)

        assertThat(start).isEqualTo(QueueStart(2, 0))
    }

    @Test
    fun `the last song with repeat all moves to the first`() {
        val start = QueueRestore.offTheEnd(three, QueueStart(2, 600_000), PlaybackSnapshot.REPEAT_ALL, playOrder = null)

        assertThat(start).isEqualTo(QueueStart(0, 0))
    }

    @Test
    fun `repeat one starts the same song again`() {
        val start = QueueRestore.offTheEnd(three, QueueStart(0, 59_900), PlaybackSnapshot.REPEAT_ONE, playOrder = null)

        assertThat(start).isEqualTo(QueueStart(0, 0))
    }

    @Test
    fun `a restored shuffle order decides the next song`() {
        val order = intArrayOf(2, 0, 1)

        assertThat(QueueRestore.offTheEnd(three, QueueStart(0, 60_000), PlaybackSnapshot.REPEAT_OFF, order)).isEqualTo(QueueStart(1, 0))
        // The last of the order has no next one with repeat off, and wraps to the first of the order with repeat all.
        assertThat(QueueRestore.offTheEnd(three, QueueStart(1, 180_000), PlaybackSnapshot.REPEAT_OFF, order)).isEqualTo(QueueStart(1, 0))
        assertThat(QueueRestore.offTheEnd(three, QueueStart(1, 180_000), PlaybackSnapshot.REPEAT_ALL, order)).isEqualTo(QueueStart(2, 0))
    }

    @Test
    fun `a song of unknown length keeps its saved position`() {
        val slots = listOf(QueueSlot.Library(timed(10, null)), QueueSlot.Library(timed(20, 60_000)))

        assertThat(QueueRestore.offTheEnd(slots, QueueStart(0, 999_000), PlaybackSnapshot.REPEAT_OFF, playOrder = null))
            .isEqualTo(QueueStart(0, 999_000))
    }

    @Test
    fun `a position far past the library length is left to the player's length`() {
        // The library says 2 s, the file is longer: the saved 5 s is the middle of the song, not past its end.
        val slots = listOf(QueueSlot.Library(timed(10, 2_000)), QueueSlot.Library(timed(20, 60_000)))

        assertThat(QueueRestore.offTheEnd(slots, QueueStart(0, 5_000), PlaybackSnapshot.REPEAT_OFF, playOrder = null))
            .isEqualTo(QueueStart(0, 5_000))
        assertThat(QueueRestore.offTheEnd(slots, QueueStart(0, 3_001), PlaybackSnapshot.REPEAT_OFF, playOrder = null))
            .isEqualTo(QueueStart(0, 3_001))
    }

    @Test
    fun `a position up to a second past the library length is at the end`() {
        // The player's length of the same file differs from the library's by rounding and its estimate.
        val slots = listOf(QueueSlot.Library(timed(10, 2_000)), QueueSlot.Library(timed(20, 60_000)))

        assertThat(QueueRestore.offTheEnd(slots, QueueStart(0, 3_000), PlaybackSnapshot.REPEAT_OFF, playOrder = null))
            .isEqualTo(QueueStart(1, 0))
        assertThat(QueueRestore.offTheEnd(slots, QueueStart(0, 2_000), PlaybackSnapshot.REPEAT_OFF, playOrder = null))
            .isEqualTo(QueueStart(1, 0))
    }

    @Test
    fun `an opened file at its end starts again when it is the whole queue`() {
        val file = opened(0, "alone")

        assertThat(QueueRestore.offTheEnd(listOf(QueueSlot.Open(file)), QueueStart(0, 30_042), PlaybackSnapshot.REPEAT_OFF, null))
            .isEqualTo(QueueStart(0, 0))
    }

    @Test
    fun `the next song after the end follows repeat and the play order`() {
        assertThat(QueueRestore.nextAfterEnd(0, 3, PlaybackSnapshot.REPEAT_OFF, null)).isEqualTo(1)
        assertThat(QueueRestore.nextAfterEnd(2, 3, PlaybackSnapshot.REPEAT_OFF, null)).isNull()
        assertThat(QueueRestore.nextAfterEnd(2, 3, PlaybackSnapshot.REPEAT_ALL, null)).isEqualTo(0)
        assertThat(QueueRestore.nextAfterEnd(1, 3, PlaybackSnapshot.REPEAT_ONE, null)).isEqualTo(1)
        assertThat(QueueRestore.nextAfterEnd(0, 1, PlaybackSnapshot.REPEAT_ALL, null)).isEqualTo(0)
        assertThat(QueueRestore.nextAfterEnd(2, 3, PlaybackSnapshot.REPEAT_OFF, intArrayOf(1, 2, 0))).isEqualTo(0)
    }

    @Test
    fun `a start moved off the end resumes no playback`() {
        val snapshot = librarySnapshot(songId = 20, row = 1, positionMs = 180_000).copy(durationMs = 180_000)
        val chosen = QueueRestore.choose(three, snapshot, currentFileId = 20)!!
        val moved = QueueRestore.offTheEnd(three, chosen, PlaybackSnapshot.REPEAT_OFF, playOrder = null)
        val replay = QueueRestore.offTheEnd(three.take(2), chosen, PlaybackSnapshot.REPEAT_OFF, playOrder = null)

        assertThat(QueueRestore.resumesSavedItem(three, chosen, snapshot, currentFileId = 20)).isTrue()
        assertThat(QueueRestore.resumesSavedItem(three, moved, snapshot, currentFileId = 20)).isFalse()
        assertThat(QueueRestore.resumesSavedItem(three.take(2), replay, snapshot, currentFileId = 20)).isFalse()
    }

    private val counted = ListenProgress(listenedMs = 295_000, counted = true, startedAt = 1_700_000_000_000)

    @Test
    fun `a counted playback that the restore does not resume ends with its saved listened time`() {
        // Saved in the end zone of song 20, which is file 21 now; the restore moved on to the next song.
        val snapshot = librarySnapshot(songId = 20, row = 1, positionMs = 298_000).copy(listen = counted)

        assertThat(QueueRestore.endedSavedPlayback(snapshot, resumes = false, currentFileId = 21)).isEqualTo("21")
    }

    @Test
    fun `a resumed, an uncounted, a gone or an opened playback ends nothing at the restore`() {
        val snapshot = librarySnapshot(songId = 20, row = 1).copy(listen = counted)
        val uncounted = snapshot.copy(listen = counted.copy(counted = false))
        val open = opened(0, "a")
        val openSnapshot = PlaybackSnapshot(
            queue = null, openItems = listOf(open), songId = -1, row = 0, positionMs = 0, shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF, title = null, artist = null, album = null, durationMs = 0,
            uri = open.uri, listen = counted,
        )

        // The resumed playback goes on and ends later; an uncounted one never becomes a play.
        assertThat(QueueRestore.endedSavedPlayback(snapshot, resumes = true, currentFileId = 20)).isNull()
        assertThat(QueueRestore.endedSavedPlayback(uncounted, resumes = false, currentFileId = 20)).isNull()
        // Without the file now its identity is not known, and a file ID of another library would name another file.
        assertThat(QueueRestore.endedSavedPlayback(snapshot, resumes = false, currentFileId = null)).isNull()
        assertThat(QueueRestore.endedSavedPlayback(snapshot.copy(listen = null), resumes = false, currentFileId = 20)).isNull()
        assertThat(QueueRestore.endedSavedPlayback(openSnapshot, resumes = false, currentFileId = null)).isNull()
    }

    private val started = ListenProgress(listenedMs = 20_000, counted = false, startedAt = 1_700_000_000_000)

    @Test
    fun `a saved playback that the restore moves off the end of its song was listened to the end`() {
        // Saved 1 s before the end of song 20, which is file 21 now; the restore moved on to the next song.
        val snapshot = librarySnapshot(songId = 20, row = 1, positionMs = 179_000).copy(durationMs = 180_000, listen = started)
        val chosen = QueueRestore.choose(three, snapshot, currentFileId = 20)!!
        val moved = QueueRestore.offTheEnd(three, chosen, PlaybackSnapshot.REPEAT_OFF, playOrder = null)

        assertThat(QueueRestore.finishedSavedPlayback(three, chosen, moved, snapshot, currentFileId = 20))
            .isEqualTo(FinishedPlayback("20", durationMs = 180_000))
        // An uncounted playback too: the outcome does not depend on the play counting.
        assertThat(QueueRestore.finishedSavedPlayback(three, chosen, moved, snapshot.copy(listen = counted), currentFileId = 20))
            .isEqualTo(FinishedPlayback("20", durationMs = 180_000))
        // Back to the start of the same song, the last one with repeat off.
        val replay = QueueRestore.offTheEnd(three.take(2), chosen, PlaybackSnapshot.REPEAT_OFF, playOrder = null)
        assertThat(replay).isEqualTo(QueueStart(1, 0))
        assertThat(QueueRestore.finishedSavedPlayback(three.take(2), chosen, replay, snapshot, currentFileId = 20))
            .isEqualTo(FinishedPlayback("20", durationMs = 180_000))
    }

    @Test
    fun `a finished saved playback without a saved length has the queue item's`() {
        val snapshot = librarySnapshot(songId = 20, row = 1, positionMs = 179_000).copy(durationMs = 0, listen = started)
        val chosen = QueueRestore.choose(three, snapshot, currentFileId = 20)!!
        val moved = QueueRestore.offTheEnd(three, chosen, PlaybackSnapshot.REPEAT_OFF, playOrder = null)

        assertThat(QueueRestore.finishedSavedPlayback(three, chosen, moved, snapshot, currentFileId = 20))
            .isEqualTo(FinishedPlayback("20", durationMs = 180_000))
    }

    @Test
    fun `a resumed, a gone, an opened or a progressless playback is not finished at the restore`() {
        val snapshot = librarySnapshot(songId = 20, row = 1, positionMs = 90_000).copy(durationMs = 180_000, listen = started)
        val chosen = QueueRestore.choose(three, snapshot, currentFileId = 20)!!
        // Resumed where it was: it goes on, and its own end decides.
        assertThat(QueueRestore.finishedSavedPlayback(three, chosen, chosen, snapshot, currentFileId = 20)).isNull()

        // Gone from the queue: the saved row plays another song from its start; whether it was skipped is not known.
        val atEnd = snapshot.copy(positionMs = 179_000)
        val gone = listOf(three[0], three[2])
        val fallback = QueueRestore.choose(gone, atEnd, currentFileId = 20)!!
        assertThat(QueueRestore.finishedSavedPlayback(gone, fallback, fallback, atEnd, currentFileId = 20)).isNull()
        // Its file not known any more.
        val unknown = QueueRestore.choose(three, atEnd, currentFileId = null)!!
        assertThat(QueueRestore.finishedSavedPlayback(three, unknown, QueueStart(2, 0), atEnd, currentFileId = null)).isNull()
        // Without saved progress: a song played to its end was written when the queue ended.
        val bare = atEnd.copy(listen = null)
        val bareChosen = QueueRestore.choose(three, bare, currentFileId = 20)!!
        val bareMoved = QueueRestore.offTheEnd(three, bareChosen, PlaybackSnapshot.REPEAT_OFF, playOrder = null)
        assertThat(QueueRestore.finishedSavedPlayback(three, bareChosen, bareMoved, bare, currentFileId = 20)).isNull()

        // An opened file at its end has no identity to write for.
        val open = opened(0, "a")
        val openOrder = listOf<QueueSlot>(QueueSlot.Open(open), three[0])
        val openSnapshot = PlaybackSnapshot(
            queue = null, openItems = listOf(open), songId = -1, row = 0, positionMs = 29_900, shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF, title = null, artist = null, album = null, durationMs = 30_000,
            uri = open.uri, listen = started,
        )
        val openChosen = QueueRestore.choose(openOrder, openSnapshot, currentFileId = null)!!
        val openMoved = QueueRestore.offTheEnd(openOrder, openChosen, PlaybackSnapshot.REPEAT_OFF, playOrder = null)
        assertThat(openMoved).isEqualTo(QueueStart(1, 0))
        assertThat(QueueRestore.finishedSavedPlayback(openOrder, openChosen, openMoved, openSnapshot, currentFileId = null)).isNull()
    }
}
