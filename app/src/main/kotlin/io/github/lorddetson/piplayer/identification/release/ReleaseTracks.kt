package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.musicbrainz.MediumDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.musicbrainz.TrackDto
import io.github.lorddetson.piplayer.identification.musicbrainz.allTracks

/** Where a track sits on a release: the disc and the track's position on it, never the printed number (TI §5.1). */
data class TrackPosition(val disc: Int, val position: Int)

/** One track of a looked-up release with its medium and its position. */
data class ReleaseTrack(val medium: MediumDto, val track: TrackDto, val at: TrackPosition) {
    val recordingId: String? get() = track.recording?.id
}

/** The tracks of a looked-up release (TI §4.2 step 4, §5.1). */
object ReleaseTracks {

    /**
     * Every track in disc and track order. The disc is the medium's position, the track's position its `position`;
     * either falls back to its place in the list when the answer leaves it out.
     */
    fun of(release: ReleaseDto): List<ReleaseTrack> = release.media.flatMapIndexed { mediumIndex, medium ->
        val disc = medium.position ?: (mediumIndex + 1)
        medium.allTracks.mapIndexed { trackIndex, track ->
            ReleaseTrack(medium, track, TrackPosition(disc, track.position ?: (trackIndex + 1)))
        }
    }.sortedWith(compareBy({ it.at.disc }, { it.at.position }))

    /**
     * The track of [recordingId] on [release]: of several, the one whose position equals [trackNumberTag], otherwise
     * the first. Null when the release does not contain the recording.
     */
    fun choose(release: ReleaseDto, recordingId: String, trackNumberTag: Int?): ReleaseTrack? {
        val tracks = of(release).filter { it.recordingId == recordingId }
        return tracks.firstOrNull { trackNumberTag != null && it.at.position == trackNumberTag } ?: tracks.firstOrNull()
    }
}
