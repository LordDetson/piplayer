package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.identity.TrackRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The queue file, format 1 (docs/playback.md §4.8). */
class QueueFileTest {

    private val track = TrackRef(
        fileId = 12,
        identityId = 99,
        contentKey = "00ff".repeat(16),
        place = Place("external_primary", "Music/Кино/", "Группа крови.mp3"),
        durationMs = 285_000,
    )

    private val full = QueueFile(
        revision = 41,
        libraryId = "c0ffee00-1111-2222-3333-444455556666",
        added = listOf(AddedEntry(1, track), AddedEntry(2, TrackRef(13, null, null, null, null))),
        journal = listOf(
            QueueEdit.Insert(after = EntryKey.Song(5), entries = listOf(EntryKey.Added(1), EntryKey.Added(2)), row = 3),
            QueueEdit.Insert(after = null, entries = listOf(EntryKey.Added(3)), row = 0),
            QueueEdit.Remove(entry = EntryKey.Opened(0), row = 7),
            QueueEdit.Move(entry = EntryKey.Added(1), after = null, row = 0),
            QueueEdit.Move(entry = EntryKey.Song(6), after = EntryKey.Added(2), row = 9),
        ),
        order = listOf(EntryKey.Song(7), EntryKey.Added(1), EntryKey.Opened(0), EntryKey.Song(Long.MAX_VALUE)),
        nextNumber = 4,
    )

    private fun roundTrip(file: QueueFile): QueueFile? {
        val bytes = ByteArrayOutputStream().also { QueueFileCodec.write(file, it) }.toByteArray()
        return QueueFileCodec.read(ByteArrayInputStream(bytes))
    }

    private fun bytesOf(file: QueueFile) = ByteArrayOutputStream().also { QueueFileCodec.write(file, it) }.toByteArray()

    @Test
    fun everythingRoundTrips() {
        assertThat(roundTrip(full)).isEqualTo(full)
    }

    @Test
    fun anEmptyFileRoundTripsWithoutOrderOrLibrary() {
        val empty = QueueFile(revision = 1, libraryId = null)

        assertThat(roundTrip(empty)).isEqualTo(empty)
    }

    @Test
    fun anOrderOfTwentyThousandSongsStaysSmall() {
        val big = QueueFile(revision = 2, libraryId = "x", order = List(20_000) { EntryKey.Song(it + 1L) })
        val bytes = bytesOf(big)

        assertThat(QueueFileCodec.read(ByteArrayInputStream(bytes))).isEqualTo(big)
        assertThat(bytes.size).isLessThan(80_000)
    }

    @Test
    fun aCutFileIsNotRead() {
        val bytes = bytesOf(full)

        for (length in listOf(0, 3, bytes.size / 2, bytes.size - 1)) {
            assertThat(QueueFileCodec.read(ByteArrayInputStream(bytes.copyOf(length)))).describedAs("cut at $length").isNull()
        }
    }

    @Test
    fun aFileWithTrailingBytesOrAChangedByteIsNotRead() {
        val bytes = bytesOf(full)

        assertThat(QueueFileCodec.read(ByteArrayInputStream(bytes + byteArrayOf(0)))).isNull()
        val changed = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertThat(QueueFileCodec.read(ByteArrayInputStream(changed))).isNull()
    }

    @Test
    fun anotherFormatIsNotRead() {
        val bytes = bytesOf(full)
        // The format follows the four magic bytes; the CRC is made again, so that only the format tells.
        val body = bytes.copyOf(bytes.size - 4).also { it[7] = (QueueFileCodec.FORMAT + 1).toByte() }
        val crc = java.util.zip.CRC32().apply { update(body) }.value.toInt()
        val later = body + java.nio.ByteBuffer.allocate(4).putInt(crc).array()

        assertThat(QueueFileCodec.read(ByteArrayInputStream(later))).isNull()
        assertThat(QueueFileCodec.read(ByteArrayInputStream("not a queue".toByteArray()))).isNull()
    }
}
