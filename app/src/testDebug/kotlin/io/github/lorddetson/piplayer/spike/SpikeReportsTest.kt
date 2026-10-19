package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdCandidate
import io.github.lorddetson.piplayer.identification.fingerprint.FingerprintResult
import io.github.lorddetson.piplayer.identification.fingerprint.PcmEncoding
import io.github.lorddetson.piplayer.identification.fingerprint.PcmFormat
import io.github.lorddetson.piplayer.identification.fingerprint.SourceInfo
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.TextScore
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.QueryVariant
import io.github.lorddetson.piplayer.identification.text.RecordingQuery
import io.github.lorddetson.piplayer.identification.text.ScoredCandidate
import io.github.lorddetson.piplayer.identification.text.TextSearchResult
import io.github.lorddetson.piplayer.identification.text.VariantAttempt
import io.github.lorddetson.piplayer.nativelib.chromaprint.Fingerprint
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpikeReportsTest {

    private val acoustIdCandidates = List(7) { index ->
        AcoustIdCandidate(
            acoustIdTrackId = "track-$index",
            acoustIdScore = 0.97 - index * 0.1,
            recordingId = "rec-$index",
            title = "Paper Lanterns",
            artistCredit = "The Quiet Harbors",
            lengthMs = 214_400,
            releaseGroups = listOf("Harbor Lights"),
            confidence = 0.97 - index * 0.1,
        )
    }

    private val query = RecordingQuery(QueryVariant.entries.first(), "recording:(+paper)", 25)

    private val scored = ScoredCandidate(
        TextCandidate("mb-1", "Paper Lanterns", "The Quiet Harbors", listOf("The Quiet Harbors"), 214_000),
        TextScore(title = 1.0, artist = 1.0, durationCredit = 1.0, albumTrack = 0.0, total = 0.85),
    )

    private val report = SpikeReport(
        fileName = "01 - Paper Lanterns.mp3",
        sizeBytes = 8_600_000,
        tags = StageOutcome.Done(
            TagReadResult.Success(mapOf("TITLE" to listOf("Paper Lanterns")), null, emptyList()),
            12.milliseconds,
        ),
        hint = SpikeHint(TrackHint("Paper Lanterns", "The Quiet Harbors", 214_000), HintSource.TAGS),
        durationMs = 214_000,
        durationSource = DurationSource.TAGLIB,
        fingerprint = StageOutcome.Done(
            FingerprintResult(
                Fingerprint(IntArray(948), "AQAA"),
                fedSamples = 1_323_000,
                inputFormat = PcmFormat(44_100, 2, PcmEncoding.PCM_16BIT),
                source = SourceInfo("audio/mpeg", "c2.android.mp3.decoder", 214_000_000),
                reachedEnd = false,
            ),
            1_800.milliseconds,
        ),
        acoustId = StageOutcome.Done(acoustIdCandidates, 600.milliseconds),
        textSearch = StageOutcome.Done(
            TextSearchResult(
                attempts = listOf(VariantAttempt(query, listOf(scored))),
                candidates = listOf(scored),
            ),
            1_200.milliseconds,
        ),
    )

    @Test
    fun `the record keeps the stages, their best candidates and the timings`() {
        val record = SpikeReports.record(report)

        assertThat(record.file).isEqualTo("01 - Paper Lanterns.mp3")
        assertThat(record.durationSource).isEqualTo("TAGLIB")
        assertThat(record.tags).isEqualTo("done")
        assertThat(record.hintSource).isEqualTo("TAGS")
        assertThat(record.hintTitle).isEqualTo("Paper Lanterns")
        assertThat(record.fingerprint).isEqualTo("done")
        assertThat(record.fingerprintItems).isEqualTo(948)
        assertThat(record.fingerprintSeconds).isEqualTo(120.0)
        assertThat(record.inputFormat).isEqualTo("44100 Hz, 2 ch, PCM_16BIT")
        assertThat(record.decoder).isEqualTo("c2.android.mp3.decoder (audio/mpeg)")
        assertThat(record.acoustId).isEqualTo("done")
        assertThat(record.acoustIdCandidates).hasSize(SpikeReports.TOP)
        assertThat(record.acoustIdCandidates.first())
            .isEqualTo(RecordCandidate("rec-0", "Paper Lanterns", "The Quiet Harbors", 214_400, 0.97, 0.97))
        assertThat(record.textQueries).isEqualTo(1)
        assertThat(record.textCandidates.single())
            .isEqualTo(RecordCandidate("mb-1", "Paper Lanterns", "The Quiet Harbors", 214_000, 0.85))
        assertThat(record.elapsedMs).containsExactlyInAnyOrderEntriesOf(
            mapOf("tags" to 12L, "fingerprint" to 1_800L, "acoustId" to 600L, "textSearch" to 1_200L),
        )
    }

    @Test
    fun `skipped and failed stages are spelled out`() {
        val record = SpikeReports.record(
            report.copy(
                acoustId = StageOutcome.Skipped(SkipReason.NO_API_KEY),
                textSearch = StageOutcome.Failed("IOException: timeout", 30_000.milliseconds),
            ),
        )
        assertThat(record.acoustId).isEqualTo("skipped: NO_API_KEY")
        assertThat(record.acoustIdCandidates).isEmpty()
        assertThat(record.textSearch).isEqualTo("failed: IOException: timeout")
        assertThat(record.elapsedMs).containsEntry("textSearch", 30_000L).doesNotContainKey("acoustId")
    }

    @Test
    fun `a text search that stopped after a failed query is marked as partial`() {
        val partial = TextSearchResult(
            attempts = listOf(VariantAttempt(query, emptyList())),
            candidates = emptyList(),
            failure = MusicBrainzException.Unavailable(4, null),
        )
        val record = SpikeReports.record(report.copy(textSearch = StageOutcome.Done(partial, 9_000.milliseconds)))

        assertThat(record.textSearch).isEqualTo("partial: Unavailable: MusicBrainz unavailable after 4 attempts")
        assertThat(SpikeReports.text(report.copy(textSearch = StageOutcome.Done(partial, 9_000.milliseconds))))
            .contains("partial: Unavailable")
    }

    @Test
    fun `the record is one line of JSON`() {
        val line = Json.encodeToString(SpikeRecord.serializer(), SpikeReports.record(report))
        assertThat(line).doesNotContain("\n")
        assertThat(Json.decodeFromString(SpikeRecord.serializer(), line)).isEqualTo(SpikeReports.record(report))
    }

    @Test
    fun `the text names the file, the hint and the best candidates of each stage`() {
        val text = SpikeReports.text(report)

        assertThat(text).contains("01 - Paper Lanterns.mp3")
        assertThat(text.lines()).contains("Hint (TAGS): The Quiet Harbors - Paper Lanterns")
        assertThat(text).contains("948 items").contains("120.0 s")
        assertThat(text).contains("0.97").contains("rec-0")
        assertThat(text).contains("0.85").contains("mb-1")
        assertThat(text).doesNotContain("rec-5")
    }
}
