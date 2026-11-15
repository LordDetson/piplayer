package io.github.lorddetson.piplayer.identification.provider

import io.github.lorddetson.piplayer.net.CallGuard
import io.github.lorddetson.piplayer.net.CaptivePortalException
import io.github.lorddetson.piplayer.net.Connectivity
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.NetworkState
import io.github.lorddetson.piplayer.net.NoNetworkException
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.net.SendBlockedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The online services identification talks to (TI §9). */
enum class ProviderId { ACOUSTID, MUSICBRAINZ, COVER_ART_ARCHIVE }

/** Why a provider stopped for the rest of the process. */
enum class StopReason {
    /** MusicBrainz answered 403 with an `X-RateLimit-Who` that starts with `ua-`: it refuses our User-Agent. */
    USER_AGENT_REJECTED,

    /** AcoustID answered error 4: it refuses the application key. */
    INVALID_API_KEY,
}

/** Whether a provider may still be asked, shown in Settings and in the review (M3). */
sealed interface ProviderStatus {
    data object Active : ProviderStatus

    data class Stopped(val reason: StopReason) : ProviderStatus
}

/**
 * The sticky stops of one set of services (TI §9, §3.2): a provider that refused the User-Agent or the application key
 * stays stopped for the life of the process, so later calls fail at once without sending. It is not persisted: the
 * next process tries once more. Every services instance has its own store, so tests do not stop each other.
 */
class ProviderStatusStore {

    private val statuses: Map<ProviderId, MutableStateFlow<ProviderStatus>> =
        ProviderId.entries.associateWith { MutableStateFlow(ProviderStatus.Active) }

    fun status(provider: ProviderId): StateFlow<ProviderStatus> = statuses.getValue(provider).asStateFlow()

    /** The status now; read before every attempt, so it allocates nothing. */
    fun current(provider: ProviderId): ProviderStatus = statuses.getValue(provider).value

    /** Stops [provider] for good; the first reason is kept. */
    fun stop(provider: ProviderId, reason: StopReason) {
        statuses.getValue(provider).compareAndSet(ProviderStatus.Active, ProviderStatus.Stopped(reason))
    }
}

/** The provider stopped earlier in this process (see [ProviderStatusStore]): nothing was sent. */
class ProviderStoppedException(val provider: ProviderId, val reason: StopReason) :
    SendBlockedException("$provider is stopped for this session ($reason); nothing was sent")

/**
 * What a client of [provider] checks before every attempt (see [io.github.lorddetson.piplayer.net.RetryingCaller]):
 * the online gate first, then the provider's sticky stop, then the network. A client also uses it to [stop] its
 * provider.
 */
class ProviderAccess(
    val provider: ProviderId,
    private val gate: OnlineGate,
    private val connectivity: Connectivity,
    private val statuses: ProviderStatusStore,
) : CallGuard {

    val status: StateFlow<ProviderStatus> get() = statuses.status(provider)

    override fun check() {
        if (!gate.isOpen()) throw GateClosedException()
        (statuses.current(provider) as? ProviderStatus.Stopped)?.let { throw ProviderStoppedException(provider, it.reason) }
        when (connectivity.state()) {
            NetworkState.VALIDATED -> Unit
            NetworkState.NO_NETWORK -> throw NoNetworkException()
            NetworkState.CAPTIVE_PORTAL -> throw CaptivePortalException()
        }
    }

    fun stop(reason: StopReason) = statuses.stop(provider, reason)
}
