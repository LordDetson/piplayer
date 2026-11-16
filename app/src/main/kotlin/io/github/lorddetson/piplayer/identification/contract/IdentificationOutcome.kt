package io.github.lorddetson.piplayer.identification.contract

import io.github.lorddetson.piplayer.identification.FingerprintStage
import io.github.lorddetson.piplayer.identification.UnavailableReason
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStoppedException
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.release.ReleaseChoice
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.net.CaptivePortalException
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.NoNetworkException

/**
 * How one identification ended (TI §3, §6, §8, §9): the shared model of the pipeline (M3 step 13), which reports it, and
 * the review (step 18), which shows it. The first four end a call before anything is sent ([blocked]); [NoKey] and
 * [Found] end a run that went through its stages ([finished]). [Unreadable] ends a run whose file stage 0 cannot read,
 * and [Unavailable] one that a failing provider left without a result (TI §3 "One run").
 */
sealed interface IdentificationOutcome {

    /** Online lookups are off (the online gate, TI §9): nothing was sent, and the review offers the disclosure. */
    data object Disabled : IdentificationOutcome

    /** No validated network (TI §8): nothing was sent. */
    data object NoNetwork : IdentificationOutcome

    /** The network asks for a sign-in first (a captive portal, TI §8): nothing was sent. */
    data object CaptivePortal : IdentificationOutcome

    /** [provider] stopped for the rest of the process for [reason] (TI §8), so it was not asked. */
    data class ProviderStopped(val provider: ProviderId, val reason: StopReason) : IdentificationOutcome

    /**
     * Nothing reached the medium band, and the fingerprint lookup could not run because the build has no AcoustID
     * application key (TI §9): the review says so instead of a plain "no match".
     */
    data object NoKey : IdentificationOutcome

    /** Stage 0 could not read the file (TI §3.1), so it has no content key: nothing was sent. */
    data object Unreadable : IdentificationOutcome

    /**
     * [provider] stayed busy or failed through every retry, and nothing reached the medium band, or the winner's
     * release could not be fetched (TI §3 "One run"): the review says to try again later instead of "no match".
     */
    data class Unavailable(val provider: ProviderId) : IdentificationOutcome

    /**
     * The run went through its stages: [ranking] holds the scored recordings, best first, in their [band] (TI §4.1;
     * [Band.NO_MATCH] when none reaches 0.50), [choice] the release choice of the winner (TI §4.2, [ReleaseChoice.NONE]
     * without one), and [acoustIdTrackId] the AcoustID track ID of the best fingerprint result, if any (TI §3.2).
     */
    data class Found(val ranking: Ranking, val choice: ReleaseChoice, val acoustIdTrackId: String?) : IdentificationOutcome {
        val band: Band get() = ranking.band
    }

    companion object {

        /**
         * The outcome of a call that sent nothing: the online gate, the network check or a provider's stop refused it
         * ([io.github.lorddetson.piplayer.net.SendBlockedException]), also when a client wrapped that error in its own.
         * Null for any other error, which the stage that met it handles.
         */
        fun blocked(error: Throwable): IdentificationOutcome? {
            for (cause in generateSequence(error) { it.cause }.take(MAX_CAUSES)) {
                when (cause) {
                    is GateClosedException -> return Disabled
                    is NoNetworkException -> return NoNetwork
                    is CaptivePortalException -> return CaptivePortal
                    is ProviderStoppedException -> return ProviderStopped(cause.provider, cause.reason)
                }
            }
            return null
        }

        /**
         * The outcome of a run that went through its stages. A run whose best score stays below the medium band in a
         * build without an AcoustID key ([fingerprintStage] unavailable for [UnavailableReason.NoKey]) is [NoKey]; every
         * other run is [Found] in its band.
         */
        fun finished(
            ranking: Ranking,
            choice: ReleaseChoice,
            acoustIdTrackId: String?,
            fingerprintStage: FingerprintStage,
        ): IdentificationOutcome {
            val noKey = (fingerprintStage as? FingerprintStage.Unavailable)?.reason == UnavailableReason.NoKey
            return if (ranking.band == Band.NO_MATCH && noKey) NoKey else Found(ranking, choice, acoustIdTrackId)
        }

        /** Causes looked through, so a cycle of causes cannot loop. */
        private const val MAX_CAUSES = 16
    }
}
