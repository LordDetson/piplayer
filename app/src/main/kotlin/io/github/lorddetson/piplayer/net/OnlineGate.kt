package io.github.lorddetson.piplayer.net

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether PiPlayer may send anything to the online services at all (TI §9 Privacy). Every client built by
 * [HttpClients.create] asks it before every request and every redirect hop, and [RetryingCaller] asks it again before
 * every attempt, so a gate closed during a backoff stops the retries. Reading it must be cheap and must not block.
 */
fun interface OnlineGate {
    fun isOpen(): Boolean
}

/**
 * The user's choice about online lookups: one toggle plus the version of the disclosure the user accepted, with no
 * per-service flags. A newer disclosure (a new service, or more data shared) closes the gate again until the user has
 * read it.
 */
data class OnlineConsent(val enabled: Boolean, val disclosureVersion: Int) {

    /** Open only while the toggle is on and the accepted disclosure is at least [required]. */
    fun allows(required: Int): Boolean = enabled && disclosureVersion >= required

    companion object {
        /** No choice made yet: closed. */
        val NONE = OnlineConsent(enabled = false, disclosureVersion = 0)

        /** The version of the in-app disclosure; raised whenever its list of services or shared data changes. */
        const val CURRENT_DISCLOSURE_VERSION = 1
    }
}

/**
 * A gate that keeps the consent in memory, closed until something opens it. It stands in for the DataStore-backed
 * gate (M3 step 11) and is what the production services use until then, so dormant code sends nothing.
 */
class InMemoryOnlineGate(
    initial: OnlineConsent = OnlineConsent.NONE,
    private val requiredDisclosureVersion: Int = OnlineConsent.CURRENT_DISCLOSURE_VERSION,
) : OnlineGate {

    private val mutableConsent = MutableStateFlow(initial)
    val consent: StateFlow<OnlineConsent> = mutableConsent.asStateFlow()

    override fun isOpen(): Boolean = mutableConsent.value.allows(requiredDisclosureVersion)

    fun set(consent: OnlineConsent) {
        mutableConsent.value = consent
    }
}

/**
 * A call that may not send anything now: online lookups are off, there is no usable network, or the provider is
 * stopped. Nothing was sent, and [RetryingCaller] neither retries it nor pauses the limiter for it (TI §8). It is an
 * [IOException] because OkHttp hands only those back from an interceptor; the dedicated type keeps it from being
 * retried like a network error.
 */
abstract class SendBlockedException(message: String) : IOException(message)

/** Online lookups are off (TI §9): nothing was sent. */
class GateClosedException : SendBlockedException("Online lookups are off; nothing was sent")

/** No network that is known to reach the internet: nothing was sent. */
class NoNetworkException : SendBlockedException("No validated network; nothing was sent")

/** The network asks for a sign-in page (a captive portal) before it reaches the internet: nothing was sent. */
class CaptivePortalException : SendBlockedException("The network asks for a sign-in first; nothing was sent")
