package io.github.lorddetson.piplayer.identification.score

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdCandidate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** TI §4.1 and the §11.1 M3 "Recording scoring" row. */
class RecordingScorerTest {

    private val tolerance = within(1e-9)

    private val hint = TrackHint(title = "Paper Lanterns", artist = "The Quiet Harbors", durationMs = 214_000)

    private fun recording(
        id: String = "rec-1",
        title: String = "Paper Lanterns",
        credit: String = "The Quiet Harbors",
        lengthMs: Long? = 214_000,
    ) = TextCandidate(id, title, credit, listOf(credit), lengthMs)

    private fun fingerprint(
        id: String? = "rec-1",
        score: Double = 0.97,
        title: String? = "Paper Lanterns",
        credit: String? = "The Quiet Harbors",
        lengthMs: Long? = 214_000,
    ) = AcoustIdCandidate(
        acoustIdTrackId = "track-${id ?: "none"}",
        acoustIdScore = score,
        recordingId = id,
        title = title,
        artistCredit = credit,
        lengthMs = lengthMs,
        releaseGroups = listOf("Harbor Lights"),
        confidence = score,
        artistNames = listOfNotNull(credit),
    )

    private fun rank(vararg findings: StageFinding, file: TrackHint = hint) =
        RecordingScorer.rank(file, findings.toList())

    // Identifier tier: the recording ID from the file's tags.

    @ParameterizedTest(name = "recording length {0} ms -> 1.0")
    @CsvSource("214000", "221000", "207000")
    fun `a tag recording ID within 7 s scores 1,0 in the identifier tier`(lengthMs: Long) {
        val match = rank(StageFinding.TagRecordingId(recording(lengthMs = lengthMs))).matches.single()
        assertThat(match.score).isEqualTo(1.0)
        assertThat(match.reason.source).isEqualTo(Source.TAG_RECORDING_ID)
        assertThat(match.reason.tier).isEqualTo(Tier.IDENTIFIER)
    }

    @Test
    fun `a tag recording ID more than 7 s off is scored by the similarity tier`() {
        val candidate = recording(lengthMs = 221_001)
        val match = rank(StageFinding.TagRecordingId(candidate)).matches.single()
        assertThat(match.score).isCloseTo(TextCandidateScorer.score(hint, candidate).total, tolerance)
        assertThat(match.score).isLessThanOrEqualTo(TextCandidateScorer.TEXT_CAP)
        assertThat(match.reason.source).isEqualTo(Source.TAG_RECORDING_ID)
        assertThat(match.reason.tier).isEqualTo(Tier.SIMILARITY)
    }

    @Test
    fun `a tag recording ID without a length on either side is scored by the similarity tier`() {
        val unknownLength = rank(StageFinding.TagRecordingId(recording(lengthMs = null))).matches.single()
        assertThat(unknownLength.reason.tier).isEqualTo(Tier.SIMILARITY)
        val unknownFile = rank(StageFinding.TagRecordingId(recording()), file = hint.copy(durationMs = null))
        assertThat(unknownFile.matches.single().reason.tier).isEqualTo(Tier.SIMILARITY)
    }

    // Identifier tier: ISRC.

    @ParameterizedTest(name = "recording length {0} ms -> 0.95")
    @CsvSource("214000", "216000", "212000")
    fun `an ISRC with one recording within 2 s scores 0,95`(lengthMs: Long) {
        val match = rank(StageFinding.Isrc(listOf(recording(lengthMs = lengthMs)))).matches.single()
        assertThat(match.score).isCloseTo(0.95, tolerance)
        assertThat(match.reason.source).isEqualTo(Source.ISRC)
        assertThat(match.reason.tier).isEqualTo(Tier.IDENTIFIER)
    }

    @Test
    fun `an ISRC recording more than 2 s off is scored by the similarity tier`() {
        val candidate = recording(lengthMs = 216_500)
        val match = rank(StageFinding.Isrc(listOf(candidate))).matches.single()
        assertThat(match.reason.tier).isEqualTo(Tier.SIMILARITY)
        assertThat(match.score).isCloseTo(TextCandidateScorer.score(hint, candidate).total, tolerance)
    }

    @Test
    fun `an ISRC that maps to several recordings scores each by the similarity tier`() {
        val first = recording(id = "rec-1")
        val second = recording(id = "rec-2", credit = "Somebody Else")
        val matches = rank(StageFinding.Isrc(listOf(first, second))).matches
        assertThat(matches.map { it.recordingId }).containsExactly("rec-1", "rec-2")
        assertThat(matches).allSatisfy { assertThat(it.reason.tier).isEqualTo(Tier.SIMILARITY) }
        assertThat(matches[0].score).isCloseTo(TextCandidateScorer.TEXT_CAP, tolerance)
        assertThat(matches[1].score).isCloseTo(TextCandidateScorer.score(hint, second).total, tolerance)
    }

    @Test
    fun `the same recording listed twice by one ISRC is still one recording`() {
        val match = rank(StageFinding.Isrc(listOf(recording(), recording()))).matches.single()
        assertThat(match.score).isCloseTo(0.95, tolerance)
    }

    // Identifier tier: AcoustID.

    @Test
    fun `an AcoustID result scores its score times the duration credit`() {
        // 9 s off: credit 1 - 7 / 28 = 0.75.
        val match = rank(StageFinding.Fingerprint(listOf(fingerprint(lengthMs = 223_000)))).matches.single()
        assertThat(match.score).isCloseTo(0.97 * 0.75, tolerance)
        assertThat(match.reason.source).isEqualTo(Source.FINGERPRINT)
        assertThat(match.reason.tier).isEqualTo(Tier.IDENTIFIER)
        assertThat(match.reason.fingerprintScore).isEqualTo(0.97)
    }

    @Test
    fun `an AcoustID recording without a length keeps its score`() {
        val noLength = rank(StageFinding.Fingerprint(listOf(fingerprint(lengthMs = null)))).matches.single()
        assertThat(noLength.score).isCloseTo(0.97, tolerance)
        val noFileDuration = rank(StageFinding.Fingerprint(listOf(fingerprint())), file = hint.copy(durationMs = null))
        assertThat(noFileDuration.matches.single().score).isCloseTo(0.97, tolerance)
    }

    @Test
    fun `an AcoustID track without a recording is not a candidate`() {
        val ranking = rank(StageFinding.Fingerprint(listOf(fingerprint(id = null, title = null, credit = null))))
        assertThat(ranking.matches).isEmpty()
        assertThat(ranking.band).isEqualTo(Band.NO_MATCH)
    }

    // Similarity tier and penalties.

    @Test
    fun `a perfect text match stays capped at 0,85 and lands in the medium band`() {
        val ranking = rank(StageFinding.TextSearch(listOf(recording())))
        assertThat(ranking.matches.single().score).isCloseTo(TextCandidateScorer.TEXT_CAP, tolerance)
        assertThat(ranking.matches.single().reason.tier).isEqualTo(Tier.SIMILARITY)
        assertThat(ranking.matches.single().reason.source).isEqualTo(Source.TEXT_SEARCH)
        assertThat(ranking.band).isEqualTo(Band.MEDIUM)
    }

    @Test
    fun `the similarity tier uses the duration credit`() {
        // 16 s off: credit 0.5, so 0.4 + 0.3 + 0.1 = 0.8.
        val match = rank(StageFinding.TextSearch(listOf(recording(lengthMs = 230_000)))).matches.single()
        assertThat(match.score).isCloseTo(0.8, tolerance)
    }

    @Test
    fun `the duration penalty applies to the similarity tier`() {
        val match = rank(StageFinding.TextSearch(listOf(recording(lengthMs = 254_000)))).matches.single()
        assertThat(match.score).isCloseTo((0.4 + 0.3) * 0.3, tolerance)
    }

    @Test
    fun `the duration penalty applies to identifier tiers too`() {
        // A fingerprint hit 40 s off: no credit, then the penalty; it never reaches a band.
        val ranking = rank(StageFinding.Fingerprint(listOf(fingerprint(lengthMs = 254_000))))
        assertThat(ranking.matches.single().score).isCloseTo(0.0, tolerance)
        assertThat(ranking.band).isEqualTo(Band.NO_MATCH)
    }

    @Test
    fun `the title and artist penalty applies to the similarity tier`() {
        val cover = recording(credit = "Other Band")
        val match = rank(StageFinding.TextSearch(listOf(cover))).matches.single()
        assertThat(match.score).isCloseTo(TextCandidateScorer.score(hint, cover).total, tolerance)
        assertThat(match.score).isLessThan(0.4 + 0.3 * match.reason.artist!! + 0.2)
    }

    @Test
    fun `an identifier hit whose artist disagrees keeps its score and shows the disagreement`() {
        val fingerprintHit = rank(StageFinding.Fingerprint(listOf(fingerprint(credit = "Other Band")))).matches.single()
        assertThat(fingerprintHit.score).isCloseTo(0.97, tolerance)
        assertThat(fingerprintHit.reason.disagreement).containsExactly(HintField.ARTIST)

        val isrcHit = rank(StageFinding.Isrc(listOf(recording(credit = "Other Band")))).matches.single()
        assertThat(isrcHit.score).isCloseTo(0.95, tolerance)
        assertThat(isrcHit.reason.disagreement).containsExactly(HintField.ARTIST)

        val tagHit = rank(StageFinding.TagRecordingId(recording(title = "Completely Different"))).matches.single()
        assertThat(tagHit.score).isEqualTo(1.0)
        assertThat(tagHit.reason.disagreement).containsExactly(HintField.TITLE)
    }

    @Test
    fun `an identifier hit that agrees with the file shows no disagreement`() {
        val match = rank(StageFinding.Fingerprint(listOf(fingerprint()))).matches.single()
        assertThat(match.reason.disagreement).isEmpty()
    }

    @Test
    fun `a file without a title or artist has nothing to disagree with`() {
        val match = rank(
            StageFinding.Fingerprint(listOf(fingerprint(title = "X", credit = "Y"))),
            file = TrackHint(title = null, artist = "-", durationMs = 214_000),
        ).matches.single()
        assertThat(match.reason.title).isNull()
        assertThat(match.reason.artist).isNull()
        assertThat(match.reason.disagreement).isEmpty()
    }

    @Test
    fun `a recording AcoustID knows only by its ID has no title or artist to disagree with`() {
        val match = rank(StageFinding.Fingerprint(listOf(fingerprint(title = null, credit = null)))).matches.single()
        assertThat(match.score).isCloseTo(0.97, tolerance)
        assertThat(match.reason.title).isNull()
        assertThat(match.reason.artist).isNull()
        assertThat(match.reason.disagreement).isEmpty()
    }

    @Test
    fun `a version written without brackets is bracketed before scoring, as the text search does`() {
        val match = rank(
            StageFinding.TextSearch(listOf(recording(title = "Paper Lanterns (Radio Edit)"))),
            file = hint.copy(title = "Paper Lanterns Radio Edit"),
        ).matches.single()
        assertThat(match.reason.title).isEqualTo(1.0)
    }

    // Merging and the reason.

    @Test
    fun `a recording found by several stages takes its highest score and lists every stage`() {
        val ranking = rank(
            StageFinding.TextSearch(listOf(recording())),
            StageFinding.Fingerprint(listOf(fingerprint(score = 0.93))),
        )
        val match = ranking.matches.single()
        assertThat(match.score).isCloseTo(0.93, tolerance)
        assertThat(match.reason.source).isEqualTo(Source.FINGERPRINT)
        assertThat(match.reason.foundBy).containsExactlyInAnyOrder(Source.FINGERPRINT, Source.TEXT_SEARCH)
    }

    @Test
    fun `a merged recording keeps the fingerprint score in its reason when another stage wins`() {
        val match = rank(
            StageFinding.Fingerprint(listOf(fingerprint(score = 0.91))),
            StageFinding.TagRecordingId(recording()),
        ).matches.single()
        assertThat(match.score).isEqualTo(1.0)
        assertThat(match.reason.source).isEqualTo(Source.TAG_RECORDING_ID)
        assertThat(match.reason.fingerprintScore).isEqualTo(0.91)
        assertThat(match.reason.foundBy).containsExactlyInAnyOrder(Source.TAG_RECORDING_ID, Source.FINGERPRINT)
    }

    @Test
    fun `the reason carries the duration difference and the title and artist similarity`() {
        val match = rank(StageFinding.TextSearch(listOf(recording(title = "Paper Lantern", lengthMs = 215_000))))
            .matches.single()
        assertThat(match.reason.durationDeltaMs).isEqualTo(1_000)
        assertThat(match.reason.title).isCloseTo(13.0 / 14.0, tolerance)
        assertThat(match.reason.titlePercent).isEqualTo(93)
        assertThat(match.reason.artist).isEqualTo(1.0)
        assertThat(match.reason.fingerprintScore).isNull()
    }

    @Test
    fun `the duration difference is unknown without both lengths`() {
        val match = rank(StageFinding.TextSearch(listOf(recording(lengthMs = null)))).matches.single()
        assertThat(match.reason.durationDeltaMs).isNull()
    }

    @Test
    fun `matches are best first with the runner-up gap`() {
        val ranking = rank(
            StageFinding.TextSearch(listOf(recording(id = "text"))),
            StageFinding.Fingerprint(listOf(fingerprint(id = "fp", score = 0.98))),
        )
        assertThat(ranking.matches.map { it.recordingId }).containsExactly("fp", "text")
        assertThat(ranking.best?.recordingId).isEqualTo("fp")
        assertThat(ranking.runnerUpGap).isCloseTo(0.98 - 0.85, tolerance)
        assertThat(ranking.band).isEqualTo(Band.HIGH)
    }

    // Bands in a ranking.

    @Test
    fun `a score of at least 0,90 with a runner-up gap below 0,10 lands in the medium band`() {
        val ranking = rank(
            StageFinding.Fingerprint(listOf(fingerprint(id = "a", score = 0.95), fingerprint(id = "b", score = 0.90))),
        )
        assertThat(ranking.best?.score).isCloseTo(0.95, tolerance)
        assertThat(ranking.runnerUpGap).isCloseTo(0.05, tolerance)
        assertThat(ranking.band).isEqualTo(Band.MEDIUM)
    }

    @Test
    fun `a lone tag recording ID lands in the high band`() {
        val ranking = rank(StageFinding.TagRecordingId(recording()))
        assertThat(ranking.runnerUpGap).isEqualTo(1.0)
        assertThat(ranking.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `no findings give no match`() {
        val ranking = rank()
        assertThat(ranking.matches).isEmpty()
        assertThat(ranking.best).isNull()
        assertThat(ranking.runnerUpGap).isNull()
        assertThat(ranking.band).isEqualTo(Band.NO_MATCH)
    }
}
