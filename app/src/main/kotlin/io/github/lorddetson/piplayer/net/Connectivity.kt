package io.github.lorddetson.piplayer.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What the default network can do for an online lookup. */
enum class NetworkState {
    /** It reaches the internet: Android has validated it. */
    VALIDATED,

    /** No network, or one that Android has not seen reach the internet. */
    NO_NETWORK,

    /** A network that shows a sign-in page first (hotel or station Wi-Fi). */
    CAPTIVE_PORTAL,
}

/**
 * Asked before every attempt of a call, so that a lookup without a usable network fails at once with
 * [NoNetworkException] or [CaptivePortalException] and sends nothing, instead of burning its retries (TI §8).
 */
fun interface Connectivity {
    fun state(): NetworkState

    companion object {
        /**
         * The state of a network with these capabilities: it needs both INTERNET and VALIDATED; a captive portal is
         * told apart so that the message can say what to do.
         */
        fun stateOf(hasInternet: Boolean, validated: Boolean, captivePortal: Boolean): NetworkState = when {
            captivePortal -> NetworkState.CAPTIVE_PORTAL
            hasInternet && validated -> NetworkState.VALIDATED
            else -> NetworkState.NO_NETWORK
        }
    }
}

/** The default network's capabilities as Android reports them (needs ACCESS_NETWORK_STATE). */
class AndroidConnectivity(context: Context) : Connectivity {

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun state(): NetworkState {
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return NetworkState.NO_NETWORK
        return Connectivity.stateOf(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        )
    }
}
