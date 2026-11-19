package io.github.lorddetson.piplayer.library.tagpass

import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.db.FileState
import io.github.lorddetson.piplayer.library.db.LibraryDao
import io.github.lorddetson.piplayer.library.db.TrackArtistEntity
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.db.TrackGenreEntity
import io.github.lorddetson.piplayer.library.values.AlbumKey
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.userdata.TrackLegacyEncoding
import io.github.lorddetson.piplayer.userdata.decode
import kotlinx.serialization.json.Json

/** Persists tag reads for the library pass and for a verified file write. */
internal class TagPassStorage(private val dao: LibraryDao) {
    class ParsedRead(val report: TagReport, val tags: TrackTags, val encoding: String?, val rawTags: String)

    /** Null for a reply that holds no tags. */
    fun parse(
        report: TagReport,
        cyrillicLanguage: () -> Boolean,
        choice: LegacyEncoding?,
        preference: TrackLegacyEncoding? = null,
    ): ParsedRead? {
        if (report.error != null) return null
        val decoded = preference.decode(report.properties, cyrillicLanguage(), choice)
        // As read, marks included, so that a change of the legacy encoding can decode them again without a read.
        val raw = report.properties.mapValues { (_, values) -> values.filter { it.length <= MAX_RAW_VALUE_CHARS } }
            .filterValues { it.isNotEmpty() }
        return ParsedRead(report, TrackTags.from(decoded.properties), decoded.encoding, Json.encodeToString(raw))
    }

    fun withPreference(
        parsed: ParsedRead,
        cyrillicLanguage: () -> Boolean,
        global: LegacyEncoding?,
        preference: TrackLegacyEncoding,
    ): ParsedRead {
        val decoded = preference.decode(parsed.report.properties, cyrillicLanguage(), global)
        return ParsedRead(parsed.report, TrackTags.from(decoded.properties), decoded.encoding, parsed.rawTags)
    }

    suspend fun store(fileId: Long, parsed: ParsedRead, state: FileState, readerVersion: Int) {
        val report = parsed.report
        val trackKey = report.content?.let { "${it.key}:$TRACK_START_MS" }
        storeTrack(fileId, parsed.tags, parsed.rawTags, trackKey, state.volume, state.relativePath)
        val cover = report.pictures.firstOrNull { it.pictureType == FRONT_COVER } ?: report.pictures.firstOrNull()
        dao.markRead(
            fileId,
            at = maxOf(System.currentTimeMillis(), (state.tagPassAt ?: 0) + 1),
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
    suspend fun storeTrack(
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
        val credits = artists(trackId, TagPass.ROLE_ARTIST, tags.artists, tags.artistMbids, tags.artistSort) +
            artists(trackId, TagPass.ROLE_ALBUM_ARTIST, tags.albumArtists, tags.albumArtistMbids, tags.albumArtistSort) +
            artists(trackId, TagPass.ROLE_COMPOSER, tags.composers, emptyList(), sort = null)
        if (credits.isNotEmpty()) dao.addTrackArtists(credits)
        val genres = tags.genres.map { name ->
            val key = keyOf(name)
            dao.addGenre(key, name)
            TrackGenreEntity(trackId, dao.genreId(key))
        }.distinct()
        if (genres.isNotEmpty()) dao.addTrackGenres(genres)
    }

    suspend fun storeContent(fileId: Long, report: TagReport, state: FileState) {
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

    private companion object {
        const val FRONT_COVER = "Front Cover"
        const val MAX_RAW_VALUE_CHARS = 16 * 1024
        const val TRACK_START_MS = 0
    }
}
