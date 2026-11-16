package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.musicbrainz.CoverArtArchiveDto
import io.github.lorddetson.piplayer.identification.musicbrainz.MediumDto
import io.github.lorddetson.piplayer.identification.musicbrainz.NameCreditDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.musicbrainz.VARIOUS_ARTISTS_MBID
import io.github.lorddetson.piplayer.identification.musicbrainz.allTracks
import kotlin.math.abs

/**
 * One credited artist (TI §5.1).
 *
 * @property credited the name as credited on this release or track.
 * @property name the artist's own name.
 * @property sortName the artist's sort name; the credited name when MusicBrainz has none.
 * @property joinPhrase the text printed after the name, such as " feat. ".
 */
data class Credit(
    val credited: String,
    val name: String,
    val artistId: String?,
    val sortName: String,
    val joinPhrase: String,
)

/** The credit as printed: each credited name followed by its join phrase. */
fun List<Credit>.printed(): String = joinToString("") { it.credited + it.joinPhrase }

/** The sort credit: each sort name followed by its join phrase. */
fun List<Credit>.sorted(): String = joinToString("") { it.sortName + it.joinPhrase }

/**
 * The album data of one track on one looked-up release (TI §5.1).
 *
 * @property date the release date as written; [originalDate] the release group's first release date.
 * @property discSubtitle the medium title; null when it is blank.
 * @property labels every distinct label name in the release's order.
 * @property catalogNumbers every distinct catalog number, without MusicBrainz's `[none]`.
 * @property barcode null when unknown or when the release has none.
 * @property isrcs the ISRCs of the track's recording.
 * @property compilation true when the release is credited to Various Artists.
 * @property lengthMs the release track's length, otherwise the recording's.
 * @property lengthDiffers true when [lengthMs] and the file's exact duration are known and more than 7 s apart.
 */
data class ReleaseDetails(
    val releaseId: String,
    val releaseTitle: String,
    val releaseGroupId: String?,
    val status: String?,
    val country: String?,
    val date: String?,
    val originalDate: String?,
    val originalYear: Int?,
    val releaseCredits: List<Credit>,
    val trackId: String,
    val recordingId: String,
    val title: String,
    val trackCredits: List<Credit>,
    val at: TrackPosition,
    val trackTotal: Int?,
    val discTotal: Int,
    val discSubtitle: String?,
    val labels: List<String>,
    val catalogNumbers: List<String>,
    val barcode: String?,
    val isrcs: List<String>,
    val compilation: Boolean,
    val lengthMs: Long?,
    val lengthDiffers: Boolean,
    val coverArt: CoverArtArchiveDto?,
) {
    val releaseArtist: String get() = releaseCredits.printed()
    val releaseArtistSort: String get() = releaseCredits.sorted()
    val trackArtist: String get() = trackCredits.printed()
    val trackArtistSort: String get() = trackCredits.sorted()
}

/** Takes the fields of TI §5.1 from a release lookup. */
object ReleaseDetailsExtractor {

    /** A track length further than this from the file's duration is shown in the review (TI §4.2). */
    const val LENGTH_TOLERANCE_MS = 7_000L

    /**
     * The details of [recordingId]'s track on [release] (chosen as [ReleaseTracks.choose] does, the track at [at] first),
     * or null when the release does not contain the recording.
     */
    fun extract(
        release: ReleaseDto,
        recordingId: String,
        trackNumberTag: Int?,
        fileDurationMs: Long?,
        at: TrackPosition? = null,
    ): ReleaseDetails? {
        val chosen = ReleaseTracks.choose(release, recordingId, trackNumberTag, at) ?: return null
        val track = chosen.track
        val length = track.length ?: track.recording?.length
        val releaseCredits = credits(release.artistCredit)
        val labelInfo = release.labelInfo
        return ReleaseDetails(
            releaseId = release.id,
            releaseTitle = release.title,
            releaseGroupId = release.releaseGroup?.id,
            status = release.status,
            country = release.country,
            date = release.date?.takeIf { it.isNotBlank() },
            originalDate = release.releaseGroup?.firstReleaseDate?.takeIf { it.isNotBlank() },
            originalYear = year(release.releaseGroup?.firstReleaseDate),
            releaseCredits = releaseCredits,
            trackId = track.id,
            recordingId = recordingId,
            title = track.title ?: track.recording?.title.orEmpty(),
            // A track without its own credit carries the release's.
            trackCredits = credits(track.artistCredit.ifEmpty { track.recording?.artistCredit.orEmpty() })
                .ifEmpty { releaseCredits },
            at = chosen.at,
            trackTotal = chosen.medium.trackCount ?: chosen.medium.allTracksCount(),
            discTotal = release.media.size,
            discSubtitle = chosen.medium.title?.takeIf { it.isNotBlank() },
            labels = labelInfo.mapNotNull { it.label?.name?.takeIf(String::isNotBlank) }.distinct(),
            catalogNumbers = labelInfo.mapNotNull { info ->
                info.catalogNumber?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals(NO_CATALOG_NUMBER, ignoreCase = true) }
            }.distinct(),
            barcode = release.barcode?.trim()?.takeIf { it.isNotEmpty() },
            isrcs = track.recording?.isrcs.orEmpty(),
            compilation = release.artistCredit.any { it.artist?.id == VARIOUS_ARTISTS_MBID },
            lengthMs = length,
            lengthDiffers = length != null && fileDurationMs != null &&
                abs(length - fileDurationMs) > LENGTH_TOLERANCE_MS,
            coverArt = release.coverArtArchive,
        )
    }

    /** The credits of a credit list as MusicBrainz sends it. */
    fun credits(credit: List<NameCreditDto>): List<Credit> = credit.map {
        Credit(
            credited = it.name,
            name = it.artist?.name ?: it.name,
            artistId = it.artist?.id,
            sortName = it.artist?.sortName?.takeIf(String::isNotBlank) ?: it.name,
            joinPhrase = it.joinphrase,
        )
    }

    /** The year of a MusicBrainz date ("1975", "1975-11-21"), or null when it has none. */
    fun year(date: String?): Int? = date?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()

    private const val NO_CATALOG_NUMBER = "[none]"

    private fun MediumDto.allTracksCount(): Int? = allTracks.size.takeIf { it > 0 }
}
