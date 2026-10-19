package io.github.lorddetson.piplayer.identification.musicbrainz

import io.github.lorddetson.piplayer.identification.score.CandidateRelease
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON settings for MusicBrainz answers: unknown fields are skipped, so new server fields never break parsing. */
val MusicBrainzJson: Json = Json {
    ignoreUnknownKeys = true
    // Some fields arrive as an explicit null; those with a default take the default.
    coerceInputValues = true
}

/** The JSON of `GET /ws/2/recording?query=...&fmt=json`. Fields the server may leave out have defaults. */
@Serializable
data class RecordingSearchResponse(
    val created: String? = null,
    val count: Int = 0,
    val offset: Int = 0,
    val recordings: List<RecordingDto> = emptyList(),
)

@Serializable
data class RecordingDto(
    val id: String,
    /** Relative to the best hit of the same query (always 100), so it is only a rank, never a confidence. */
    val score: Int? = null,
    val title: String,
    val length: Long? = null,
    /** True for music videos; the server sends null instead of false. */
    val video: Boolean? = null,
    val disambiguation: String? = null,
    @SerialName("artist-credit") val artistCredit: List<NameCreditDto> = emptyList(),
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    val releases: List<ReleaseDto> = emptyList(),
    val isrcs: List<String> = emptyList(),
)

@Serializable
data class NameCreditDto(
    val name: String,
    val joinphrase: String = "",
    val artist: ArtistDto? = null,
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String,
    @SerialName("sort-name") val sortName: String? = null,
)

@Serializable
data class ReleaseDto(
    val id: String,
    val title: String,
    val status: String? = null,
    val date: String? = null,
    val country: String? = null,
    @SerialName("track-count") val trackCount: Int? = null,
    @SerialName("release-group") val releaseGroup: ReleaseGroupDto? = null,
    val media: List<MediumDto> = emptyList(),
)

@Serializable
data class ReleaseGroupDto(
    val id: String,
    val title: String? = null,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
)

@Serializable
data class MediumDto(
    val position: Int? = null,
    val format: String? = null,
    @SerialName("track-count") val trackCount: Int? = null,
    /** Zero-based index of the matched track on this medium. */
    @SerialName("track-offset") val trackOffset: Int? = null,
    val track: List<TrackDto> = emptyList(),
)

@Serializable
data class TrackDto(
    val id: String,
    /** Printed track number, which can be text such as "A4". */
    val number: String? = null,
    val title: String? = null,
    val length: Long? = null,
)

/** The credit as printed: each name followed by its join phrase. */
fun RecordingDto.creditString(): String = artistCredit.joinToString("") { it.name + it.joinphrase }

/**
 * The recording as a scoring candidate. The server lists a release once for every track that links the recording, so a
 * release can appear with several track numbers; exact repeats are dropped. The track number is the printed number,
 * or the position when the number is not numeric.
 */
fun RecordingDto.toCandidate(): TextCandidate = TextCandidate(
    recordingId = id,
    title = title,
    artistCredit = creditString(),
    artistNames = artistCredit.map { it.name },
    lengthMs = length,
    releases = releases.map { release ->
        val medium = release.media.firstOrNull()
        val number = medium?.track?.firstOrNull()?.number?.toIntOrNull() ?: medium?.trackOffset?.plus(1)
        release.id to CandidateRelease(release.title, number)
    }.distinct().map { it.second },
)
