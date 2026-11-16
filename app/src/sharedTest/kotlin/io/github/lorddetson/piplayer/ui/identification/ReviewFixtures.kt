package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.Fixtures
import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.identification.caa.ImageFormat
import io.github.lorddetson.piplayer.identification.caa.ImageHeader
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzJson
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.LocalFile
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.pipeline.StageDuration
import io.github.lorddetson.piplayer.identification.release.EditionSource
import io.github.lorddetson.piplayer.identification.release.ReleaseChoice
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.release.ReleaseDetailsExtractor
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.Bands
import io.github.lorddetson.piplayer.identification.score.HintField
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.Tier
import io.github.lorddetson.piplayer.library.details.CoverSource as ShownSource
import io.github.lorddetson.piplayer.library.details.DetailField
import io.github.lorddetson.piplayer.library.details.DetailRow
import io.github.lorddetson.piplayer.library.details.DetailValue
import io.github.lorddetson.piplayer.library.details.MusicBrainzKind
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.library.values.TrackTags
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Pipeline results for the review (TI §6, review screen A), built from the shared MusicBrainz and Cover Art Archive
 * fixtures: "Paper Lanterns" of the made-up Harbor Lights world on its studio album, a live album's recording as a second
 * candidate, and the front image of 1 200 px.
 */
object ReviewFixtures {

    /** The library file under review. */
    const val FILE_ID = 7L
    const val CONTENT_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    const val OPENED_URI = "content://downloads/track_07.mp3"

    val libraryFile: FileRef = FileRef.Library(FILE_ID)
    val openedFile: FileRef = FileRef.Opened(OPENED_URI)

    val libraryTarget = IdentificationTarget.LibraryFile(FILE_ID, CONTENT_KEY)
    val openedTarget = IdentificationTarget.OpenedFile(OPENED_URI, CONTENT_KEY, "track_07.mp3", 4_200_000)

    /** The studio album as MusicBrainz answers its lookup. */
    val harborRelease: ReleaseDto = MusicBrainzJson.decodeFromString(FakeMusicBrainz.HARBOR_RELEASE_LOOKUP.text())

    /** "Paper Lanterns", track 3 of 11 on the studio album: the values Apply proposes. */
    val harborDetails: ReleaseDetails =
        ReleaseDetailsExtractor.extract(harborRelease, FakeMusicBrainz.HARBOR_RECORDING, 3, 214_000)!!

    val harborEdition = SuggestedEdition(harborRelease, harborDetails, EditionSource.RANKING)

    /** The recording of the live album, a second candidate. */
    const val LIVE_RECORDING = "33333333-3333-4333-8333-333333333333"

    /** A candidate of [recordingId] with [score], found by [foundBy] (the first scored). */
    fun match(
        recordingId: String = FakeMusicBrainz.HARBOR_RECORDING,
        score: Double = 0.96,
        title: String = "Paper Lanterns",
        artist: String = "The Quiet Harbors feat. Ada Vale",
        foundBy: List<Source> = listOf(Source.FINGERPRINT, Source.TEXT_SEARCH),
        fingerprintScore: Double? = 0.97,
        durationDeltaMs: Long? = 800,
        titleSimilarity: Double? = 0.92,
        artistSimilarity: Double? = null,
        disagreement: Set<HintField> = emptySet(),
    ) = RecordingMatch(
        TextCandidate(recordingId, title, artist, listOf(artist), 214_000),
        score,
        Reason(foundBy.first(), Tier.IDENTIFIER, foundBy.toSet(), fingerprintScore, durationDeltaMs, titleSimilarity, artistSimilarity, disagreement),
    )

    /** A ranking of [matches], best first, in the band [Bands] gives it. */
    fun ranking(vararg matches: RecordingMatch): Ranking {
        val best = matches.firstOrNull()?.score
        val second = matches.getOrNull(1)?.score
        return Ranking(matches.toList(), best?.let { Bands.gap(it, second) }, Bands.of(best, second))
    }

    /**
     * A run that found [matches] with the studio album as the winner's suggested edition ([edition], null for none). The
     * best AcoustID result is [acoustIdTrackId], which holds the winner when the fingerprint found it; [acoustIdTrackIds]
     * gives each recording the fingerprint found its own result.
     */
    fun found(
        vararg matches: RecordingMatch = arrayOf(match()),
        edition: SuggestedEdition? = harborEdition,
        acoustIdTrackId: String? = "acoustid-track",
        acoustIdTrackIds: Map<String, String> = listOfNotNull(
            matches.firstOrNull()?.takeIf { acoustIdTrackId != null && Source.FINGERPRINT in it.reason.foundBy }?.let { it.recordingId to acoustIdTrackId!! },
        ).toMap(),
    ): IdentificationOutcome.Found {
        val ranking = ranking(*matches)
        val choice = ReleaseChoice(ranking.best?.recordingId, null, emptyMap(), edition, emptyList())
        return IdentificationOutcome.Found(ranking, choice, acoustIdTrackId, acoustIdTrackIds)
    }

    /** A run with [outcome] for [target]'s file; [read] false for a run that ended before stage 0. */
    fun result(
        outcome: IdentificationOutcome,
        target: IdentificationTarget = libraryTarget,
        read: Boolean = true,
        retryAt: Instant? = null,
    ) = PipelineResult(
        outcome = outcome,
        file = if (read) {
            LocalFile(
                target = target,
                fileName = "track_07.mp3",
                tags = TrackTags(title = "track_07"),
                duration = StageDuration.Exact(213_200),
                format = "mp3",
                folder = "primary/Music",
                uri = if (target is IdentificationTarget.OpenedFile) target.uri else "content://media/external/audio/media/42",
            )
        } else {
            null
        },
        durationMs = if (read) 213_200 else null,
        retryLater = retryAt != null,
        retryAt = retryAt,
    )

    /** The front image of the studio album at 1 200 px, as Cover Art Archive sends it. */
    fun cover(entity: CoverEntity = CoverEntity.RELEASE): CoverImage {
        val bytes = Fixtures.bytes("caa/front-1200.jpg")
        val mbid = if (entity == CoverEntity.RELEASE) harborDetails.releaseId else harborDetails.releaseGroupId!!
        val url = "https://coverartarchive.org/${entity.path}/$mbid/front-1200".toHttpUrl()
        return CoverImage(bytes, ImageFormat.JPEG, ImageHeader.size(bytes), url, url, original = false, source = CoverSource(entity, mbid))
    }

    /**
     * What track details show of the file now: a title from its name, no artist, an album, track 7 without a total, an
     * embedded cover of [coverWidth] px (or the cover [coverSource]) and no MusicBrainz IDs.
     */
    fun currentDetails(
        title: String = "track_07",
        artist: String? = null,
        album: String? = "Harbor Lights",
        coverSource: ShownSource = ShownSource.EMBEDDED,
        coverWidth: Int? = 600,
        extra: List<DetailRow> = emptyList(),
    ): TrackDetails {
        val rows = buildList {
            add(DetailRow(DetailField.TITLE, DetailValue.Text(title)))
            artist?.let { add(DetailRow(DetailField.ARTIST, DetailValue.Text(it))) }
            album?.let { add(DetailRow(DetailField.ALBUM, DetailValue.Text(it))) }
            add(DetailRow(DetailField.TRACK, DetailValue.OfTotal(7, null)))
            add(DetailRow(DetailField.COVER_SOURCE, DetailValue.Cover(coverSource)))
            coverWidth?.let { add(DetailRow(DetailField.COVER_SIZE, DetailValue.Pixels(it, it, 48_000))) }
            addAll(extra)
        }.sortedBy { it.field.ordinal }
        return TrackDetails(FILE_ID, title, artist, album, rows)
    }

    /** A recording ID row of track details. */
    fun recordingIdRow(id: String) = DetailRow(DetailField.RECORDING_ID, DetailValue.MusicBrainz(MusicBrainzKind.RECORDING, id))
}
