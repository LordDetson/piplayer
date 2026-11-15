package io.github.lorddetson.piplayer.identification.score

import kotlin.math.roundToInt

/** The stage that found a recording (TI §3), strongest first. */
enum class Source { TAG_RECORDING_ID, ISRC, FINGERPRINT, TEXT_SEARCH }

/** Which tier of TI §4.1 gave a score. */
enum class Tier { IDENTIFIER, SIMILARITY }

/** A value of the file a candidate can disagree with. */
enum class HintField { TITLE, ARTIST }

/**
 * Why a candidate scored as it did, for the review card (TI §4.1, §6): "fingerprint 0.97 · duration ±1 s · title 92%".
 *
 * @property source the stage whose tier gave the score.
 * @property tier the identifier tier, or the similarity tier for text search and for stage 1 and 2 recordings that do
 *   not qualify for the identifier tier.
 * @property foundBy every stage that found the recording.
 * @property fingerprintScore AcoustID's own score when the fingerprint found the recording, also when another stage
 *   gave the higher score.
 * @property durationDeltaMs the recording's length minus the file's duration; null without both.
 * @property title the title similarity to the file's title, null when either side has none.
 * @property artist the artist similarity to the file's artist, null when either side has none.
 * @property disagreement the file's values the candidate disagrees with (similarity below 0.5). An identifier hit keeps
 *   its score and shows this instead of a penalty.
 */
data class Reason(
    val source: Source,
    val tier: Tier,
    val foundBy: Set<Source>,
    val fingerprintScore: Double?,
    val durationDeltaMs: Long?,
    val title: Double?,
    val artist: Double?,
    val disagreement: Set<HintField>,
) {
    /** [title] as a whole percentage. */
    val titlePercent: Int? get() = title?.let { (it * 100).roundToInt() }
}
