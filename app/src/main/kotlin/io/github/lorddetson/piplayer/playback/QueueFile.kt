package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.identity.TrackRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** An entry of the queue by what it is, not by its row (docs/playback.md §4.8). */
sealed interface EntryKey {
    /** A library song of the compact request, by its file ID. */
    data class Song(val fileId: Long) : EntryKey

    /** A file opened from another app, by its place in the snapshot's list of opened files. */
    data class Opened(val index: Int) : EntryKey

    /** An entry an edit added, by the number the queue file gave it. */
    data class Added(val number: Long) : EntryKey
}

/** An entry an edit added, with the track it plays. */
data class AddedEntry(val number: Long, val track: TrackRef)

/**
 * An edit of the queue (step 9). Each names entries by their keys and keeps the [row] it was made at, so that it still
 * applies to a list the compact request reads changed.
 */
sealed interface QueueEdit {
    val row: Int

    /** [entries] (added ones) go right after [after], or first when it is null. */
    data class Insert(val after: EntryKey?, val entries: List<EntryKey.Added>, override val row: Int) : QueueEdit

    data class Remove(val entry: EntryKey, override val row: Int) : QueueEdit

    /** [entry] goes right after [after], or first when it is null. */
    data class Move(val entry: EntryKey, val after: EntryKey?, override val row: Int) : QueueEdit
}

/**
 * The structure of the queue that changes only when the queue changes, kept in `queue.bin` (docs/playback.md §4.8).
 * [order] is the play order while shuffle is on.
 */
data class QueueFile(
    val revision: Long,
    val libraryId: String?,
    val added: List<AddedEntry> = emptyList(),
    val journal: List<QueueEdit> = emptyList(),
    val order: List<EntryKey>? = null,
    val nextNumber: Long = 1,
    /** The entries of the up-next run (docs/playback.md §9.1), added ones; format 2. */
    val upNext: List<EntryKey> = emptyList(),
)

/**
 * Reads and writes [QueueFile], format 2: four magic bytes and the format, the values in a fixed order with numbers as
 * variable-length integers, and a CRC-32 of everything before it at the end. Format 2 adds the up-next run after the
 * order; format 1 files are still read, without a run.
 */
object QueueFileCodec {

    const val FORMAT = 2

    /** The format of M2a step 8, without the up-next run. */
    private const val FORMAT_1 = 1

    private val MAGIC = "PIQF".toByteArray(Charsets.US_ASCII)

    private const val SONG = 0
    private const val OPENED = 1
    private const val ADDED = 2
    private const val NONE = 3

    private const val INSERT = 0
    private const val REMOVE = 1
    private const val MOVE = 2

    fun write(file: QueueFile, output: OutputStream) {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).run {
            write(MAGIC)
            writeInt(FORMAT)
            writeLong(file.revision)
            text(file.libraryId)
            number(file.nextNumber)
            number(file.added.size.toLong())
            for (entry in file.added) {
                number(entry.number)
                track(entry.track)
            }
            number(file.journal.size.toLong())
            for (edit in file.journal) {
                when (edit) {
                    is QueueEdit.Insert -> {
                        writeByte(INSERT)
                        key(edit.after)
                        number(edit.entries.size.toLong())
                        edit.entries.forEach { key(it) }
                    }
                    is QueueEdit.Remove -> {
                        writeByte(REMOVE)
                        key(edit.entry)
                    }
                    is QueueEdit.Move -> {
                        writeByte(MOVE)
                        key(edit.entry)
                        key(edit.after)
                    }
                }
                number(edit.row.toLong())
            }
            writeBoolean(file.order != null)
            file.order?.let { order ->
                number(order.size.toLong())
                order.forEach { key(it) }
            }
            number(file.upNext.size.toLong())
            file.upNext.forEach { key(it) }
            flush()
        }
        val bytes = body.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        output.write(bytes)
        output.write(ByteBuffer.allocate(4).putInt(crc).array())
        output.flush()
    }

    /** The queue file in [input], or null when it is damaged or of a format this version does not know. */
    fun read(input: InputStream): QueueFile? {
        val all = input.readBytes()
        if (all.size < MAGIC.size + 8) return null
        val body = all.copyOfRange(0, all.size - 4)
        val crc = ByteBuffer.wrap(all, all.size - 4, 4).int
        if (CRC32().apply { update(body) }.value.toInt() != crc) return null
        return try {
            DataInputStream(ByteArrayInputStream(body)).decode()
        } catch (e: IOException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    private fun DataInputStream.decode(): QueueFile? {
        val magic = ByteArray(MAGIC.size).also { readFully(it) }
        if (!magic.contentEquals(MAGIC)) return null
        val format = readInt()
        if (format != FORMAT && format != FORMAT_1) return null
        val revision = readLong()
        val libraryId = text()
        val nextNumber = number()
        val added = List(count()) { AddedEntry(number(), track()) }
        val journal = List(count()) { edit() }
        val order = if (readBoolean()) List(count()) { key() ?: throw IOException("An order without an entry") } else null
        val upNext = if (format == FORMAT_1) emptyList() else List(count()) { key() ?: throw IOException("A run without an entry") }
        if (available() != 0) return null
        return QueueFile(revision, libraryId, added, journal, order, nextNumber, upNext)
    }

    private fun DataInputStream.edit(): QueueEdit = when (readByte().toInt()) {
        INSERT -> {
            val after = key()
            val entries = List(count()) { key() as? EntryKey.Added ?: throw IOException("An insert of an entry not added") }
            QueueEdit.Insert(after, entries, row())
        }
        REMOVE -> QueueEdit.Remove(key() ?: throw IOException("A removal without an entry"), row())
        MOVE -> {
            val entry = key() ?: throw IOException("A move without an entry")
            QueueEdit.Move(entry, key(), row())
        }
        else -> throw IOException("An unknown edit")
    }

    private fun DataInputStream.row(): Int = Math.toIntExact(number())

    private fun DataOutputStream.number(value: Long) {
        var rest = value
        while (rest and 0x7FL.inv() != 0L) {
            writeByte(((rest and 0x7F) or 0x80).toInt())
            rest = rest ushr 7
        }
        writeByte(rest.toInt())
    }

    private fun DataInputStream.number(): Long {
        var value = 0L
        var shift = 0
        while (shift < 64) {
            val byte = readUnsignedByte()
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
        throw IOException("A number too long")
    }

    /** A count of items of at least one byte each, which must fit what is left. */
    private fun DataInputStream.count(): Int {
        val count = number()
        if (count < 0 || count > available()) throw IOException("A count beyond the end")
        return count.toInt()
    }

    private fun DataOutputStream.text(value: String?) {
        writeBoolean(value != null)
        value?.let(::writeUTF)
    }

    private fun DataInputStream.text(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.key(key: EntryKey?) {
        when (key) {
            is EntryKey.Song -> {
                writeByte(SONG)
                number(key.fileId)
            }
            is EntryKey.Opened -> {
                writeByte(OPENED)
                number(key.index.toLong())
            }
            is EntryKey.Added -> {
                writeByte(ADDED)
                number(key.number)
            }
            null -> writeByte(NONE)
        }
    }

    private fun DataInputStream.key(): EntryKey? = when (readByte().toInt()) {
        SONG -> EntryKey.Song(number())
        OPENED -> EntryKey.Opened(Math.toIntExact(number()))
        ADDED -> EntryKey.Added(number())
        NONE -> null
        else -> throw IOException("An unknown entry")
    }

    private fun DataOutputStream.track(track: TrackRef) {
        number(track.fileId)
        writeBoolean(track.identityId != null)
        track.identityId?.let { number(it) }
        text(track.contentKey)
        writeBoolean(track.place != null)
        track.place?.let {
            writeUTF(it.volume)
            writeUTF(it.relativePath)
            writeUTF(it.displayName)
        }
        writeBoolean(track.durationMs != null)
        track.durationMs?.let { number(it) }
    }

    private fun DataInputStream.track() = TrackRef(
        fileId = number(),
        identityId = if (readBoolean()) number() else null,
        contentKey = text(),
        place = if (readBoolean()) Place(readUTF(), readUTF(), readUTF()) else null,
        durationMs = if (readBoolean()) number() else null,
    )
}

/** Replays the edit journal over the list the compact request reads (docs/playback.md §4.8). */
object QueueJournal {

    /** Above this many edits the journal is folded into a fixed list. */
    const val MAX_EDITS = 200

    /**
     * [base] with [edits] applied in order. Without [songKeys] the file IDs of library songs name other files (another
     * library ID, docs/playback.md §9.4): edits of library songs are left out and an insert after one goes to its row.
     */
    fun replay(base: List<EntryKey>, edits: List<QueueEdit>, songKeys: Boolean = true): List<EntryKey> {
        if (edits.isEmpty()) return base
        val list = base.toMutableList()
        for (edit in edits) {
            when (edit) {
                is QueueEdit.Insert -> {
                    val after = edit.after.takeUnless { !songKeys && it is EntryKey.Song }
                    // An anchor that names another file now is not trusted: the row stands instead.
                    val at = if (after == null && edit.after != null) edit.row.coerceIn(0, list.size) else placeAfter(list, after, edit.row)
                    list.addAll(at, edit.entries)
                }
                is QueueEdit.Remove -> if (songKeys || edit.entry !is EntryKey.Song) list.remove(edit.entry)
                is QueueEdit.Move -> if ((songKeys || edit.entry !is EntryKey.Song) && list.remove(edit.entry)) {
                    val after = edit.after.takeUnless { !songKeys && it is EntryKey.Song }
                    val at = if (after == null && edit.after != null) edit.row.coerceIn(0, list.size) else placeAfter(list, after, edit.row)
                    list.add(at, edit.entry)
                }
            }
        }
        return list
    }

    /** The index right after [after], 0 for none, or [row] when [after] is gone. */
    private fun placeAfter(list: List<EntryKey>, after: EntryKey?, row: Int): Int {
        if (after == null) return 0
        val index = list.indexOf(after)
        return if (index >= 0) index + 1 else row.coerceIn(0, list.size)
    }
}
