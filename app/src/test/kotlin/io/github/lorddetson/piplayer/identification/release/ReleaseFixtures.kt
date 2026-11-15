package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.fakes.Fixtures
import io.github.lorddetson.piplayer.identification.musicbrainz.AreaDto
import io.github.lorddetson.piplayer.identification.musicbrainz.MediumDto
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzJson
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseBrowseResponse
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseEventDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseGroupDto

/** Recorded and made-up releases for the release-choice tests. */
object ReleaseFixtures {

    fun browse(name: String): ReleaseBrowseResponse = MusicBrainzJson.decodeFromString(Fixtures.text("musicbrainz/$name"))

    fun lookup(name: String): ReleaseDto = MusicBrainzJson.decodeFromString(Fixtures.text("musicbrainz/$name"))

    /** A made-up release; [id] is padded to an MBID-like string so the MBID tie-break follows it. */
    fun release(
        id: String,
        primary: String? = "Album",
        secondary: List<String> = emptyList(),
        format: String? = "CD",
        countries: List<String> = listOf("GB"),
        date: String? = "2000",
        title: String = "Title",
        trackCount: Int = 10,
        status: String = "Official",
    ) = ReleaseDto(
        id = id,
        title = title,
        status = status,
        date = date,
        country = countries.firstOrNull(),
        releaseGroup = ReleaseGroupDto(id = "group", primaryType = primary, secondaryTypes = secondary),
        media = listOf(MediumDto(position = 1, format = format, trackCount = trackCount)),
        releaseEvents = countries.map { ReleaseEventDto(date = date, area = AreaDto(listOf(it))) },
    )
}
