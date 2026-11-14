package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.library.details.DetailField.ADDED
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM_ARTIST
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM_ARTIST_ID
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM_GAIN
import io.github.lorddetson.piplayer.library.details.DetailField.ARTIST
import io.github.lorddetson.piplayer.library.details.DetailField.ARTIST_ID
import io.github.lorddetson.piplayer.library.details.DetailField.BARCODE
import io.github.lorddetson.piplayer.library.details.DetailField.BITRATE
import io.github.lorddetson.piplayer.library.details.DetailField.BIT_DEPTH
import io.github.lorddetson.piplayer.library.details.DetailField.CATALOG_NUMBER
import io.github.lorddetson.piplayer.library.details.DetailField.CHANNELS
import io.github.lorddetson.piplayer.library.details.DetailField.CODEC
import io.github.lorddetson.piplayer.library.details.DetailField.COMPILATION
import io.github.lorddetson.piplayer.library.details.DetailField.COMPOSER
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SOURCE
import io.github.lorddetson.piplayer.library.details.DetailField.DATE
import io.github.lorddetson.piplayer.library.details.DetailField.DISC
import io.github.lorddetson.piplayer.library.details.DetailField.DISC_SUBTITLE
import io.github.lorddetson.piplayer.library.details.DetailField.FAVORITE
import io.github.lorddetson.piplayer.library.details.DetailField.FILE_NAME
import io.github.lorddetson.piplayer.library.details.DetailField.FOLDER
import io.github.lorddetson.piplayer.library.details.DetailField.FORMAT
import io.github.lorddetson.piplayer.library.details.DetailField.GENRE
import io.github.lorddetson.piplayer.library.details.DetailField.INDEX_LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.ISRC
import io.github.lorddetson.piplayer.library.details.DetailField.LABEL
import io.github.lorddetson.piplayer.library.details.DetailField.LAST_PLAYED
import io.github.lorddetson.piplayer.library.details.DetailField.LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.MIME_TYPE
import io.github.lorddetson.piplayer.library.details.DetailField.MODIFIED
import io.github.lorddetson.piplayer.library.details.DetailField.ORIGINAL_DATE
import io.github.lorddetson.piplayer.library.details.DetailField.PLAY_COUNT
import io.github.lorddetson.piplayer.library.details.DetailField.RECORDING_ID
import io.github.lorddetson.piplayer.library.details.DetailField.RELEASE_GROUP_ID
import io.github.lorddetson.piplayer.library.details.DetailField.RELEASE_ID
import io.github.lorddetson.piplayer.library.details.DetailField.SAMPLE_RATE
import io.github.lorddetson.piplayer.library.details.DetailField.SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.TAGS
import io.github.lorddetson.piplayer.library.details.DetailField.TAG_ENCODING
import io.github.lorddetson.piplayer.library.details.DetailField.TITLE
import io.github.lorddetson.piplayer.library.details.DetailField.TRACK
import io.github.lorddetson.piplayer.library.details.DetailField.TRACK_GAIN
import io.github.lorddetson.piplayer.library.details.DetailField.TRACK_ID
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.FieldOverrides
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.library.values.TrackTags

/**
 * What is known of a file, for its details (docs/library.md §18): from `audio_file`, or for a file opened from another
 * app from the file itself. [volume] is null for an opened file, which has no place in the library; [read] says that the
 * tags were read, and [tagState] why not when they were not.
 */
data class FileFacts(
    val displayName: String,
    val volume: String? = null,
    val relativePath: String? = null,
    val size: Long? = null,
    val mtimeSeconds: Long? = null,
    val mimeType: String? = null,
    val read: Boolean = false,
    val tagState: TagState? = null,
    val format: String? = null,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val bitrateKbps: Int? = null,
    val tagEncoding: String? = null,
    val exactDurationMs: Long? = null,
    /** `audio_file.duration_source` (`DurationSource`). */
    val durationSource: String? = null,
    val tagLengthMs: Long? = null,
)

/** What the user database knows of the song's identity (docs/library.md §18.2). */
data class UserFacts(val favorite: Boolean, val playCount: Long, val lastPlayedAt: Long?, val addedAt: Long)

/** The cover shown and its size when known (docs/library.md §18.3). */
data class CoverDetail(val source: CoverSource, val width: Int? = null, val height: Int? = null, val bytes: Long? = null)

/** Everything details are made of; [fileId] is null for a file opened from another app. */
data class DetailInputs(
    val fileId: Long?,
    val file: FileFacts,
    val index: IndexValues? = null,
    val tags: TrackTags? = null,
    val user: UserFacts? = null,
    val cover: CoverDetail? = null,
    val overrides: FieldOverrides = FieldOverrides.NONE,
)

/** Makes the rows of track details from the merged values (docs/library.md §18.2, TI §7.1). */
object TrackDetailsBuilder {

    fun of(inputs: DetailInputs): TrackDetails {
        val file = inputs.file
        val read = file.read
        // Level 3 counts once the file is read, level 4 only before (TI §7.1).
        val tags = inputs.tags.takeIf { read }
        val index = inputs.index.takeIf { !read }
        // The resolver of the lists itself, so that the fields it resolves are exactly what the lists show.
        val merged = MergedValues.of(
            displayName = file.displayName,
            index = inputs.index,
            tags = inputs.tags,
            read = read,
            exactDurationMs = file.exactDurationMs,
            tagLengthMs = file.tagLengthMs,
            overrides = inputs.overrides,
            volume = file.volume.orEmpty(),
            relativePath = file.relativePath.orEmpty(),
        )
        val rows = ArrayList<DetailRow>()
        fun add(field: DetailField, value: DetailValue?) {
            if (value != null) rows += DetailRow(field, value)
        }
        fun text(field: DetailField, value: String?) = add(field, value.present()?.let(DetailValue::Text))
        fun texts(field: DetailField, values: List<String>) = add(field, values.mapNotNull { it.present() }.ifEmpty { null }?.let(DetailValue::Texts))

        text(TITLE, merged.title)
        text(ARTIST, merged.artist)
        text(ALBUM, merged.album)
        text(ALBUM_ARTIST, merged.albumArtist)
        add(TRACK, merged.trackNumber?.let { DetailValue.OfTotal(it, tags?.trackTotal) })
        add(DISC, merged.discNumber?.let { DetailValue.OfTotal(it, tags?.discTotal) })
        text(DISC_SUBTITLE, tags?.discSubtitle)
        text(DATE, if (read) tags?.date else index?.year?.takeIf { it > 0 }?.toString())
        text(ORIGINAL_DATE, tags?.originalDate)
        texts(GENRE, if (read) tags?.genres.orEmpty() else listOfNotNull(index?.genre))
        texts(COMPOSER, if (read) tags?.composers.orEmpty() else listOfNotNull(index?.composer))
        add(COMPILATION, if (tags?.compilation == true) DetailValue.Flag(true) else null)
        text(LABEL, tags?.label)
        text(CATALOG_NUMBER, tags?.catalogNumber)
        text(BARCODE, tags?.barcode)
        text(ISRC, tags?.isrc)
        add(TRACK_GAIN, tags?.trackGainDb?.let { DetailValue.Gain(it, tags.trackPeak) })
        add(ALBUM_GAIN, tags?.albumGainDb?.let { DetailValue.Gain(it, tags.albumPeak) })

        inputs.user?.let { user ->
            add(FAVORITE, DetailValue.Flag(user.favorite))
            add(PLAY_COUNT, DetailValue.Count(user.playCount))
            add(LAST_PLAYED, user.lastPlayedAt?.let(DetailValue::Time))
            add(ADDED, DetailValue.Time(user.addedAt))
        }

        text(FORMAT, file.format)
        text(CODEC, file.codec)
        add(BITRATE, file.bitrateKbps?.takeIf { it > 0 }?.let(DetailValue::Kbps))
        add(SAMPLE_RATE, file.sampleRate?.takeIf { it > 0 }?.let(DetailValue::Hertz))
        add(CHANNELS, file.channels?.takeIf { it > 0 }?.let(DetailValue::Channels))
        add(BIT_DEPTH, file.bitDepth?.takeIf { it > 0 }?.let(DetailValue::Bits))
        val length = lengthOf(file, inputs.index)
        add(LENGTH, length)
        val indexLength = inputs.index?.durationMs?.takeIf { it > 0 }
        if (indexLength != null && length?.source != LengthSource.INDEX) add(INDEX_LENGTH, DetailValue.Length(indexLength, LengthSource.INDEX))
        add(SIZE, file.size?.takeIf { it >= 0 }?.let(DetailValue::Bytes))
        text(FILE_NAME, file.displayName)
        file.volume?.let { add(FOLDER, DetailValue.Folder(it, file.relativePath.orEmpty())) }
        text(MIME_TYPE, file.mimeType)
        text(TAG_ENCODING, file.tagEncoding)
        add(MODIFIED, file.mtimeSeconds?.takeIf { it > 0 }?.let { DetailValue.Time(it * 1_000) })
        if (!read) add(TAGS, DetailValue.Tags(file.tagState ?: TagState.NOT_READ))

        inputs.cover?.let { cover ->
            add(COVER_SOURCE, DetailValue.Cover(cover.source))
            if (cover.width != null && cover.height != null && cover.width > 0 && cover.height > 0) {
                add(COVER_SIZE, DetailValue.Pixels(cover.width, cover.height, cover.bytes?.takeIf { it > 0 }))
            }
        }

        // The IDs of the merged values: the media index has none, so they come from the read tags (TI §7.1).
        fun ids(field: DetailField, kind: MusicBrainzKind, values: List<String?>) =
            values.mapNotNull { it.present() }.distinct().forEach { add(field, DetailValue.MusicBrainz(kind, it)) }
        ids(RECORDING_ID, MusicBrainzKind.RECORDING, listOf(tags?.recordingMbid))
        ids(RELEASE_ID, MusicBrainzKind.RELEASE, listOf(tags?.releaseMbid))
        ids(RELEASE_GROUP_ID, MusicBrainzKind.RELEASE_GROUP, listOf(tags?.releaseGroupMbid))
        ids(TRACK_ID, MusicBrainzKind.TRACK, listOf(tags?.releaseTrackMbid))
        ids(ARTIST_ID, MusicBrainzKind.ARTIST, tags?.artistMbids.orEmpty())
        ids(ALBUM_ARTIST_ID, MusicBrainzKind.ARTIST, tags?.albumArtistMbids.orEmpty())

        return TrackDetails(
            fileId = inputs.fileId,
            title = merged.title,
            artist = merged.artist,
            album = merged.album,
            rows = rows.sortedBy { it.field.ordinal },
        )
    }

    /**
     * The length the lists show, in the resolver's order (the stored exact length, the index's, TagLib's), with where it
     * comes from (docs/library.md §18.2).
     */
    private fun lengthOf(file: FileFacts, index: IndexValues?): DetailValue.Length? {
        file.exactDurationMs?.takeIf { it > 0 }?.let { ms ->
            val source = when (file.durationSource) {
                DurationSource.WALK -> LengthSource.WALK
                DurationSource.PENDING_FLAC_END -> LengthSource.HEADER_UNCHECKED
                else -> LengthSource.HEADER
            }
            return DetailValue.Length(ms, source)
        }
        index?.durationMs?.takeIf { it > 0 }?.let { return DetailValue.Length(it, LengthSource.INDEX) }
        return file.tagLengthMs?.takeIf { it > 0 }?.let { DetailValue.Length(it, LengthSource.ESTIMATE) }
    }

    /** Media index placeholders count as empty (TI §7.1). */
    private fun String?.present(): String? = this?.trim()?.takeUnless { it.isEmpty() || it == UNKNOWN }

    private const val UNKNOWN = "<unknown>"
}
