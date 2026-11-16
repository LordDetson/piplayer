package io.github.lorddetson.piplayer.identification

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient
import io.github.lorddetson.piplayer.identification.caa.CoverArtArchiveClient
import io.github.lorddetson.piplayer.identification.caa.FailureCache
import io.github.lorddetson.piplayer.identification.caa.InMemoryFailureCache
import io.github.lorddetson.piplayer.identification.caa.RoomFailureCache
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient
import io.github.lorddetson.piplayer.identification.provider.CoverArtArchiveProvider
import io.github.lorddetson.piplayer.identification.provider.ProviderAccess
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatus
import io.github.lorddetson.piplayer.identification.provider.ProviderStatusStore
import io.github.lorddetson.piplayer.identification.provider.identificationProviders
import io.github.lorddetson.piplayer.identification.release.FolderReleaseHint
import io.github.lorddetson.piplayer.identification.release.InMemoryReleaseCache
import io.github.lorddetson.piplayer.identification.release.LibraryFolderReleaseHint
import io.github.lorddetson.piplayer.identification.release.NoFolderReleaseHint
import io.github.lorddetson.piplayer.identification.release.ReleaseCache
import io.github.lorddetson.piplayer.identification.release.ReleaseChooser
import io.github.lorddetson.piplayer.identification.release.RoomReleaseCache
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.net.Connectivity
import io.github.lorddetson.piplayer.net.GateInterceptor
import io.github.lorddetson.piplayer.net.GatedDns
import io.github.lorddetson.piplayer.net.GatedSocketFactory
import io.github.lorddetson.piplayer.net.HopGate
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.settings.DataStoreOnlineGate
import java.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
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
 * through [http], whose interceptor checks [gate] on every request and redirect hop. [acoustIdApiKey] is the build's
 * AcoustID application key, empty in a build without one; it is kept out of every `toString` and log line.
 * [releaseCache] and [failureCache] are the release lookups (TI §5.1) and the Cover Art Archive failure entries (TI §5.2)
 * of these services, in the library database for the app ([production]) and in memory by default.
 * [folderReleaseHint] is the one place the folder hint of the release ranking is bound (TI §4.2): [releases] uses it.
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
    private val acoustIdApiKey: String = "",
    val releaseCache: ReleaseCache = InMemoryReleaseCache(),
    val failureCache: FailureCache = InMemoryFailureCache(),
    val folderReleaseHint: FolderReleaseHint = NoFolderReleaseHint,
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

    /**
     * Waits until [gate] knows the user's choice, at most [GATE_READ_TIMEOUT]: the app's gate is closed until it has read
     * the `settings` file (TI §9), and a run that started before the read would otherwise report online lookups as off
     * (TI §3 "One run"). Other gates know their state at once.
     */
    suspend fun awaitGateRead() {
        val stored = gate as? DataStoreOnlineGate ?: return
        withTimeoutOrNull(GATE_READ_TIMEOUT) { stored.awaitLoaded() }
    }

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

    /**
     * The Cover Art Archive client (TI §5.2), one per services instance like the other clients, so the 503s in a row
     * and the failure entries belong to the provider. The entries are kept in [failureCache].
     */
    val coverArtArchive: CoverArtArchiveClient by lazy {
        CoverArtArchiveClient(
            http = http,
            limiter = limiter(ProviderId.COVER_ART_ARCHIVE),
            retryPolicy = retryPolicy,
            access = access(ProviderId.COVER_ART_ARCHIVE),
            failures = failureCache,
            baseUrl = baseUrls.coverArtArchive,
            clock = clock,
            ioDispatcher = ioDispatcher,
        )
    }

    /**
     * Release choice and release details (TI §4.2, §5.1) for these services: lookups through [musicBrainz], kept in
     * [releaseCache], with [folderReleaseHint] as the first key of the ranking.
     */
    val releases: ReleaseChooser by lazy { ReleaseChooser(musicBrainz, releaseCache, folderReleaseHint) }

    /**
     * Whether stage 3 (TI §3.2) can run: a build without an AcoustID application key has no fingerprint lookup, sends
     * nothing to AcoustID and identifies by the other stages, text search included (TI §9).
     */
    val fingerprintStage: FingerprintStage by lazy {
        if (acoustIdApiKey.isBlank()) {
            FingerprintStage.Unavailable(UnavailableReason.NoKey)
        } else {
            FingerprintStage.Available(acoustId(acoustIdApiKey))
        }
    }

    /**
     * An AcoustID client with an application key (TI §9), sharing the AcoustID limiter and stop. Shipped code takes its
     * client from [fingerprintStage] only, so the key enters through the constructor and no caller can skip the
     * no-key state; tests of this module may still make one with a key of their own (ApiKeyReadersTest).
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun acoustId(apiKey: String): AcoustIdClient = AcoustIdClient(
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

        /** How long a run waits for the app's gate to read the user's choice; still closed after it means off. */
        val GATE_READ_TIMEOUT = 5.seconds

        /**
         * Services whose HTTP client checks the same [gate]. [configureHttp] adjusts the client, for example to trust a
         * test server's certificate; it may not take out the gate checks, replace the resolver or the socket factory,
         * or turn on OkHttp's own redirects. [acoustIdApiKey] is the application key of
         * [IdentificationServices.fingerprintStage], empty for none. The caches default to memory and the folder hint to
         * none.
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
            acoustIdApiKey: String = "",
            releaseCache: ReleaseCache = InMemoryReleaseCache(),
            failureCache: FailureCache = InMemoryFailureCache(),
            folderReleaseHint: FolderReleaseHint = NoFolderReleaseHint,
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
            acoustIdApiKey = acoustIdApiKey,
            releaseCache = releaseCache,
            failureCache = failureCache,
            folderReleaseHint = folderReleaseHint,
        )

        /**
         * The app's services: the real hosts, the build's [acoustIdApiKey] (empty for none), and the user's persisted
         * opt-in as the gate (TI §9), closed until the user enables online lookups. The type keeps any other gate, such
         * as an open one of a test, out of the app's services. The release cache and the failure entries live in the
         * library database that [library] reaches (TI §10.1), which nothing opens before a cache is used; so does the
         * folder hint, which reads the decided and tagged releases of the library (TI §4.2 key 1).
         */
        fun production(
            userAgent: String,
            connectivity: Connectivity,
            acoustIdApiKey: String,
            gate: DataStoreOnlineGate,
            library: LibraryAccess,
        ): IdentificationServices {
            val clock = Clock.systemUTC()
            return create(
                userAgent = userAgent,
                gate = gate,
                connectivity = connectivity,
                clock = clock,
                acoustIdApiKey = acoustIdApiKey,
                releaseCache = RoomReleaseCache(library, clock),
                failureCache = RoomFailureCache(library),
                folderReleaseHint = LibraryFolderReleaseHint(library),
            )
        }

        /** The services of the app's main process. */
        fun of(context: Context): IdentificationServices =
            (context.applicationContext as PiPlayerApplication).identificationServices
    }
}

/** Whether stage 3, the fingerprint lookup (TI §3.2), can run in this build, and if not, why. */
sealed interface FingerprintStage {

    /** Stage 3 looks fingerprints up through [acoustId]. Its `toString` names the state only, never the client's key. */
    class Available(val acoustId: AcoustIdClient) : FingerprintStage {
        override fun toString(): String = "FingerprintStage.Available"
    }

    /** Stage 3 is skipped for [reason], and identification goes on with the next stage. */
    data class Unavailable(val reason: UnavailableReason) : FingerprintStage
}

/** Why a stage cannot run in this build. */
sealed interface UnavailableReason {

    /** The build has no AcoustID application key (TI §9), as in a fork that has not registered its own. */
    data object NoKey : UnavailableReason
}
