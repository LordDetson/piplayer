package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.pipeline.FingerprintSource
import io.github.lorddetson.piplayer.identification.pipeline.LocalFile
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.pipeline.PipelineStage
import io.github.lorddetson.piplayer.identification.pipeline.SkipReason
import io.github.lorddetson.piplayer.identification.pipeline.StageDuration
import io.github.lorddetson.piplayer.identification.pipeline.StageReport
import io.github.lorddetson.piplayer.identification.pipeline.StageResult
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.release.ReleaseChoice
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.Tier
import io.github.lorddetson.piplayer.identification.score.TrackHint
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The spike's report of one pipeline run: the corpus record and the text for the clipboard. */
class SpikeReportsTest {

    private val file = LocalFile(
        target = IdentificationTarget.OpenedFile("content://test/1", "0".repeat(64), "01 - Paper Lanterns.mp3", 8_600_000),
        fileName = "01 - Paper Lanterns.mp3",
        tags = null,
        duration = StageDuration.Exact(214_000),
        format = "MPEG",
        folder = null,
        uri = "content://test/1",
    )

    private fun match(index: Int) = RecordingMatch(
        TextCandidate("rec-$index", "Paper Lanterns", "The Quiet Harbors", listOf("The Quiet Harbors"), 214_400),
        score = 0.97 - index * 0.1,
        reason = Reason(
            source = Source.FINGERPRINT,
            tier = Tier.IDENTIFIER,
            foundBy = setOf(Source.TEXT_SEARCH, Source.FINGERPRINT),
            fingerprintScore = 0.97 - index * 0.1,
            durationDeltaMs = 400,
            title = 1.0,
            artist = 1.0,
            disagreement = emptySet(),
        ),
    )

    private val found = PipelineResult(
        outcome = IdentificationOutcome.Found(
            Ranking(List(7, ::match), 0.1, Band.MEDIUM),
            ReleaseChoice.NONE,
            acoustIdTrackId = "track-0",
        ),
        file = file,
        hint = TrackHint("Paper Lanterns", "The Quiet Harbors", 214_000),
        durationMs = 214_000,
        lengthWalked = true,
        stages = listOf(
            StageReport(PipelineStage.LOCAL_READ, StageResult.Ran(0), 3.milliseconds),
            StageReport(PipelineStage.KNOWN_IDS, StageResult.Skipped(SkipReason.NO_VALUE), 0.milliseconds),
            StageReport(PipelineStage.FINGERPRINT, StageResult.Ran(7), 5_400.milliseconds),
            StageReport(
                PipelineStage.TEXT_SEARCH,
                StageResult.Failed(MusicBrainzException.Unavailable(4, null)),
                9_000.milliseconds,
            ),
        ),
        fingerprint = FingerprintSource.COMPUTED,
    )

    @Test
    fun `the record keeps the outcome, each stage's status and time and the best five recordings`() {
        val record = SpikeReports.record(found)

        assertThat(record.file).isEqualTo("01 - Paper Lanterns.mp3")
        assertThat(record.outcome).isEqualTo("Found")
        assertThat(record.band).isEqualTo("MEDIUM")
        assertThat(record.durationMs).isEqualTo(214_000)
        assertThat(record.lengthWalked).isTrue()
        assertThat(record.fingerprint).isEqualTo("COMPUTED")
        assertThat(record.stages).containsEntry("KNOWN_IDS", "skipped: NO_VALUE")
            .containsEntry("FINGERPRINT", "found 7")
            .containsEntry("TEXT_SEARCH", "failed: Unavailable: MusicBrainz unavailable after 4 attempts")
        assertThat(record.elapsedMs).containsEntry("FINGERPRINT", 5_400L)
        assertThat(record.candidates).hasSize(SpikeReports.TOP)
        assertThat(record.candidates.first().foundBy).containsExactly("FINGERPRINT", "TEXT_SEARCH")
        assertThat(record.candidates.first().acoustIdScore).isEqualTo(0.97)
        assertThat(record.release).isNull()
    }

    @Test
    fun `the record survives a round trip through JSON`() {
        val record = SpikeReports.record(found)

        val line = Json.encodeToString(SpikeRecord.serializer(), record)

        assertThat(Json.decodeFromString(SpikeRecord.serializer(), line)).isEqualTo(record)
    }

    @Test
    fun `a run that ended early names its outcome and provider`() {
        val record = SpikeReports.record(PipelineResult(IdentificationOutcome.Unavailable(ProviderId.ACOUSTID)))

        assertThat(record.outcome).isEqualTo("Unavailable(ACOUSTID)")
        assertThat(record.band).isNull()
        assertThat(record.candidates).isEmpty()
    }

    @Test
    fun `the text names the file, the stages, the outcome and the best recordings`() {
        val text = SpikeReports.text(found)

        assertThat(text).contains(
            "File: 01 - Paper Lanterns.mp3",
            "Duration: 3:34, walked",
            "Hint: The Quiet Harbors - Paper Lanterns",
            "FINGERPRINT: found 7, 5400 ms",
            "Outcome: Found (MEDIUM)",
            "0.97  The Quiet Harbors - Paper Lanterns  3:34  FINGERPRINT+TEXT_SEARCH  rec-0",
        )
        assertThat(text).doesNotContain("rec-5")
    }
}
