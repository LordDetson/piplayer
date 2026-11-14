package io.github.lorddetson.piplayer.library.tagpass

import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.db.FileState
import io.github.lorddetson.piplayer.library.db.LibraryDao
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.SongRow
import io.github.lorddetson.piplayer.library.db.TrackArtistEntity
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.db.TrackGenreEntity
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.values.AlbumKey
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.library.values.TextForms
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.tagging.TagReadGuard
import io.github.lorddetson.piplayer.tagging.TagReport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** A file to read. */
data class PassFile(
    val fileId: Long,
    val mediaStoreId: Long,
    val volume: String,
    val displayName: String,
    val size: Long,
    val mtime: Long,
)

/** What a read gave. */
sealed interface PassRead {
    /** `:tagger` answered, with the tags or with why TagLib could not read the file. */
    data class Replied(val report: TagReport) : PassRead

    data object OpenFailed : PassRead

    /** `:tagger` crashed or hung on the file. */
    data object Crashed : PassRead

    /** The file has failed too often to be read at all. */
    data object Unsafe : PassRead

    /** `:tagger` could not be reached: nothing was read, and it says nothing about the file. */
    data object Unavailable : PassRead
}

fun interface PassReader {
    suspend fun read(file: PassFile): PassRead
}

data class PassProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

data class PassResult(val read: Int, val failed: Int)

/**
 * Reads the tags of the library in the background and stores what it finds in batches (docs/library.md §5). Rows change in
 * place: the revision of the library does not move, so a list keeps its order and gets the new values through [onBatch].
 * [gate] is asked before each file and may hold the run; nothing is cancelled for it, because cancelling a call kills
 * `:tagger`.
 */
class TagPass(
    private val database: LibraryDatabase,
    private val sync: LibrarySync,
    private val reader: PassReader,
    private val cyrillicLanguage: () -> Boolean,
    /** The manual choice of the legacy encoding, null for Automatic; read on the pass's thread (docs/library.md §5.7). */
    private val legacyEncoding: suspend () -> LegacyEncoding? = { null },
    /** What the stored files were last decoded with, so that a change left unfinished is applied by a later run. */
    private val decodedEncoding: DecodedEncoding = DecodedEncoding.InMemory(),
    private val readerVersion: Int = READER_VERSION,
    private val batchSize: Int = BATCH_SIZE,
    private val crashBackoff: Duration = CRASH_BACKOFF,
    private val pause: Duration = Duration.ZERO,
    private val gate: suspend () -> Unit = {},
    private val onBatch: (List<SongRow>) -> Unit = {},
) {
    private val dao: LibraryDao = database.library()
    private val displays = TrackDisplayWriter(database)

    /** Files that failed in this process in a way a later process may get past; only a change of the file brings them back here. */
    private val failedHere = ConcurrentHashMap.newKeySet<Triple<Long, Long, Long>>()

    /** A change of the legacy encoding asked for the stored files to be decoded again (docs/library.md §5.7). */
    private val redecodeAsked = AtomicBoolean()

    /** The manual legacy encoding the reads decode with: taken when a run starts and whenever the files are decoded again. */
    @Volatile
    private var choice: LegacyEncoding? = null

    private val mutableProgress = MutableStateFlow(PassProgress())
    val progress: StateFlow<PassProgress> = mutableProgress

    /** One run over what is still to read, from a list taken now; a file that comes later waits for the next run. */
    suspend fun run(): PassResult {
        choice = legacyEncoding()
        // Also when no change asked: one may have come too late for a run before the process ended.
        if (choice?.name != decodedEncoding.read()) redecodeAsked.set(true)
        redecodeIfAsked()
        val files = sync.exclusive { dao.unreadFiles(readerVersion, TagReadGuard.UNSAFE_COUNT) }.filter { it.key() !in failedHere }
        mutableProgress.value = PassProgress(running = true, done = 0, total = files.size)
        var read = 0
        var failed = 0
        try {
            val pending = ArrayList<Pending>(batchSize)
            var pendingBytes = 0L
            var oldest = TimeSource.Monotonic.markNow()
            suspend fun flush() {
                if (pending.isEmpty()) return
                apply(pending)
                pending.clear()
                pendingBytes = 0
            }
            for (file in files) {
                if (redecodeAsked.get()) {
                    // What this run stored so far was decoded with the old choice.
                    gate()
                    flush()
                    redecodeIfAsked()
                }
                var answer: PassRead
                while (true) {
                    gate()
                    answer = reader.read(file)
                    if (answer !is PassRead.Unavailable) break
                    flush()
                    delay(crashBackoff)
                }
                val item = Pending(file, answer, if (answer is PassRead.Replied) parse(answer.report) else null)
                if (pending.isEmpty()) oldest = TimeSource.Monotonic.markNow()
                pending += item
                pendingBytes += item.parsed?.rawTags?.length ?: 0
                if (item.parsed != null) read++ else failed++
                if (answer !is PassRead.Replied || answer.report.error == TagReadError.IO_ERROR) failedHere += file.key()
                mutableProgress.update { it.copy(done = it.done + 1) }
                if (pending.size >= batchSize || pendingBytes >= MAX_BATCH_BYTES || oldest.elapsedNow() >= MAX_BATCH_AGE) flush()
                if (answer is PassRead.Crashed) delay(crashBackoff)
                if (pause.isPositive()) delay(pause)
            }
            flush()
            // A change during the last read.
            redecodeIfAsked()
            // Also when there was nothing to read: a sync may have removed the last tracks of an album.
            removeOrphans()
        } finally {
            mutableProgress.update { it.copy(running = false) }
        }
        return PassResult(read, failed)
    }

    /** The legacy encoding changed: the next run, or the one under way before its next file, decodes the stored files again. */
    fun askRedecode() = redecodeAsked.set(true)

    /**
     * Decodes the stored files with legacy text again from their raw tags, reading no file, when a change of the setting
     * asked for it: only those whose code page changes, in batches, while no sync runs (docs/library.md §5.7).
     */
    private suspend fun redecodeIfAsked() {
        if (!redecodeAsked.getAndSet(false)) return
        choice = legacyEncoding()
        val manual = choice
        val cyrillic = cyrillicLanguage()
        val ids = sync.exclusive { dao.legacyFileIds() }
        for (chunk in ids.chunked(batchSize)) {
            val rows = sync.exclusive {
                val changed = ArrayList<Long>()
                database.withWriteTransaction {
                    for (row in dao.legacyTracksOf(chunk)) {
                        val raw = row.rawTagsJson?.let { Json.decodeFromString<Map<String, List<String>>>(it) } ?: continue
                        val decoded = LegacyText.decode(raw, cyrillic, manual)
                        if (decoded.encoding == row.tagEncoding) continue
                        storeTrack(row.fileId, TrackTags.from(decoded.properties), row.rawTagsJson, row.trackKey, row.volume, row.relativePath)
                        dao.setTagEncoding(row.fileId, decoded.encoding)
                        changed += row.fileId
                    }
                    if (changed.isNotEmpty()) {
                        val inputs = displays.inputsOf(changed)
                        with(TrackDisplayWriter) { write(inputs.map { it.fileId }, TextForms().let { forms -> inputs.map { it.resolve(forms) } }) }
                    }
                }
                changed.chunked(CHUNK).flatMap { dao.songRowsOf(it) }
            }
            if (rows.isNotEmpty()) onBatch(rows)
        }
        decodedEncoding.write(manual?.name)
    }

    /** The files that failed in this process get another try in the next run. */
    fun forgetFailures() = failedHere.clear()

    private class Pending(val file: PassFile, val answer: PassRead, val parsed: ParsedRead?)

    private class ParsedRead(val report: TagReport, val tags: TrackTags, val encoding: String?, val rawTags: String)

    /** Null for a reply that holds no tags. */
    private fun parse(report: TagReport): ParsedRead? {
        if (report.error != null) return null
        val decoded = LegacyText.decode(report.properties, cyrillicLanguage(), choice)
        // As read, marks included, so that a change of the legacy encoding can decode them again without a read.
        val raw = report.properties.mapValues { (_, values) -> values.filter { it.length <= MAX_RAW_VALUE_CHARS } }
            .filterValues { it.isNotEmpty() }
        return ParsedRead(report, TrackTags.from(decoded.properties), decoded.encoding, Json.encodeToString(raw))
    }

    /** One transaction per batch, while no sync runs. A file the sync changed since it was read keeps nothing of the read. */
    private suspend fun apply(items: List<Pending>) {
        val rows = sync.exclusive {
            val states = dao.fileStates(items.map { it.file.fileId }).associateBy { it.id }
            val current = items.filter { item ->
                states[item.file.fileId]?.let { it.size == item.file.size && it.mtime == item.file.mtime } == true
            }
            val read = current.filter { it.parsed != null }.map { it.file.fileId }
            database.withWriteTransaction {
                for (item in current) {
                    if (item.parsed != null) {
                        store(item.file.fileId, item.parsed, states.getValue(item.file.fileId))
                    } else {
                        fail(item, states)
                    }
                }
                val inputs = displays.inputsOf(read)
                with(TrackDisplayWriter) { write(inputs.map { it.fileId }, TextForms().let { forms -> inputs.map { it.resolve(forms) } }) }
            }
            read.chunked(CHUNK).flatMap { dao.songRowsOf(it) }
        }
        if (rows.isNotEmpty()) onBatch(rows)
    }

    private suspend fun fail(item: Pending, states: Map<Long, FileState>) {
        val fileId = item.file.fileId
        when (val answer = item.answer) {
            is PassRead.Replied -> {
                // A file TagLib does not know still has a key, and maybe a length to walk.
                storeContent(fileId, answer.report, states.getValue(fileId))
                when (answer.report.error) {
                    // The device's fault, such as a full disk: not the file's.
                    TagReadError.IO_ERROR -> dao.markFailed(fileId, OPEN_ERROR, readerVersion = null)
                    // TagLib's answer holds until the file or the reader changes.
                    TagReadError.UNSUPPORTED_FORMAT -> dao.markFailed(fileId, UNSUPPORTED, readerVersion)
                    else -> dao.markFailed(fileId, READ_ERROR, readerVersion)
                }
            }
            PassRead.OpenFailed -> dao.markFailed(fileId, OPEN_ERROR, readerVersion = null)
            PassRead.Crashed -> dao.markFailed(fileId, CRASH, readerVersion = null)
            PassRead.Unsafe -> dao.markFailed(fileId, UNSAFE, readerVersion = null)
            // Not stored: the run tries the file again itself.
            PassRead.Unavailable -> Unit
        }
    }

    private suspend fun store(fileId: Long, parsed: ParsedRead, state: FileState) {
        val report = parsed.report
        val trackKey = report.content?.let { "${it.key}:$TRACK_START_MS" }
        storeTrack(fileId, parsed.tags, parsed.rawTags, trackKey, state.volume, state.relativePath)
        val cover = report.pictures.firstOrNull { it.pictureType == FRONT_COVER } ?: report.pictures.firstOrNull()
        dao.markRead(
            fileId,
            at = System.currentTimeMillis(),
            readerVersion = readerVersion,
            format = report.container,
            codec = report.codec,
            sampleRate = report.audio?.sampleRate?.takeIf { it > 0 },
            bitDepth = report.bitDepth,
            channels = report.audio?.channels?.takeIf { it > 0 },
            bitrate = report.audio?.bitrateKbps?.takeIf { it > 0 },
            tagEncoding = parsed.encoding,
            coverPresent = cover != null,
            coverBytes = cover?.sizeBytes?.toLong(),
            tagLengthMs = report.audio?.lengthMs?.takeIf { it > 0 }?.toLong(),
        )
        storeContent(fileId, report, state)
    }

    /** The track of a read file, with its album, artists and genres, built anew from [tags]. */
    private suspend fun storeTrack(
        fileId: Long,
        tags: TrackTags,
        rawTags: String?,
        trackKey: String?,
        volume: String,
        relativePath: String,
    ) {
        val ids = listOf(fileId)
        dao.deleteTrackArtists(ids)
        dao.deleteTrackGenres(ids)
        dao.deleteTracks(ids)
        val albumId = AlbumKey.of(tags, volume, relativePath)?.let { key ->
            dao.addAlbum(key, tags.album.orEmpty(), tags.albumArtist, tags.releaseMbid, tags.compilation)
            // The newest spelling: a file tagged again keeps its key, but not its old name.
            dao.updateAlbum(key, tags.album.orEmpty(), tags.albumArtist, tags.compilation)
            dao.albumId(key)
        }
        val trackId = dao.addTrack(TrackEntity.of(fileId, tags, rawTags, albumId).copy(trackKey = trackKey))
        val credits = artists(trackId, ROLE_ARTIST, tags.artists, tags.artistMbids, tags.artistSort) +
            artists(trackId, ROLE_ALBUM_ARTIST, tags.albumArtists, tags.albumArtistMbids, tags.albumArtistSort) +
            artists(trackId, ROLE_COMPOSER, tags.composers, emptyList(), sort = null)
        if (credits.isNotEmpty()) dao.addTrackArtists(credits)
        val genres = tags.genres.map { name ->
            val key = keyOf(name)
            dao.addGenre(key, name)
            TrackGenreEntity(trackId, dao.genreId(key))
        }.distinct()
        if (genres.isNotEmpty()) dao.addTrackGenres(genres)
    }

    private suspend fun storeContent(fileId: Long, report: TagReport, state: FileState) {
        val content = report.content ?: return
        val previous = state.durationSource?.let { StoredDuration(state.durationMs, it) }
        val stored = StoredDuration.afterRead(content.duration, previous)
        dao.setContent(fileId, content.key, stored.ms, stored.source)
    }

    /** The IDs of MusicBrainz go with the names only when each name has one; [sort] only with a single name. */
    private suspend fun artists(
        trackId: Long,
        role: Int,
        names: List<String>,
        mbids: List<String>,
        sort: String?,
    ): List<TrackArtistEntity> = names.mapIndexed { position, name ->
        val mbid = mbids.getOrNull(position).takeIf { mbids.size == names.size }
        val key = mbid?.let { "mbid:$it" } ?: keyOf(name)
        dao.addArtist(key, name, sort.takeIf { names.size == 1 }, mbid)
        dao.updateArtist(key, name, sort.takeIf { names.size == 1 })
        TrackArtistEntity(trackId, dao.artistId(key), role, position)
    }

    private fun keyOf(name: String) = TextNormalizer.normalize(name).ifEmpty { name.lowercase() }

    private suspend fun removeOrphans() = sync.exclusive {
        database.withWriteTransaction {
            dao.deleteAlbumsWithoutTracks()
            dao.deleteArtistsWithoutTracks()
            dao.deleteGenresWithoutTracks()
        }
    }

    private fun PassFile.key() = Triple(fileId, size, mtime)

    companion object {
        /** A change that makes old reads worth redoing raises this. */
        const val READER_VERSION = 2

        /** The part of a track key after the content key: where the track starts, 0 until CUE sheets arrive (TI §10). */
        private const val TRACK_START_MS = 0

        const val BATCH_SIZE = 200
        private const val MAX_BATCH_BYTES = 1024 * 1024
        private val MAX_BATCH_AGE = 1.seconds

        /** Android gives up on a process that crashes twice within a minute (docs/library.md §4.3). */
        val CRASH_BACKOFF = 60.seconds

        /** Longer values, such as lyrics, stay out of the database for now. */
        private const val MAX_RAW_VALUE_CHARS = 16 * 1024
        private const val CHUNK = 500

        private const val UNSUPPORTED = "unsupported"
        private const val READ_ERROR = "read"
        private const val OPEN_ERROR = "open"
        private const val CRASH = "crash"
        private const val UNSAFE = "unsafe"
        private const val FRONT_COVER = "Front Cover"

        const val ROLE_ARTIST = 0
        const val ROLE_ALBUM_ARTIST = 1
        const val ROLE_COMPOSER = 2
    }
}
