package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.score.Bands
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.Tier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The pure request caps of TI §3 (request budget). */
class LookupPlanTest {

    companion object {
        fun candidate(id: String, title: String = "Song", credit: String = "Artist", length: Long? = 200_000) =
            TextCandidate(id, title, credit, listOf(credit).filter { it.isNotEmpty() }, length)

        fun match(candidate: TextCandidate, score: Double, foundBy: Set<Source> = setOf(Source.FINGERPRINT)) = RecordingMatch(
            candidate,
            score,
            Reason(foundBy.min(), Tier.IDENTIFIER, foundBy, score, null, null, null, emptySet()),
        )

        /** A ranking of recordings "r0", "r1", … with [scores], best first, in the band [Bands] gives it. */
        fun ranking(vararg scores: Double): Ranking {
            val matches = scores.mapIndexed { i, score -> match(candidate("r$i"), score) }
            val best = scores.firstOrNull()
            return Ranking(matches, best?.let { Bands.gap(it, scores.getOrNull(1)) }, Bands.of(best, scores.getOrNull(1)))
        }
    }

    @Test
    fun `a high-band winner is browsed alone`() {
        assertThat(LookupPlan.recordingsToBrowse(ranking(0.97, 0.80))).containsExactly("r0")
        assertThat(LookupPlan.recordingsToBrowse(ranking(0.95))).containsExactly("r0")
    }

    @Test
    fun `recordings less than the band gap below the winner are browsed too, at most three`() {
        assertThat(LookupPlan.recordingsToBrowse(ranking(0.85, 0.80, 0.76, 0.70))).containsExactly("r0", "r1", "r2")
        assertThat(LookupPlan.recordingsToBrowse(ranking(0.95, 0.94, 0.93, 0.92, 0.91))).containsExactly("r0", "r1", "r2")
    }

    @Test
    fun `a recording exactly the band gap below is not within it`() {
        assertThat(LookupPlan.recordingsToBrowse(ranking(0.80, 0.70))).containsExactly("r0")
        assertThat(LookupPlan.recordingsToBrowse(ranking(0.90, 0.80))).containsExactly("r0")
    }

    @Test
    fun `a runner-up within the gap but in the no-match band is not browsed`() {
        assertThat(LookupPlan.recordingsToBrowse(ranking(0.55, 0.48, 0.46))).containsExactly("r0")
        assertThat(LookupPlan.recordingsToBrowse(ranking(0.55, 0.50, 0.49))).containsExactly("r0", "r1")
    }

    @Test
    fun `nothing is browsed without a match`() {
        assertThat(LookupPlan.recordingsToBrowse(ranking(0.49, 0.45))).isEmpty()
        assertThat(LookupPlan.recordingsToBrowse(ranking())).isEmpty()
    }

    @Test
    fun `a recording known by its ID only, or without a title or a credit, is worth a lookup`() {
        assertThat(LookupPlan.needsRecordingLookup(match(candidate("a"), 0.9))).isFalse()
        assertThat(LookupPlan.needsRecordingLookup(match(candidate("a", title = ""), 0.9))).isTrue()
        assertThat(LookupPlan.needsRecordingLookup(match(candidate("a", credit = ""), 0.9))).isTrue()
        assertThat(LookupPlan.needsRecordingLookup(match(candidate("a", title = "", credit = "", length = null), 0.9))).isTrue()
    }

    @Test
    fun `a missing length alone is not worth a lookup, since MusicBrainz has none to give`() {
        assertThat(LookupPlan.needsRecordingLookup(match(candidate("a", length = null), 0.9))).isFalse()
        val text = match(candidate("a", length = null), 0.8, foundBy = setOf(Source.TEXT_SEARCH))
        assertThat(LookupPlan.needsRecordingLookup(text)).isFalse()
    }

    @Test
    fun `a recording stage 1 already looked up is never looked up again`() {
        val tagged = setOf(Source.TAG_RECORDING_ID, Source.FINGERPRINT)
        assertThat(LookupPlan.needsRecordingLookup(match(candidate("a", length = null), 0.8, tagged))).isFalse()
        assertThat(LookupPlan.needsRecordingLookup(match(candidate("a", credit = ""), 0.8, tagged))).isFalse()
    }
}
