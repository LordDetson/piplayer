package io.github.lorddetson.piplayer.identification

import android.content.Context
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient
import io.github.lorddetson.piplayer.identification.provider.CoverArtArchiveProvider
import io.github.lorddetson.piplayer.identification.provider.ProviderAccess
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatus
import io.github.lorddetson.piplayer.identification.provider.ProviderStatusStore
import io.github.lorddetson.piplayer.identification.provider.identificationProviders
import io.github.lorddetson.piplayer.net.Connectivity
import io.github.lorddetson.piplayer.net.GateInterceptor
import io.github.lorddetson.piplayer.net.GatedDns
import io.github.lorddetson.piplayer.net.GatedSocketFactory
import io.github.lorddetson.piplayer.net.HopGate
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import java.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Where the providers are reached; tests point them at fakes. */
data class ProviderBaseUrls(val acoustId: HttpUrl, val musicBrainz: HttpUrl, val coverArtArchive: HttpUrl) {
    companion object {
        val PRODUCTION = ProviderBaseUrls(
            acoustId = AcoustIdClient.DEFAULT_BASE_URL.toHttpUrl(),
            musicBrainz = MusicBrainzClient.DEFAULT_BASE_URL.toHttpUrl(),
            coverArtArchive = "https://${CoverArtArchiveProvider.host}/".toHttpUrl(),
        )
    }
}

/**
 * The network side of identification for one process (TI §8, §9): one HTTP client, one rate limiter per provider, the
 * online gate, the connectivity check and the providers' sticky stops. The app holds one instance in its main process
 * ([of]); tests build their own, so their limiters and stops never leak into each other. Every client made here sends
 * through [http], whose interceptor checks [gate] on every request and redirect hop.
 */
class IdentificationServices(
    val http: OkHttpClient,
    val baseUrls: ProviderBaseUrls,
    val clock: Clock,
    val timeSource: TimeSource.WithComparableMarks,
    val gate: OnlineGate,
    val connectivity: Connectivity,
    val providerStatus: ProviderStatusStore,
    val retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 3),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    init {
        // OkHttp's own redirects would run below GateInterceptor, so a hop's host would be looked up before the gate;
        // an ungated resolver or socket factory would let OkHttp's own repeats look up and connect while it is closed.
        require(
            http.interceptors.any { it is GateInterceptor && it.gate === gate } &&
                http.networkInterceptors.any { it is HopGate && it.gate === gate } &&
                (http.dns as? GatedDns)?.gate === gate &&
                (http.socketFactory as? GatedSocketFactory)?.gate === gate &&
                !http.followRedirects,
        ) {
            "The HTTP client must check the services' own online gate on every hop, lookup and connection and follow " +
                "redirects only through it (HttpClients.create)"
        }
    }

    private val limiters: Map<ProviderId, RateLimiter> =
        identificationProviders.associate { it.id to RateLimiter(it.minInterval, timeSource) }

    /** The limiter every request to [provider] goes through. */
    fun limiter(provider: ProviderId): RateLimiter = limiters.getValue(provider)

    /** What a client of [provider] checks before every attempt. */
    fun access(provider: ProviderId): ProviderAccess = ProviderAccess(provider, gate, connectivity, providerStatus)

    fun status(provider: ProviderId): StateFlow<ProviderStatus> = providerStatus.status(provider)

    val musicBrainz: MusicBrainzClient by lazy {
        MusicBrainzClient(
            http = http,
            limiter = limiter(ProviderId.MUSICBRAINZ),
            retryPolicy = retryPolicy,
            access = access(ProviderId.MUSICBRAINZ),
            baseUrl = baseUrls.musicBrainz,
            clock = clock,
            ioDispatcher = ioDispatcher,
        )
    }

    /** An AcoustID client with the build's application key (TI §9), sharing the AcoustID limiter and stop. */
    fun acoustId(apiKey: String): AcoustIdClient = AcoustIdClient(
        http = http,
        limiter = limiter(ProviderId.ACOUSTID),
        retryPolicy = retryPolicy,
        access = access(ProviderId.ACOUSTID),
        apiKey = apiKey,
        baseUrl = baseUrls.acoustId,
        clock = clock,
        ioDispatcher = ioDispatcher,
    )

    companion object {

        /**
         * Services whose HTTP client checks the same [gate]. [configureHttp] adjusts the client, for example to trust a
         * test server's certificate; it may not take out the gate checks, replace the resolver or the socket factory,
         * or turn on OkHttp's own redirects.
         */
        fun create(
            userAgent: String,
            gate: OnlineGate,
            connectivity: Connectivity,
            baseUrls: ProviderBaseUrls = ProviderBaseUrls.PRODUCTION,
            clock: Clock = Clock.systemUTC(),
            timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
            providerStatus: ProviderStatusStore = ProviderStatusStore(),
            retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 3),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            configureHttp: OkHttpClient.Builder.() -> Unit = {},
        ): IdentificationServices = IdentificationServices(
            http = HttpClients.create(userAgent, gate).newBuilder().apply(configureHttp).build(),
            baseUrls = baseUrls,
            clock = clock,
            timeSource = timeSource,
            gate = gate,
            connectivity = connectivity,
            providerStatus = providerStatus,
            retryPolicy = retryPolicy,
            ioDispatcher = ioDispatcher,
        )

        /**
         * The app's services: the real hosts, and a gate that stays closed until the online-lookup opt-in exists (M3
         * step 11), so nothing is sent while identification is dormant.
         */
        fun production(userAgent: String, connectivity: Connectivity): IdentificationServices =
            create(userAgent, InMemoryOnlineGate(), connectivity)

        /** The services of the app's main process. */
        fun of(context: Context): IdentificationServices =
            (context.applicationContext as PiPlayerApplication).identificationServices
    }
}
