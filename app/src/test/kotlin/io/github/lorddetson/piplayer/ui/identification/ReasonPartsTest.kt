package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.score.HintField
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.Tier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The templated reason of the card (TI §4.1, §6; §11.1 M3 "Review diff", the reason's parts). */
class ReasonPartsTest {

    private fun reason(
        source: Source = Source.FINGERPRINT,
        tier: Tier = Tier.IDENTIFIER,
        foundBy: Set<Source> = setOf(source),
        fingerprintScore: Double? = 0.97,
        durationDeltaMs: Long? = 800,
        title: Double? = 0.92,
        artist: Double? = null,
        disagreement: Set<HintField> = emptySet(),
    ) = Reason(source, tier, foundBy, fingerprintScore, durationDeltaMs, title, artist, disagreement)

    @Test
    fun `a fingerprint hit reads as the stage with AcoustID's score, the duration and the title`() {
        assertThat(ReasonParts.of(reason())).containsExactly(
            ReasonPart.Stage(Source.FINGERPRINT, fingerprintScore = 0.97),
            ReasonPart.Duration(1),
            ReasonPart.TitleMatch(92),
        )
    }

    @Test
    fun `the duration rounds to whole seconds, 0 within half a second, signed`() {
        assertThat(ReasonParts.of(reason(durationDeltaMs = 499))).contains(ReasonPart.Duration(0))
        assertThat(ReasonParts.of(reason(durationDeltaMs = -499))).contains(ReasonPart.Duration(0))
        assertThat(ReasonParts.of(reason(durationDeltaMs = 500))).contains(ReasonPart.Duration(1))
        assertThat(ReasonParts.of(reason(durationDeltaMs = -500))).contains(ReasonPart.Duration(-1))
        assertThat(ReasonParts.of(reason(durationDeltaMs = -3_400))).contains(ReasonPart.Duration(-3))
        assertThat(ReasonParts.of(reason(durationDeltaMs = null)).filterIsInstance<ReasonPart.Duration>()).isEmpty()
    }

    @Test
    fun `disagreements and the artist similarity are parts of their own`() {
        val parts = ReasonParts.of(reason(title = 0.31, artist = 0.2, disagreement = setOf(HintField.TITLE, HintField.ARTIST)))

        assertThat(parts).containsSubsequence(
            ReasonPart.TitleMatch(31),
            ReasonPart.ArtistMatch(20),
            ReasonPart.Differs(HintField.TITLE),
            ReasonPart.Differs(HintField.ARTIST),
        )
    }

    @Test
    fun `the other stages that found it come last, the fingerprint with AcoustID's score`() {
        val parts = ReasonParts.of(
            reason(source = Source.ISRC, foundBy = setOf(Source.ISRC, Source.FINGERPRINT, Source.TEXT_SEARCH), fingerprintScore = 0.81),
        )

        assertThat(parts.first()).isEqualTo(ReasonPart.Stage(Source.ISRC))
        assertThat(parts).endsWith(ReasonPart.AlsoFoundBy(Source.FINGERPRINT, 0.81), ReasonPart.AlsoFoundBy(Source.TEXT_SEARCH))
    }

    @Test
    fun `stages 1 and 2 scored by the similarity tier say so`() {
        assertThat(ReasonParts.of(reason(source = Source.TAG_RECORDING_ID, tier = Tier.SIMILARITY, fingerprintScore = null)).first())
            .isEqualTo(ReasonPart.Stage(Source.TAG_RECORDING_ID, byText = true))
        assertThat(ReasonParts.of(reason(source = Source.TEXT_SEARCH, tier = Tier.SIMILARITY, fingerprintScore = null)).first())
            .isEqualTo(ReasonPart.Stage(Source.TEXT_SEARCH))
    }
}
