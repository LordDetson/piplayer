package io.github.lorddetson.piplayer.identification.musicbrainz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The JSON of a release browse: `GET /ws/2/release?recording=…` or `?release-group=…` (TI §4.2). */
@Serializable
data class ReleaseBrowseResponse(
    /** Every release the filter matches, not only this page's. */
    @SerialName("release-count") val releaseCount: Int = 0,
    @SerialName("release-offset") val releaseOffset: Int = 0,
    val releases: List<ReleaseDto> = emptyList(),
)

/** The JSON of `GET /ws/2/isrc/{isrc}`: the recordings that carry the ISRC. */
@Serializable
data class IsrcResponse(
    val isrc: String? = null,
    val recordings: List<RecordingDto> = emptyList(),
)

/** One label and catalog number of a release. */
@Serializable
data class LabelInfoDto(
    /** MusicBrainz writes `[none]` for a release known to have no catalog number. */
    @SerialName("catalog-number") val catalogNumber: String? = null,
    val label: LabelDto? = null,
)

@Serializable
data class LabelDto(
    val id: String? = null,
    val name: String,
)

/** A date and country the release came out in. */
@Serializable
data class ReleaseEventDto(
    val date: String? = null,
    val area: AreaDto? = null,
)

@Serializable
data class AreaDto(
    /** The country codes of the area; `XW` is worldwide, `XE` Europe. */
    @SerialName("iso-3166-1-codes") val isoCodes: List<String> = emptyList(),
)

/** What the Cover Art Archive holds for a release (TI §5.2). */
@Serializable
data class CoverArtArchiveDto(
    val count: Int = 0,
    val front: Boolean = false,
    val back: Boolean = false,
    val artwork: Boolean = false,
    /** True when the images were taken down on request; then none may be shown. */
    val darkened: Boolean = false,
)

/**
 * The releases of a paged browse (TI §4.2), each once, in the server's order.
 *
 * @property totalCount the release count the server reported.
 * @property pages the browse requests sent.
 * @property capped true when the page cap stopped the walk before [totalCount] was reached; the releases are then
 *   only part of the list.
 * @property nonOfficialFallback true when the recording had no official release, so these are of any status.
 */
data class ReleaseList(
    val releases: List<ReleaseDto>,
    val totalCount: Int,
    val pages: Int,
    val capped: Boolean,
    val nonOfficialFallback: Boolean = false,
)

/** What a pasted MusicBrainz link or ID (TI §3.3) names. */
sealed interface RefLookup {
    data class Recording(val recording: RecordingDto) : RefLookup

    data class Release(val release: ReleaseDto) : RefLookup

    /** MusicBrainz knows no recording or release with that ID. */
    data object NotFound : RefLookup
}

/** The special artist MusicBrainz credits compilations of several artists to (TI §5.1). */
const val VARIOUS_ARTISTS_MBID = "89ad4ac3-39f7-470e-963a-56509c546377"

/** Every track of the medium, from a lookup's full list or else a search's matched tracks. */
val MediumDto.allTracks: List<TrackDto> get() = tracks.ifEmpty { track }
