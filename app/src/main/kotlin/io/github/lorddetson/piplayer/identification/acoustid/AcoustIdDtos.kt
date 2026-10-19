package io.github.lorddetson.piplayer.identification.acoustid

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The parser for AcoustID answers: unknown fields are skipped, and nulls in place of defaults are tolerated. */
internal val AcoustIdJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * The answer of `/v2/lookup`. [status] is "ok" or "error"; an error answer carries [error] instead of [results].
 * With `compress` in `meta`, a field equal to its parent's value is left out (a release title equal to its release
 * group's, for example).
 */
@Serializable
data class AcoustIdResponse(
    val status: String,
    val results: List<AcoustIdResultDto> = emptyList(),
    val error: AcoustIdErrorDto? = null,
)

@Serializable
data class AcoustIdErrorDto(val code: Int, val message: String = "")

/** One AcoustID track; [recordings] is empty when no MusicBrainz recording is linked to it. */
@Serializable
data class AcoustIdResultDto(
    val id: String,
    val score: Double,
    val recordings: List<AcoustIdRecordingDto> = emptyList(),
)

/**
 * A MusicBrainz recording linked to the track. AcoustID returns only [id] when it has no metadata for it; [duration]
 * is in seconds and may have a fraction.
 */
@Serializable
data class AcoustIdRecordingDto(
    val id: String? = null,
    val title: String? = null,
    val duration: Double? = null,
    val artists: List<AcoustIdArtistDto> = emptyList(),
    @SerialName("releasegroups") val releaseGroups: List<AcoustIdReleaseGroupDto> = emptyList(),
    val isrcs: List<String> = emptyList(),
)

@Serializable
data class AcoustIdArtistDto(
    val id: String? = null,
    val name: String = "",
    @SerialName("joinphrase") val joinPhrase: String = "",
)

@Serializable
data class AcoustIdReleaseGroupDto(
    val id: String,
    val title: String? = null,
    val type: String? = null,
    @SerialName("secondarytypes") val secondaryTypes: List<String> = emptyList(),
    val releases: List<AcoustIdReleaseDto> = emptyList(),
)

@Serializable
data class AcoustIdReleaseDto(
    val id: String,
    val title: String? = null,
    val country: String? = null,
    val date: AcoustIdDateDto? = null,
    @SerialName("track_count") val trackCount: Int? = null,
)

@Serializable
data class AcoustIdDateDto(val year: Int? = null, val month: Int? = null, val day: Int? = null)
