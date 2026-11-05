package io.github.lorddetson.piplayer.library.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.lorddetson.piplayer.library.values.TrackTags

/**
 * What the tag pass read of a track, precedence level 3 (TI §10.1). The lists of values are held as one text, joined by
 * [SEPARATOR], which no tag holds as text. `start_ms` tells the tracks of one file apart once CUE sheets arrive.
 */
@Entity(tableName = "track", indices = [Index("file_id", "start_ms", unique = true), Index("album_id")])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "track_key") val trackKey: String? = null,
    @ColumnInfo(name = "start_ms", defaultValue = "0") val startMs: Long = 0,
    @ColumnInfo(name = "end_ms") val endMs: Long? = null,
    @ColumnInfo(name = "album_id") val albumId: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val artists: String? = null,
    val album: String? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    @ColumnInfo(name = "album_artists") val albumArtists: String? = null,
    @ColumnInfo(name = "title_sort") val titleSort: String? = null,
    @ColumnInfo(name = "artist_sort") val artistSort: String? = null,
    @ColumnInfo(name = "album_sort") val albumSort: String? = null,
    @ColumnInfo(name = "album_artist_sort") val albumArtistSort: String? = null,
    val composers: String? = null,
    val genres: String? = null,
    val date: String? = null,
    @ColumnInfo(name = "original_date") val originalDate: String? = null,
    @ColumnInfo(name = "track_number") val trackNumber: Int? = null,
    @ColumnInfo(name = "track_total") val trackTotal: Int? = null,
    @ColumnInfo(name = "disc_number") val discNumber: Int? = null,
    @ColumnInfo(name = "disc_total") val discTotal: Int? = null,
    @ColumnInfo(name = "disc_subtitle") val discSubtitle: String? = null,
    val compilation: Boolean = false,
    val label: String? = null,
    @ColumnInfo(name = "catalog_number") val catalogNumber: String? = null,
    val barcode: String? = null,
    val isrc: String? = null,
    @ColumnInfo(name = "recording_mbid") val recordingMbid: String? = null,
    @ColumnInfo(name = "release_mbid") val releaseMbid: String? = null,
    @ColumnInfo(name = "release_group_mbid") val releaseGroupMbid: String? = null,
    @ColumnInfo(name = "release_track_mbid") val releaseTrackMbid: String? = null,
    @ColumnInfo(name = "artist_mbids") val artistMbids: String? = null,
    @ColumnInfo(name = "album_artist_mbids") val albumArtistMbids: String? = null,
    @ColumnInfo(name = "track_gain_db") val trackGainDb: Double? = null,
    @ColumnInfo(name = "track_peak") val trackPeak: Double? = null,
    @ColumnInfo(name = "album_gain_db") val albumGainDb: Double? = null,
    @ColumnInfo(name = "album_peak") val albumPeak: Double? = null,
    @ColumnInfo(name = "raw_tags_json") val rawTagsJson: String? = null,
) {
    fun toTags() = TrackTags(
        title = title,
        artist = artist,
        artists = artists.values(),
        album = album,
        albumArtist = albumArtist,
        albumArtists = albumArtists.values(),
        titleSort = titleSort,
        artistSort = artistSort,
        albumSort = albumSort,
        albumArtistSort = albumArtistSort,
        composers = composers.values(),
        genres = genres.values(),
        date = date,
        originalDate = originalDate,
        trackNumber = trackNumber,
        trackTotal = trackTotal,
        discNumber = discNumber,
        discTotal = discTotal,
        discSubtitle = discSubtitle,
        compilation = compilation,
        label = label,
        catalogNumber = catalogNumber,
        barcode = barcode,
        isrc = isrc,
        recordingMbid = recordingMbid,
        releaseMbid = releaseMbid,
        releaseGroupMbid = releaseGroupMbid,
        releaseTrackMbid = releaseTrackMbid,
        artistMbids = artistMbids.values(),
        albumArtistMbids = albumArtistMbids.values(),
        trackGainDb = trackGainDb,
        trackPeak = trackPeak,
        albumGainDb = albumGainDb,
        albumPeak = albumPeak,
    )

    companion object {
        private const val SEPARATOR = "\u001F"

        fun of(fileId: Long, tags: TrackTags, rawTagsJson: String? = null, albumId: Long? = null) = TrackEntity(
            fileId = fileId,
            albumId = albumId,
            title = tags.title,
            artist = tags.artist,
            artists = tags.artists.joined(),
            album = tags.album,
            albumArtist = tags.albumArtist,
            albumArtists = tags.albumArtists.joined(),
            titleSort = tags.titleSort,
            artistSort = tags.artistSort,
            albumSort = tags.albumSort,
            albumArtistSort = tags.albumArtistSort,
            composers = tags.composers.joined(),
            genres = tags.genres.joined(),
            date = tags.date,
            originalDate = tags.originalDate,
            trackNumber = tags.trackNumber,
            trackTotal = tags.trackTotal,
            discNumber = tags.discNumber,
            discTotal = tags.discTotal,
            discSubtitle = tags.discSubtitle,
            compilation = tags.compilation,
            label = tags.label,
            catalogNumber = tags.catalogNumber,
            barcode = tags.barcode,
            isrc = tags.isrc,
            recordingMbid = tags.recordingMbid,
            releaseMbid = tags.releaseMbid,
            releaseGroupMbid = tags.releaseGroupMbid,
            releaseTrackMbid = tags.releaseTrackMbid,
            artistMbids = tags.artistMbids.joined(),
            albumArtistMbids = tags.albumArtistMbids.joined(),
            trackGainDb = tags.trackGainDb,
            trackPeak = tags.trackPeak,
            albumGainDb = tags.albumGainDb,
            albumPeak = tags.albumPeak,
            rawTagsJson = rawTagsJson,
        )

        private fun List<String>.joined(): String? = if (isEmpty()) null else joinToString(SEPARATOR)

        private fun String?.values(): List<String> = this?.split(SEPARATOR).orEmpty()
    }
}

/** An album: grouped by its release ID, otherwise by its title, album artist and folder (TI §10.1). */
@Entity(tableName = "album", indices = [Index("album_key", unique = true)])
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "album_key") val albumKey: String,
    val title: String,
    @ColumnInfo(name = "album_artist") val albumArtist: String?,
    @ColumnInfo(name = "release_mbid") val releaseMbid: String?,
    val compilation: Boolean,
)

/** An artist, one per normalized name or MusicBrainz ID. */
@Entity(tableName = "artist", indices = [Index("artist_key", unique = true)])
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "artist_key") val artistKey: String,
    val name: String,
    @ColumnInfo(name = "sort_name") val sortName: String?,
    val mbid: String?,
)

/** An artist of a track; [role] says as what, [position] gives the order within the role. */
@Entity(tableName = "track_artist", primaryKeys = ["track_id", "role", "position"], indices = [Index("artist_id")])
data class TrackArtistEntity(
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    val role: Int,
    val position: Int,
    @ColumnInfo(name = "join_phrase") val joinPhrase: String? = null,
)

@Entity(tableName = "genre", indices = [Index("genre_key", unique = true)])
data class GenreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "genre_key") val genreKey: String,
    val name: String,
)

@Entity(tableName = "track_genre", primaryKeys = ["track_id", "genre_id"], indices = [Index("genre_id")])
data class TrackGenreEntity(
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "genre_id") val genreId: Long,
)
