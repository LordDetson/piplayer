package io.github.lorddetson.piplayer.fakes

import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.ProviderBaseUrls
import io.github.lorddetson.piplayer.identification.provider.ProviderAccess
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatusStore
import io.github.lorddetson.piplayer.net.Connectivity
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.NetworkState
import io.github.lorddetson.piplayer.net.OnlineConsent
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.UserAgent
import java.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Shared pieces for tests of the online clients; nothing here reaches a real host. */
object TestServices {

    val USER_AGENT = UserAgent.of("0.1.0")

    /** A gate with consent given to the current disclosure. */
    fun openGate(): InMemoryOnlineGate =
        InMemoryOnlineGate(OnlineConsent(enabled = true, disclosureVersion = OnlineConsent.CURRENT_DISCLOSURE_VERSION))

    /** A network whose state the test sets; validated unless told otherwise. */
    class FakeConnectivity(@Volatile var current: NetworkState = NetworkState.VALIDATED) : Connectivity {
        override fun state(): NetworkState = current
    }

    fun access(
        provider: ProviderId,
        gate: OnlineGate = openGate(),
        connectivity: Connectivity = FakeConnectivity(),
        statuses: ProviderStatusStore = ProviderStatusStore(),
    ) = ProviderAccess(provider, gate, connectivity, statuses)

    /** All three providers at one fake base URL each; the CAA one defaults to an unused path of [acoustId]'s host. */
    fun baseUrls(acoustId: HttpUrl, musicBrainz: HttpUrl, coverArtArchive: HttpUrl = acoustId.resolve("/caa/")!!) =
        ProviderBaseUrls(acoustId, musicBrainz, coverArtArchive)

    fun services(
        baseUrls: ProviderBaseUrls,
        gate: OnlineGate = openGate(),
        connectivity: Connectivity = FakeConnectivity(),
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
        clock: Clock = Clock.systemUTC(),
        statuses: ProviderStatusStore = ProviderStatusStore(),
        retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 3),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        configureHttp: OkHttpClient.Builder.() -> Unit = {},
    ): IdentificationServices = IdentificationServices.create(
        userAgent = USER_AGENT,
        gate = gate,
        connectivity = connectivity,
        baseUrls = baseUrls,
        clock = clock,
        timeSource = timeSource,
        providerStatus = statuses,
        retryPolicy = retryPolicy,
        ioDispatcher = ioDispatcher,
        configureHttp = configureHttp,
    )
}
