package io.github.lorddetson.piplayer.identification

import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.identification.caa.CoverUrls
import io.github.lorddetson.piplayer.identification.caa.FailureKind
import io.github.lorddetson.piplayer.identification.caa.InMemoryFailureCache
import io.github.lorddetson.piplayer.identification.caa.RoomFailureCache
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.release.FolderReleaseHint
import io.github.lorddetson.piplayer.identification.release.InMemoryReleaseCache
import io.github.lorddetson.piplayer.identification.release.LookupPlanTest.Companion.candidate
import io.github.lorddetson.piplayer.identification.release.LookupPlanTest.Companion.match
import io.github.lorddetson.piplayer.identification.release.LibraryFolderReleaseHint
import io.github.lorddetson.piplayer.identification.release.NoFolderReleaseHint
import io.github.lorddetson.piplayer.identification.release.ReleaseFixtures
import io.github.lorddetson.piplayer.identification.release.ReleaseRequest
import io.github.lorddetson.piplayer.identification.release.RoomReleaseCache
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatus
import io.github.lorddetson.piplayer.identification.provider.ProviderStoppedException
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.RecordingSearch
import io.github.lorddetson.piplayer.identification.text.TextSearch
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.NetworkState
import io.github.lorddetson.piplayer.net.NoNetworkException
import io.github.lorddetson.piplayer.settings.DataStoreOnlineGate
import io.github.lorddetson.piplayer.settings.IdentificationSettings
import io.github.lorddetson.piplayer.settings.MemoryDataStore
import javax.net.SocketFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class IdentificationServicesTest {

    @StartStop
    private val acoustIdServer = MockWebServer()

    @StartStop
    private val musicBrainzServer = MockWebServer()

    @StartStop
    private val caaServer = MockWebServer()

    /** A library database that a JVM test cannot open; the app's services must not open it before a cache is used. */
    private var libraryUses = 0
    private val noLibrary = object : LibraryAccess {
        override fun <T> use(block: (LibraryDatabase) -> T): T {
            libraryUses++
            error("No library database in a JVM test")
        }
    }

    private val acoustId = FakeAcoustId()
    private val musicBrainz = FakeMusicBrainz()
    private val key = "testKey123"
    private val fingerprint = "AQAAT0mUaEkSRZEGAA"
    private val query = "recording:(paper lanterns)"

    private val settingsScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun stopSettings() = settingsScope.cancel()

    /** The gate of the app: the opt-in in a `settings` file, empty here as on a new install. */
    private val persistedGate = DataStoreOnlineGate(IdentificationSettings(MemoryDataStore()), settingsScope)

    /** The app's `device_state` file, empty here as on a new install. */
    private val deviceState = MemoryDataStore()

    private fun TestScope.services(
        gate: io.github.lorddetson.piplayer.net.OnlineGate = TestServices.openGate(),
        connectivity: io.github.lorddetson.piplayer.net.Connectivity = TestServices.FakeConnectivity(),
        log: RecordingRequestLog? = null,
        acoustIdApiKey: String = key,
    ): IdentificationServices {
        acoustIdServer.dispatcher = log?.wrap(acoustId) ?: acoustId
        musicBrainzServer.dispatcher = log?.wrap(musicBrainz) ?: musicBrainz
        return TestServices.services(
            baseUrls = TestServices.baseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/")),
            gate = gate,
            connectivity = connectivity,
            timeSource = testTimeSource,
            acoustIdApiKey = acoustIdApiKey,
        )
    }

    @Test
    fun `the app's services use the real hosts and the persisted opt-in, closed until the user enables it`() {
        val services =
            IdentificationServices.production(
                TestServices.USER_AGENT, TestServices.FakeConnectivity(), "", persistedGate, noLibrary, deviceState,
            )
        assertThat(services.gate).isSameAs(persistedGate)
        assertThat(services.gate.isOpen()).isFalse()
        runBlocking { persistedGate.awaitLoaded() }
        assertThat(services.gate.isOpen()).isFalse()
        runBlocking { persistedGate.enable() }
        assertThat(services.gate.isOpen()).isTrue()
        runBlocking { persistedGate.turnOff() }
        assertThat(services.gate.isOpen()).isFalse()
        assertThat(services.baseUrls).isEqualTo(ProviderBaseUrls.PRODUCTION)
        assertThat(ProviderBaseUrls.PRODUCTION.acoustId).isEqualTo("https://api.acoustid.org/v2/".toHttpUrl())
        assertThat(ProviderBaseUrls.PRODUCTION.musicBrainz).isEqualTo("https://musicbrainz.org/ws/2/".toHttpUrl())
        assertThat(ProviderBaseUrls.PRODUCTION.coverArtArchive).isEqualTo("https://coverartarchive.org/".toHttpUrl())
    }

    @Test
    fun `one limiter per provider with the intervals of TI §8`() = runTest {
        val services = services()
        assertThat(services.limiter(ProviderId.MUSICBRAINZ).minInterval).isEqualTo(1_100.milliseconds)
        assertThat(services.limiter(ProviderId.ACOUSTID).minInterval).isEqualTo(350.milliseconds)
        assertThat(services.limiter(ProviderId.COVER_ART_ARCHIVE).minInterval).isEqualTo(1_000.milliseconds)
        assertThat(services.limiter(ProviderId.ACOUSTID)).isSameAs(services.limiter(ProviderId.ACOUSTID))
    }

    @Test
    fun `the HTTP client must check the services' own gate`() {
        val gate = TestServices.openGate()
        val error = runCatching {
            IdentificationServices(
                http = HttpClients.create(TestServices.USER_AGENT, TestServices.openGate()),
                baseUrls = ProviderBaseUrls.PRODUCTION,
                clock = java.time.Clock.systemUTC(),
                timeSource = kotlin.time.TimeSource.Monotonic,
                gate = gate,
                connectivity = TestServices.FakeConnectivity(),
                providerStatus = io.github.lorddetson.piplayer.identification.provider.ProviderStatusStore(),
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a client whose hop check was taken out is refused`() {
        val error = runCatching {
            IdentificationServices.create(
                userAgent = TestServices.USER_AGENT,
                gate = TestServices.openGate(),
                connectivity = TestServices.FakeConnectivity(),
                configureHttp = { networkInterceptors().clear() },
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a client that looks up or connects without asking the gate is refused`() {
        for (bypass in listOf<OkHttpClient.Builder.() -> Unit>(
            { dns(Dns.SYSTEM) },
            { socketFactory(SocketFactory.getDefault()) },
        )) {
            val error = runCatching {
                IdentificationServices.create(
                    userAgent = TestServices.USER_AGENT,
                    gate = TestServices.openGate(),
                    connectivity = TestServices.FakeConnectivity(),
                    configureHttp = bypass,
                )
            }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `a client that follows redirects below the gate is refused`() {
        val error = runCatching {
            IdentificationServices.create(
                userAgent = TestServices.USER_AGENT,
                gate = TestServices.openGate(),
                connectivity = TestServices.FakeConnectivity(),
                configureHttp = { followRedirects(true) },
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `no client sends anything while the gate is closed`() = runTest {
        val services = services(gate = InMemoryOnlineGate())
        assertThat(runCatching { services.musicBrainz.searchRecordings(query, 25) }.exceptionOrNull())
            .isInstanceOf(GateClosedException::class.java)
        assertThat(runCatching { services.acoustId(key).lookup(fingerprint, 214_400) }.exceptionOrNull())
            .isInstanceOf(GateClosedException::class.java)
        assertThat(acoustIdServer.requestCount + musicBrainzServer.requestCount).isZero()
    }

    @Test
    fun `no client sends anything without a validated network`() = runTest {
        val services = services(connectivity = TestServices.FakeConnectivity(NetworkState.NO_NETWORK))
        assertThat(runCatching { services.musicBrainz.searchRecordings(query, 25) }.exceptionOrNull())
            .isInstanceOf(NoNetworkException::class.java)
        assertThat(runCatching { services.acoustId(key).lookup(fingerprint, 214_400) }.exceptionOrNull())
            .isInstanceOf(NoNetworkException::class.java)
        assertThat(acoustIdServer.requestCount + musicBrainzServer.requestCount).isZero()
    }

    @Test
    fun `the clients reach the fakes with the User-Agent, and the log never shows the key`() = runTest {
        val log = RecordingRequestLog { testScheduler.currentTime }
        val services = services(log = log)

        assertThat(services.musicBrainz.searchRecordings(query, 25).recordings).hasSize(2)
        assertThat(services.acoustId(key).lookup(fingerprint, 214_400).results).hasSize(2)

        assertThat(log.entries.map { it.userAgent }).containsOnly(TestServices.USER_AGENT)
        assertThat(log.entries.map { it.path.substringBefore('?') }).containsExactly("/ws/2/recording", "/v2/lookup")
        assertThat(log.entries[1].body).contains("client=$key")
        assertThat(log.entries.toString()).doesNotContain(key)
    }

    @Test
    fun `clients made by one services instance share its limiters`() = runTest {
        val services = services()
        services.acoustId(key).lookup(fingerprint, 214_400)
        services.acoustId(key).lookup(fingerprint, 214_400)
        assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(350)
        services.musicBrainz.searchRecordings(query, 25)
        val before = testScheduler.currentTime
        services.musicBrainz.searchRecordings(query, 25)
        assertThat(testScheduler.currentTime - before).isGreaterThanOrEqualTo(1_100)
    }

    @Test
    fun `a stop in one services instance does not stop another`() = runTest {
        val first = services()
        val second = services()
        musicBrainz.enqueue(FakeMusicBrainz.userAgentRejected())

        assertThat(runCatching { first.musicBrainz.searchRecordings(query, 25) }.exceptionOrNull())
            .isInstanceOf(io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException.UserAgentRejected::class.java)
        assertThat(first.status(ProviderId.MUSICBRAINZ).value).isEqualTo(ProviderStatus.Stopped(StopReason.USER_AGENT_REJECTED))
        assertThat(first.status(ProviderId.ACOUSTID).value).isEqualTo(ProviderStatus.Active)

        assertThat(second.status(ProviderId.MUSICBRAINZ).value).isEqualTo(ProviderStatus.Active)
        assertThat(second.musicBrainz.searchRecordings(query, 25).recordings).hasSize(2)
        assertThat(runCatching { first.musicBrainz.searchRecordings(query, 25) }.exceptionOrNull())
            .isInstanceOf(ProviderStoppedException::class.java)
        assertThat(musicBrainzServer.requestCount).isEqualTo(2)
    }

    @Test
    fun `an invalid key stops AcoustID for every client of the instance`() = runTest {
        val services = services()
        acoustId.enqueue(FakeAcoustId.ERROR_4.response())

        assertThat(runCatching { services.acoustId(key).lookup(fingerprint, 214_400) }.exceptionOrNull())
            .isInstanceOf(io.github.lorddetson.piplayer.identification.acoustid.AcoustIdException.InvalidApiKey::class.java)
        assertThat(runCatching { services.acoustId(key).lookup(fingerprint, 214_400) }.exceptionOrNull())
            .isInstanceOf(ProviderStoppedException::class.java)
        assertThat(acoustIdServer.requestCount).isEqualTo(1)
        // MusicBrainz is not affected.
        assertThat(services.musicBrainz.searchRecordings(query, 25).recordings).hasSize(2)
    }

    @Test
    fun `without an application key the fingerprint stage is unavailable and AcoustID gets nothing`() = runTest {
        for (blank in listOf("", "   ")) {
            val services = services(acoustIdApiKey = blank)
            assertThat(services.fingerprintStage).isEqualTo(FingerprintStage.Unavailable(UnavailableReason.NoKey))
        }
        assertThat(acoustIdServer.requestCount).isZero()
    }

    @Test
    fun `without an application key text search still reaches MusicBrainz`() = runTest {
        val log = RecordingRequestLog { testScheduler.currentTime }
        val services = services(log = log, acoustIdApiKey = "")
        assertThat(services.fingerprintStage).isInstanceOf(FingerprintStage.Unavailable::class.java)
        val search = TextSearch(
            RecordingSearch { query ->
                services.musicBrainz.searchRecordings(query.query, query.limit).recordings.map { it.toCandidate() }
            },
        )

        val result = search.search(TrackHint(title = "Paper Lanterns", artist = "The Quiet Harbors", durationMs = 214_400))

        assertThat(result.candidates.map { it.candidate.recordingId }).contains("11111111-1111-4111-8111-111111111111")
        assertThat(log.entries).isNotEmpty.allSatisfy { assertThat(it.path).startsWith("/ws/2/recording") }
        assertThat(acoustIdServer.requestCount).isZero()
    }

    @Test
    fun `with an application key the fingerprint stage looks up through the services' AcoustID limiter and stop`() = runTest {
        val services = services(acoustIdApiKey = key)
        val stage = services.fingerprintStage
        assertThat(stage).isInstanceOf(FingerprintStage.Available::class.java)
        stage as FingerprintStage.Available
        acoustId.enqueue(FakeAcoustId.ERROR_4.response())

        assertThat(runCatching { stage.acoustId.lookup(fingerprint, 214_400) }.exceptionOrNull())
            .isInstanceOf(io.github.lorddetson.piplayer.identification.acoustid.AcoustIdException.InvalidApiKey::class.java)
        assertThat(services.status(ProviderId.ACOUSTID).value).isEqualTo(ProviderStatus.Stopped(StopReason.INVALID_API_KEY))
        assertThat(acoustIdServer.requestCount).isEqualTo(1)
    }

    @Test
    fun `neither the services nor their fingerprint stage show the key`() = runTest {
        val services = services(acoustIdApiKey = key)
        val stage = services.fingerprintStage as FingerprintStage.Available
        assertThat(services.toString()).doesNotContain(key)
        assertThat(stage.toString()).doesNotContain(key)
        assertThat(stage.acoustId.toString()).doesNotContain(key)
    }

    @Test
    fun `the app's services take the build's key and treat an empty one as none`() {
        val connectivity = TestServices.FakeConnectivity()
        assertThat(
            IdentificationServices.production(TestServices.USER_AGENT, connectivity, "", persistedGate, noLibrary, deviceState)
                .fingerprintStage,
        ).isEqualTo(FingerprintStage.Unavailable(UnavailableReason.NoKey))
        assertThat(
            IdentificationServices.production(TestServices.USER_AGENT, connectivity, key, persistedGate, noLibrary, deviceState)
                .fingerprintStage,
        ).isInstanceOf(FingerprintStage.Available::class.java)
    }

    @Test
    fun `the app's services keep their caches in the library database and open it only when a cache is used`() {
        val services =
            IdentificationServices.production(
                TestServices.USER_AGENT, TestServices.FakeConnectivity(), "", persistedGate, noLibrary, deviceState,
            )
        assertThat(services.releaseCache).isInstanceOf(RoomReleaseCache::class.java)
        assertThat(services.failureCache).isInstanceOf(RoomFailureCache::class.java)
        assertThat(services.coverArtArchive).isNotNull()
        assertThat(services.releases).isNotNull()
        assertThat(libraryUses).isZero()
        // The folder hint reads the library's decided and tagged releases (M3 step 16), also only when it is used.
        assertThat(services.folderReleaseHint).isInstanceOf(LibraryFolderReleaseHint::class.java)
    }

    @Test
    fun `services made for tests keep their caches in memory`() = runTest {
        val services = services()
        assertThat(services.releaseCache).isInstanceOf(InMemoryReleaseCache::class.java)
        assertThat(services.failureCache).isInstanceOf(InMemoryFailureCache::class.java)
        assertThat(services.folderReleaseHint).isSameAs(NoFolderReleaseHint)
        assertThat(services.releaseCache).isNotSameAs(services().releaseCache)
        assertThat(services.failureCache).isNotSameAs(services().failureCache)
    }

    @Test
    fun `the release choice of the services reads their release cache before it looks a release up`() = runTest {
        val services = services()
        val id = "11111111-2222-3333-4444-555555555555"
        services.releaseCache.put(ReleaseFixtures.release(id))
        assertThat(services.releases).isSameAs(services.releases)
        assertThat(services.releases.release(id.uppercase())!!.id).isEqualTo(id)
        assertThat(musicBrainzServer.requestCount).isZero()
    }

    @Test
    fun `the Cover Art Archive client records its failures in the services' failure cache and a folder hint is bound`() = runTest {
        caaServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(404).build()
        }
        val failures = InMemoryFailureCache()
        val hint = FolderReleaseHint { setOf("22222222-3333-4444-5555-666666666666") }
        val caaBase = caaServer.url("/")
        val services = IdentificationServices.create(
            userAgent = TestServices.USER_AGENT,
            gate = TestServices.openGate(),
            connectivity = TestServices.FakeConnectivity(),
            baseUrls = TestServices.baseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/"), caaBase),
            timeSource = testTimeSource,
            failureCache = failures,
            folderReleaseHint = hint,
        )
        val source = CoverSource(CoverEntity.RELEASE, "11111111-2222-3333-4444-555555555555")

        assertThat(services.coverArtArchive.cover(source, CoverSize.ORIGINAL)).isEqualTo(CaaResult.NotFound)
        assertThat(services.failureCache).isSameAs(failures)
        assertThat(failures.get(CoverUrls.image(caaBase, source, CoverSize.ORIGINAL).toString())?.kind)
            .isEqualTo(FailureKind.NOT_FOUND)
        assertThat(services.folderReleaseHint).isSameAs(hint)
    }

    @Test
    fun `the release choice of the services asks their folder hint and suggests the release it confirms`() = runTest {
        val log = RecordingRequestLog { testScheduler.currentTime }
        musicBrainzServer.dispatcher = log.wrap(musicBrainz.withHarborLights())
        // Without the hint the ranking suggests HARBOR_RELEASE; the hint confirms the other edition of the recording.
        val live = "88888888-8888-4888-8888-888888888888"
        val folders = mutableListOf<String?>()
        val hint = FolderReleaseHint { folder -> folders += folder; setOf(live) }
        val services = IdentificationServices.create(
            userAgent = TestServices.USER_AGENT,
            gate = TestServices.openGate(),
            connectivity = TestServices.FakeConnectivity(),
            baseUrls = TestServices.baseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/")),
            timeSource = testTimeSource,
            folderReleaseHint = hint,
        )
        val recording = FakeMusicBrainz.HARBOR_RECORDING
        val ranking = Ranking(
            listOf(match(candidate(recording, "Paper Lanterns", "The Quiet Harbors feat. Ada Vale", 214_000), 0.97)),
            0.97,
            Band.HIGH,
        )

        val choice = services.releases.choose(ranking, ReleaseRequest(durationMs = 214_000, folder = "Music/Harbors"))

        assertThat(folders).containsExactly("Music/Harbors")
        assertThat(choice.ranked.getValue(recording).first().release.id).isEqualTo(live)
        // The suggestion is looked up for the top of that ranking: the confirmed release, not HARBOR_RELEASE.
        assertThat(log.entries.map { it.path.substringBefore('?') }.filter { it.startsWith("/ws/2/release/") })
            .containsExactly("/ws/2/release/$live")
    }
}
