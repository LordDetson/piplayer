package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.score.HintField
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.Tier
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** One part of a candidate's reason as the card shows it (TI §4.1, §6), formatted for the locale by the screen. */
sealed interface ReasonPart {

    /**
     * The stage whose tier gave the score; [byText] for the similarity tier of a tagged recording ID or an ISRC, and
     * [fingerprintScore] AcoustID's own score when the fingerprint gave it.
     */
    data class Stage(val source: Source, val byText: Boolean = false, val fingerprintScore: Double? = null) : ReasonPart

    /** The recording's length minus the file's duration, in whole seconds (0 within half a second). */
    data class Duration(val seconds: Long) : ReasonPart

    data class TitleMatch(val percent: Int) : ReasonPart

    data class ArtistMatch(val percent: Int) : ReasonPart

    /** The candidate disagrees with the file's [field] (similarity below 0.5). */
    data class Differs(val field: HintField) : ReasonPart

    /** Another stage found the recording too; [fingerprintScore] when that stage is the fingerprint. */
    data class AlsoFoundBy(val source: Source, val fingerprintScore: Double? = null) : ReasonPart
}

/** The parts of a reason in the card's order (TI §6, review screen A). */
object ReasonParts {

    fun of(reason: Reason): List<ReasonPart> = buildList {
        val byText = reason.tier == Tier.SIMILARITY && reason.source in setOf(Source.TAG_RECORDING_ID, Source.ISRC)
        add(ReasonPart.Stage(reason.source, byText, reason.fingerprintScore.takeIf { reason.source == Source.FINGERPRINT }))
        reason.durationDeltaMs?.let { add(ReasonPart.Duration(seconds(it))) }
        reason.title?.let { add(ReasonPart.TitleMatch(percent(it))) }
        reason.artist?.let { add(ReasonPart.ArtistMatch(percent(it))) }
        HintField.entries.filter { it in reason.disagreement }.forEach { add(ReasonPart.Differs(it)) }
        // Strongest first, as the stages run.
        Source.entries.filter { it != reason.source && it in reason.foundBy }.forEach { source ->
            add(ReasonPart.AlsoFoundBy(source, reason.fingerprintScore.takeIf { source == Source.FINGERPRINT }))
        }
    }

    /** Whole seconds, half a second rounding away from 0, so that ±499 ms is 0 and ±500 ms is ±1. */
    private fun seconds(ms: Long): Long = ms.sign * ((abs(ms) + 500) / 1_000)

    private fun percent(similarity: Double): Int = (similarity * 100).roundToInt()
}
