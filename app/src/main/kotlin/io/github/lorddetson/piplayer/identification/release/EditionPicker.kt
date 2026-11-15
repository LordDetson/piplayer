package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.text.Similarity

/**
 * One edition in the picker (TI §4.2 step 6).
 *
 * @property suggested the suggested edition, listed first.
 * @property knownToContain true when the edition is among the recording's releases (or is the suggested one), so
 *   choosing it needs no mapping; otherwise choosing it loads its tracklist first ([EditionPicker.map]).
 */
data class Edition(val release: ReleaseDto, val suggested: Boolean, val knownToContain: Boolean)

/** What choosing an edition means for the file (TI §4.2 step 6). */
sealed interface EditionMapping {

    /** The edition contains the identified recording, on [track]. */
    data class SameRecording(val track: ReleaseTrack) : EditionMapping

    /** The edition has the song as another recording, whose ID replaces the identified one. */
    data class OtherRecording(val track: ReleaseTrack) : EditionMapping {
        val recordingId: String? get() = track.recordingId
    }

    /** No track qualifies: "does not contain this song", and the edition cannot be chosen. */
    data object DoesNotContain : EditionMapping
}

/** The edition picker model of TI §4.2 step 6. */
object EditionPicker {

    /** A mapped track's title must be at least this similar to the identified track's title. */
    const val TITLE_MATCH = 0.9

    /**
     * Every release of the group: [suggestedId] first, then the others in the order of the release ranking, official
     * ones first. [recordingReleaseIds] are the identified recording's releases from the browse (TI §4.2 step 1).
     */
    fun editions(
        groupReleases: List<ReleaseDto>,
        suggestedId: String,
        recordingReleaseIds: Set<String>,
        context: ReleaseContext,
    ): List<Edition> {
        val (suggested, others) = groupReleases.distinctBy { it.id }.partition { it.id == suggestedId }
        val ranked = ReleaseRanking.rank(others, context).map { it.release }
            .sortedBy { !it.status.equals(OFFICIAL, ignoreCase = true) }
        return suggested.map { Edition(it, suggested = true, knownToContain = true) } +
            ranked.map { Edition(it, suggested = false, knownToContain = it.id in recordingReleaseIds) }
    }

    private const val OFFICIAL = "official"

    /**
     * Maps the file onto a looked-up [edition]: the identified recording's own track when the edition has it (chosen
     * as [ReleaseTracks.choose] does with [trackNumberTag]); otherwise the track at [position] (the identified track's
     * place on the suggested edition, or the track number tag on disc 1 when that is unknown) whose title is at least
     * [TITLE_MATCH] similar to [identifiedTitle]; otherwise [EditionMapping.DoesNotContain].
     */
    fun map(
        edition: ReleaseDto,
        identifiedRecordingId: String,
        identifiedTitle: String,
        position: TrackPosition?,
        trackNumberTag: Int?,
    ): EditionMapping {
        ReleaseTracks.choose(edition, identifiedRecordingId, trackNumberTag)
            ?.let { return EditionMapping.SameRecording(it) }
        val at = position ?: trackNumberTag?.let { TrackPosition(1, it) } ?: return EditionMapping.DoesNotContain
        val track = ReleaseTracks.of(edition).firstOrNull { it.at == at } ?: return EditionMapping.DoesNotContain
        val title = track.track.title ?: track.track.recording?.title ?: return EditionMapping.DoesNotContain
        return if (track.recordingId != null && Similarity.title(identifiedTitle, title) >= TITLE_MATCH) {
            EditionMapping.OtherRecording(track)
        } else {
            EditionMapping.DoesNotContain
        }
    }
}
