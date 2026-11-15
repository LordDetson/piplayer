package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.identification.release.Credit
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.library.values.DecidedField.Companion.list

/** The values of the parts of TI §10.2 that a decision or a file gives, as text (`PartKind`). */
object DecisionContent {

    /**
     * Every part an identification with [details] gives, empty ones included (TI §7.1: a field is decided as a whole);
     * [savedCoverId] is the cover when one was saved for it.
     */
    fun ofRelease(details: ReleaseDetails, savedCoverId: Long? = null): Map<DecidedField, String?> = mapOf(
        DecidedField.TITLE to details.title.ifEmpty { null },
        DecidedField.ARTIST to details.trackArtist.ifEmpty { null },
        DecidedField.ARTIST_CREDITS to credits(details.trackCredits),
        DecidedField.ARTIST_SORT to details.trackArtistSort.ifEmpty { null },
        DecidedField.ALBUM to details.releaseTitle.ifEmpty { null },
        DecidedField.ALBUM_ARTIST to details.releaseArtist.ifEmpty { null },
        DecidedField.ALBUM_ARTIST_CREDITS to credits(details.releaseCredits),
        DecidedField.ALBUM_ARTIST_SORT to details.releaseArtistSort.ifEmpty { null },
        DecidedField.DATE to details.date,
        DecidedField.ORIGINAL_DATE to details.originalDate,
        DecidedField.TRACK_NUMBER to details.at.position.toString(),
        DecidedField.TRACK_TOTAL to details.trackTotal?.toString(),
        DecidedField.DISC_NUMBER to details.at.disc.toString(),
        DecidedField.DISC_TOTAL to details.discTotal.takeIf { it > 0 }?.toString(),
        DecidedField.DISC_SUBTITLE to details.discSubtitle,
        DecidedField.COMPILATION to if (details.compilation) "1" else "0",
        DecidedField.LABEL to list(details.labels),
        DecidedField.CATALOG_NUMBER to list(details.catalogNumbers),
        DecidedField.BARCODE to details.barcode,
        DecidedField.ISRC to list(details.isrcs),
        DecidedField.RECORDING_MBID to details.recordingId,
        DecidedField.RELEASE_MBID to details.releaseId,
        DecidedField.RELEASE_GROUP_MBID to details.releaseGroupId,
        DecidedField.RELEASE_TRACK_MBID to details.trackId,
        DecidedField.ARTIST_IDS to list(details.trackCredits.mapNotNull { it.artistId }),
        DecidedField.ALBUM_ARTIST_IDS to list(details.releaseCredits.mapNotNull { it.artistId }),
        DecidedField.COVER to savedCoverId?.toString(),
    )

    /** The parts of a file's [tags], as the references of TI §7.1 compare them; the cover is not one of them. */
    fun ofTags(tags: TrackTags?): Map<DecidedField, String?> = mapOf(
        DecidedField.TITLE to tags?.title,
        DecidedField.ARTIST to tags?.artist,
        DecidedField.ARTIST_CREDITS to tags?.artists?.let(::tagCredits),
        DecidedField.ARTIST_SORT to tags?.artistSort,
        DecidedField.ALBUM to tags?.album,
        DecidedField.ALBUM_ARTIST to tags?.albumArtist,
        DecidedField.ALBUM_ARTIST_CREDITS to tags?.albumArtists?.let(::tagCredits),
        DecidedField.ALBUM_ARTIST_SORT to tags?.albumArtistSort,
        DecidedField.DATE to tags?.date,
        DecidedField.ORIGINAL_DATE to tags?.originalDate,
        DecidedField.TRACK_NUMBER to tags?.trackNumber?.toString(),
        DecidedField.TRACK_TOTAL to tags?.trackTotal?.toString(),
        DecidedField.DISC_NUMBER to tags?.discNumber?.toString(),
        DecidedField.DISC_TOTAL to tags?.discTotal?.toString(),
        DecidedField.DISC_SUBTITLE to tags?.discSubtitle,
        DecidedField.COMPILATION to if (tags?.compilation == true) "1" else null,
        DecidedField.LABEL to tags?.label,
        DecidedField.CATALOG_NUMBER to tags?.catalogNumber,
        DecidedField.BARCODE to tags?.barcode,
        DecidedField.ISRC to tags?.isrc,
        DecidedField.RECORDING_MBID to tags?.recordingMbid,
        DecidedField.RELEASE_MBID to tags?.releaseMbid,
        DecidedField.RELEASE_GROUP_MBID to tags?.releaseGroupMbid,
        DecidedField.RELEASE_TRACK_MBID to tags?.releaseTrackMbid,
        DecidedField.ARTIST_IDS to tags?.artistMbids?.let(::list),
        DecidedField.ALBUM_ARTIST_IDS to tags?.albumArtistMbids?.let(::list),
    )

    /** A credit value of [credits] (TI §10.2). */
    fun credits(credits: List<Credit>): String? = DecidedField.credits(credits.map { it.credited to it.joinPhrase })

    /** The names of a tag's artist list as a credit, joined as the tags write a list (TI §7.5). */
    fun tagCredits(names: List<String>): String? =
        DecidedField.credits(names.mapIndexed { i, name -> name to if (i < names.lastIndex) "; " else "" })
}
