package io.github.lorddetson.piplayer.identification.score

/** The confidence bands of TI §4.1. */
enum class Band { HIGH, MEDIUM, NO_MATCH }

/**
 * Places the best score in a band. The thresholds are starting points, provisional until the corpus run (PIM-60,
 * TI §11.4).
 */
object Bands {

    const val HIGH = 0.90
    const val GAP = 0.10
    const val MEDIUM = 0.50

    /** Floating-point rounding (0.9 - 0.8 is just below 0.1) never moves a boundary case. */
    private const val TOLERANCE = 1e-9

    /**
     * High is at least [HIGH] with a runner-up gap of at least [GAP]; medium is from [MEDIUM], or high without the gap;
     * below [MEDIUM] or without a candidate there is no match. Boundaries belong to the higher band; a missing
     * [runnerUp] counts as 0.
     */
    fun of(best: Double?, runnerUp: Double?): Band {
        if (best == null || best < MEDIUM - TOLERANCE) return Band.NO_MATCH
        return if (best >= HIGH - TOLERANCE && gap(best, runnerUp) >= GAP - TOLERANCE) Band.HIGH else Band.MEDIUM
    }

    /** The runner-up gap: [best] minus the second best, which counts as 0 for a single candidate. */
    fun gap(best: Double, runnerUp: Double?): Double = best - (runnerUp ?: 0.0)
}
