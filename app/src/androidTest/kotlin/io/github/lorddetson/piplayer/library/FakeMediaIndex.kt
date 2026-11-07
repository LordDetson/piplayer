package io.github.lorddetson.piplayer.library

import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.sync.MediaIndex
import io.github.lorddetson.piplayer.library.sync.MediaKinds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A media index a test fills itself. Every volume has a version and rows; its generation is the highest generation of
 * change its rows have reached under that version (a deletion does not lower it, as in the real index), unless a test
 * sets it. A test can hold reads at a gate, make them fail, or change the index while a read runs.
 */
class FakeMediaIndex : MediaIndex {

    class Volume(var version: String?, var rows: List<IndexRow> = emptyList(), var generation: Long? = null) {
        internal var highest = 0L
        internal var highestUnder: String? = null
    }

    val volumes = LinkedHashMap<String, Volume>()

    /** Reads of rows wait here while it is closed. */
    @Volatile
    var gate: CountDownLatch? = null

    /** Thrown by reads of rows of [failingVolume]. */
    @Volatile
    var failure: Exception? = null

    @Volatile
    var failingVolume: String? = null

    /** Runs inside a read of rows, after the rows have been taken: a change that lands while the sync reads. */
    @Volatile
    var duringRead: (() -> Unit)? = null

    /** How many times rows were read: one per volume and sync. */
    val rowReads: Int get() = rowReadCount.get()

    /** How many of those reads were of all rows of a volume, as a full sync reads them. */
    val fullReads: Int get() = fullReadCount.get()

    private val rowReadCount = AtomicInteger()
    private val fullReadCount = AtomicInteger()

    @Synchronized
    fun volume(name: String = PRIMARY, version: String? = "1"): Volume = volumes.getOrPut(name) { Volume(version) }

    @Synchronized
    override fun volumes(): Set<String> = volumes.keys.toSet()

    @Synchronized
    override fun version(volume: String): String? = volumes[volume]?.version

    @Synchronized
    override fun generation(volume: String): Long {
        val v = volumes.getValue(volume)
        if (v.highestUnder != v.version) {
            v.highestUnder = v.version
            v.highest = 0
        }
        v.highest = maxOf(v.highest, v.rows.maxOfOrNull { it.generationModified } ?: 0)
        return v.generation ?: v.highest
    }

    override fun rows(volume: String, since: Long?): List<IndexRow> {
        rowReadCount.incrementAndGet()
        if (since == null) fullReadCount.incrementAndGet()
        gate?.await(30, TimeUnit.SECONDS)
        if (volume == failingVolume) failure?.let { throw it }
        val rows = synchronized(this) {
            volumes.getValue(volume).rows.filter { since == null || it.generationModified > since }
        }
        duringRead?.let { change ->
            duringRead = null
            change()
        }
        return rows
    }

    @Synchronized
    override fun ids(volume: String): Set<Long> = volumes.getValue(volume).rows.map { it.mediaStoreId }.toSet()

    companion object {
        const val PRIMARY = "external_primary"

        fun row(
            id: Long,
            title: String? = "Song $id",
            volume: String = PRIMARY,
            path: String = "Music/",
            name: String = "$id.mp3",
            generation: Long = id,
            kinds: MediaKinds = MediaKinds(),
            artist: String? = "Artist $id",
            album: String? = "Album $id",
            durationMs: Long? = 60_000,
            albumArtist: String? = null,
            year: Int? = 2_000,
            trackNumber: Int? = 1,
        ) = IndexRow(
            mediaStoreId = id, volume = volume, relativePath = path, displayName = name, size = 1_000 + id,
            modifiedSeconds = 1_700_000_000 + id, generationModified = generation, mimeType = "audio/mpeg", kinds = kinds,
            title = title, artist = artist, album = album, albumArtist = albumArtist, composer = null, genre = null,
            year = year, trackNumber = trackNumber, durationMs = durationMs,
        )
    }
}
