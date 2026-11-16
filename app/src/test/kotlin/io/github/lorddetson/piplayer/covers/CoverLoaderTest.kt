package io.github.lorddetson.piplayer.covers

import io.github.lorddetson.piplayer.fakes.FakeCaa
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.caa.CoverArtArchiveClient
import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.identification.caa.CoverUrls
import io.github.lorddetson.piplayer.identification.caa.FailureTtlPolicy
import io.github.lorddetson.piplayer.identification.caa.InMemoryFailureCache
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.OnlineConsent
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Dispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Loading covers through their sources and the caches (docs/library.md §13.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverLoaderTest {

    @TempDir
    lateinit var dir: File

    private val song = CoverFile(1, "content://media/1", 10, 20, embedded = true)
    private val own = CoverStep.Own("own.jpg")
    private val embedded = CoverStep.Embedded(song)
    private val thumbnail = CoverStep.Thumbnail(song)

    /** Pictures are text here: the step's key and the size it was decoded for. */
    private object Codec : ImageCodec<String> {
        override fun encode(image: String) = image.toByteArray()
        override fun decode(bytes: ByteArray, size: Int) = String(bytes)
        override fun weight(image: String) = image.length.toLong()
    }

    /** Sources that give what [pictures] holds for a step, count their reads, and may throw or wait. */
    private class Sources(val pictures: MutableMap<CoverStep, String?> = mutableMapOf()) : CoverSources<String> {
        val reads = mutableListOf<CoverStep>()
        val failing = mutableSetOf<CoverStep>()
        var gate: CompletableDeferred<Unit>? = null
        /** Holds the loads of remote steps, cancellably, until completed. */
        var remoteGate: CompletableDeferred<Unit>? = null
        /** How long a load takes, cancellably, in the test's virtual time. */
        var delayMs = 0L

        override suspend fun load(step: CoverStep, size: Int): String? {
            reads += step
            // Not cancellable, as a blocking call is not: it returns after the load was cancelled.
            gate?.let { withContext(NonCancellable) { it.await() } }
            if (step.remote) remoteGate?.await()
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            if (step in failing) throw IOException("cannot open now")
            return pictures[step]?.let { "$it@$size" }
        }
    }

    private var steps = listOf<CoverStep>(own, embedded, thumbnail)
    private var plans = 0
    private val sources = Sources()
    private val disk by lazy { CoverDiskCache(dir) }

    private fun TestScope.loader(disk: CoverDiskCache? = this@CoverLoaderTest.disk, memory: Long = 1_000) = CoverLoader(
        plan = { plans++; steps },
        sources = sources,
        codec = Codec,
        disk = disk,
        memoryWeight = memory,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private val request = CoverRequest.Track(1)

    @Test
    fun theFirstSourceWithAPictureWins() = runTest {
        sources.pictures[embedded] = "E"
        sources.pictures[thumbnail] = "T"

        assertThat(loader().load(request, 150)).isEqualTo("E@192")
        assertThat(sources.reads).containsExactly(own, embedded)
    }

    @Test
    fun aSourceWithoutAPictureIsRememberedOnDiskAndInMemory() = runTest {
        sources.pictures[thumbnail] = "T"
        val loader = loader()
        loader.load(request, 150)
        sources.reads.clear()

        assertThat(loader.load(request, 150)).isEqualTo("T@192")
        assertThat(sources.reads).isEmpty()

        // Another process start: memory is empty, the disk still knows. A missing own copy is only looked for again.
        assertThat(loader().load(request, 150)).isEqualTo("T@192")
        assertThat(sources.reads).containsExactly(own)
    }

    @Test
    fun aDiskHitReadsNoSource() = runTest {
        sources.pictures[embedded] = "E"
        loader().load(request, 100)
        sources.reads.clear()

        val other = loader()

        assertThat(other.peek(request, 100)).isNull()
        assertThat(other.load(request, 100)).isEqualTo("E@128")
        assertThat(sources.reads).containsExactly(own)
        assertThat(other.peek(request, 100)).isEqualTo("E@128")
    }

    @Test
    fun aMemoryHitNeedsNoPlan() = runTest {
        sources.pictures[own] = "O"
        val loader = loader(disk = null)
        loader.load(request, 96)
        val plansBefore = plans

        assertThat(loader.peek(request, 90)).isEqualTo("O@96")
        assertThat(loader.load(request, 96)).isEqualTo("O@96")
        assertThat(plans).isEqualTo(plansBefore)
    }

    @Test
    fun noPictureAnywhereIsNullAndRemembered() = runTest {
        val loader = loader()

        assertThat(loader.load(request, 96)).isNull()
        sources.reads.clear()
        assertThat(loader.load(request, 96)).isNull()
        assertThat(sources.reads).isEmpty()
    }

    @Test
    fun aRequestWithoutSourcesIsNotRemembered() = runTest {
        steps = emptyList()
        val loader = loader()
        assertThat(loader.load(request, 96)).isNull()

        // The library has learnt of the file since.
        steps = listOf(embedded)
        sources.pictures[embedded] = "E"
        assertThat(loader.load(request, 96)).isEqualTo("E@96")
    }

    @Test
    fun aSourceThatFailsIsSkippedButNotRemembered() = runTest {
        sources.pictures[embedded] = "E"
        sources.pictures[thumbnail] = "T"
        sources.failing += embedded
        val loader = loader(memory = 0)

        assertThat(loader.load(request, 96)).isEqualTo("T@96")
        sources.failing.clear()
        sources.reads.clear()
        assertThat(loader.load(request, 96)).isEqualTo("E@96")
        // The failure was not remembered.
        assertThat(sources.reads).containsExactly(own, embedded)
    }

    @Test
    fun aChangeOfOwnCoversLoadsAgain() = runTest {
        sources.pictures[embedded] = "E"
        val loader = loader()
        assertThat(loader.load(request, 96)).isEqualTo("E@96")

        sources.pictures[own] = "O"
        loader.invalidate()

        assertThat(loader.peek(request, 96)).isNull()
        assertThat(loader.load(request, 96)).isEqualTo("O@96")
    }

    @Test
    fun aLoadThatEndsAfterAChangeOfOwnCoversIsNotKeptInMemory() = runTest {
        sources.pictures[embedded] = "E"
        sources.gate = CompletableDeferred()
        val loader = loader(disk = null)
        val load = async { loader.load(request, 96) }
        advanceUntilIdle()

        loader.invalidate()
        sources.gate!!.complete(Unit)
        load.await()

        assertThat(loader.peek(request, 96)).isNull()
    }

    @Test
    fun aCancelledLoadStoresNothing() = runTest {
        sources.pictures[embedded] = "E"
        sources.gate = CompletableDeferred()
        val loader = loader()
        val load = async { loader.load(request, 96) }
        advanceUntilIdle()

        load.cancel()
        sources.gate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(loader.peek(request, 96)).isNull()
        assertThat(disk.read(own.key + "@96")).isNull()
        assertThat(disk.read(embedded.key + "@96")).isNull()
    }

    @Test
    fun aLibraryThatCannotBeReadGivesNoCoverAndNothingIsRemembered() = runTest {
        sources.pictures[embedded] = "E"
        var failing = true
        val loader = CoverLoader(
            plan = { if (failing) throw IllegalStateException("closed for a rebuild") else steps },
            sources = sources,
            codec = Codec,
            disk = disk,
            memoryWeight = 1_000,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertThat(loader.load(request, 96)).isNull()
        failing = false
        assertThat(loader.load(request, 96)).isEqualTo("E@96")
    }

    @Test
    fun aMissingThumbnailIsNotRememberedOnDisk() = runTest {
        // An image put into the folder later changes neither the file's size nor its time.
        loader().load(request, 96)
        sources.reads.clear()

        sources.pictures[thumbnail] = "T"

        assertThat(disk.read(thumbnail.key + "@96")).isNull()
        assertThat(loader().load(request, 96)).isEqualTo("T@96")
        assertThat(sources.reads).containsExactly(own, thumbnail)
    }

    @Test
    fun clearingForgetsMemoryAndDisk() = runTest {
        sources.pictures[embedded] = "E"
        val loader = loader()
        loader.load(request, 96)
        sources.reads.clear()

        loader.clear()

        assertThat(loader.peek(request, 96)).isNull()
        assertThat(disk.size).isZero()
        assertThat(loader.load(request, 96)).isEqualTo("E@96")
        assertThat(sources.reads).containsExactly(own, embedded)
    }

    /** [Codec] that counts its decodes and encodes. */
    private class CountingCodec : ImageCodec<String> {
        var decodes = 0
        var encodes = 0
        /** A picture this codec cannot encode. */
        var refuse: String? = null
        override fun encode(image: String): ByteArray? { encodes++; return if (image == refuse) null else Codec.encode(image) }
        override fun decode(bytes: ByteArray, size: Int): String { decodes++; return Codec.decode(bytes, size) }
        override fun weight(image: String) = Codec.weight(image)
        override fun isEncoded(bytes: ByteArray) = !String(bytes).startsWith("junk")
    }

    private fun TestScope.counting(codec: CountingCodec, plan: suspend () -> List<CoverStep> = { plans++; steps }) = CoverLoader(
        plan = { plan() },
        sources = sources,
        codec = codec,
        disk = disk,
        memoryWeight = 1_000,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun aControllersCoverIsEncodedOnceAndThenServedFromTheDiskAsStored() = runTest {
        sources.pictures[embedded] = "E"
        val codec = CountingCodec()
        val loader = counting(codec)

        assertThat(loader.encoded(request, 512)?.let(::String)).isEqualTo("E@512")
        assertThat(codec.encodes).isEqualTo(1)
        assertThat(disk.read(embedded.key + "@512")).isInstanceOf(Cached.Picture::class.java)
        sources.reads.clear()

        // Another process: the bytes come from the disk, not decoded and encoded again, and no source is read.
        val again = counting(codec)
        assertThat(again.encoded(request, 512)?.let(::String)).isEqualTo("E@512")
        assertThat(codec.decodes).isZero()
        assertThat(codec.encodes).isEqualTo(1)
        assertThat(sources.reads).containsExactly(own)
    }

    @Test
    fun aControllersCoverSharesTheDiskWithTheApp() = runTest {
        sources.pictures[thumbnail] = "T"
        loader().load(request, 512)
        sources.reads.clear()
        val codec = CountingCodec()

        assertThat(counting(codec).encoded(request, 512)?.let(::String)).isEqualTo("T@512")
        assertThat(sources.reads).containsExactly(own)
        assertThat(codec.encodes).isZero()
    }

    @Test
    fun aControllersCoverWithoutAPictureIsNullAndAnEmbeddedAbsenceIsRemembered() = runTest {
        val loader = counting(CountingCodec())

        assertThat(loader.encoded(request, 512)).isNull()
        assertThat(disk.read(embedded.key + "@512")).isEqualTo(Cached.Nothing)
        assertThat(disk.read(thumbnail.key + "@512")).isNull()
    }

    @Test
    fun aControllersCoverIsNullWhileTheLibraryCannotBeReadOrASourceFails() = runTest {
        sources.pictures[embedded] = "E"
        var failing = true
        val loader = counting(CountingCodec()) { if (failing) throw IllegalStateException("closed for a rebuild") else steps }
        assertThat(loader.encoded(request, 512)).isNull()

        failing = false
        sources.failing += embedded
        sources.pictures[thumbnail] = "T"
        assertThat(loader.encoded(request, 512)?.let(::String)).isEqualTo("T@512")
        sources.failing.clear()
        assertThat(loader.encoded(request, 512)?.let(::String)).isEqualTo("E@512")
    }

    @Test
    fun aControllersTimeOnlyStartsOnceItsLoadHasAPermit() = runTest {
        // The app's own loads hold every permit for a while; the controller's waits in line, then loads at once.
        sources.pictures[embedded] = "E"
        sources.gate = CompletableDeferred()
        val loader = counting(CountingCodec())
        val busy = (1..CoverLoader.PARALLEL_LOADS).map { async { loader.load(CoverRequest.Track(100L + it), 96) } }
        val controller = async { loader.encoded(request, 512, timeoutMs = 100) }
        advanceTimeBy(1_000)

        sources.gate!!.complete(Unit)
        busy.forEach { it.await() }

        assertThat(controller.await()?.let(::String)).isEqualTo("E@512")
    }

    @Test
    fun aControllersLoadThatTakesTooLongIsGivenUp() = runTest {
        sources.pictures[embedded] = "E"
        sources.delayMs = 20_000
        val loader = counting(CountingCodec())

        assertThat(loader.encoded(request, 512, timeoutMs = 10_000)).isNull()
        assertThat(currentTime).isLessThan(20_000)
        // Nothing of it was stored.
        assertThat(disk.read(embedded.key + "@512")).isNull()
    }

    @Test
    fun aControllersCoverNoLongerWantedLoadsNothing() = runTest {
        sources.pictures[embedded] = "E"
        val loader = counting(CountingCodec())

        assertThat(loader.encoded(request, 512, wanted = { false })).isNull()
        assertThat(plans).isZero()
        assertThat(sources.reads).isEmpty()
    }

    @Test
    fun aCoverMemoryKnowsToBeNoneNeedsNoPlanForAController() = runTest {
        val loader = counting(CountingCodec())
        assertThat(loader.load(request, 512)).isNull()
        val before = plans
        sources.reads.clear()

        assertThat(loader.encoded(request, 512)).isNull()
        assertThat(plans).isEqualTo(before)
        assertThat(sources.reads).isEmpty()
    }

    @Test
    fun aDiskEntryThatIsNoPictureOfTheCodecIsLoadedAgain() = runTest {
        sources.pictures[embedded] = "E"
        disk.write(embedded.key + "@512", "junk".toByteArray())
        val codec = CountingCodec()

        assertThat(counting(codec).encoded(request, 512)?.let(::String)).isEqualTo("E@512")
        assertThat(disk.read(embedded.key + "@512").let { (it as Cached.Picture).bytes.let(::String) }).isEqualTo("E@512")
    }

    @Test
    fun aPictureThatCannotBeEncodedEndsTheSearchAsItWouldInTheApp() = runTest {
        sources.pictures[embedded] = "E"
        sources.pictures[thumbnail] = "T"
        val codec = CountingCodec().apply { refuse = "E@512" }

        assertThat(counting(codec).encoded(request, 512)).isNull()
        assertThat(sources.reads).containsExactly(own, embedded)
    }

    @Test
    fun theGenerationRisesWithEveryChange() = runTest {
        val loader = loader()
        val before = loader.generation

        loader.invalidate()
        loader.clear()

        assertThat(loader.generation).isEqualTo(before + 2)
    }

    @Test
    fun nowPlayingsSizeIsNotCached() = runTest {
        sources.pictures[embedded] = "E"
        val loader = loader()
        assertThat(loader.load(request, 1_080)).isEqualTo("E@1536")
        sources.reads.clear()

        assertThat(loader.load(request, 1_080)).isEqualTo("E@1536")
        assertThat(sources.reads).containsExactly(own, embedded)
        assertThat(disk.size).isZero()
    }

    // M3 (docs/library.md §13.3): the decided and the lazy Cover Art Archive steps.

    private val caaStep = CoverStep.Caa(CoverRelease("11111111-1111-4111-8111-111111111111"))

    @Test
    fun aCoverArtArchiveLoadHoldsNoPlaceOfTheLoader() = runTest {
        // Four rows wait for Cover Art Archive; a row with a local picture still loads meanwhile.
        sources.remoteGate = CompletableDeferred()
        sources.pictures[embedded] = "E"
        val loader = CoverLoader(
            plan = { request -> if (request is CoverRequest.Album) listOf(caaStep) else listOf(embedded) },
            sources = sources,
            codec = Codec,
            disk = disk,
            memoryWeight = 1_000,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val waiting = (1..CoverLoader.PARALLEL_LOADS).map { async { loader.load(CoverRequest.Album("a$it"), 96) } }
        advanceUntilIdle()

        val local = async { loader.load(request, 96) }
        advanceUntilIdle()

        assertThat(local.isCompleted).isTrue()
        assertThat(local.await()).isEqualTo("E@96")
        assertThat(waiting.none { it.isCompleted }).isTrue()
        sources.remoteGate!!.complete(Unit)
        waiting.forEach { it.await() }
    }

    @Test
    fun theLocalStepsComeBeforeTheCoverArtArchiveStepAndANonePictureThereIsRememberedInMemoryOnly() = runTest {
        steps = listOf(own, embedded, thumbnail, caaStep)
        val loader = loader()

        assertThat(loader.load(request, 96)).isNull()
        assertThat(sources.reads).containsExactly(own, embedded, thumbnail, caaStep)
        // The failure entry keeps a 404; the disk keeps nothing for the step.
        assertThat(disk.read(caaStep.key + "@96")).isNull()
        sources.reads.clear()
        assertThat(loader.load(request, 96)).isNull()
        assertThat(sources.reads).isEmpty()
    }

    @Test
    fun aCoverArtArchivePictureIsKeptOnDiskUnderItsStep() = runTest {
        steps = listOf(embedded, caaStep)
        sources.pictures[caaStep] = "C"
        loader().load(request, 96)
        sources.reads.clear()

        assertThat(disk.read(caaStep.key + "@96")).isInstanceOf(Cached.Picture::class.java)
        assertThat(loader().load(request, 96)).isEqualTo("C@96")
        assertThat(sources.reads).containsExactly(embedded)
    }

    @Test
    fun oneStepLoadsThroughTheDiskCache() = runTest {
        sources.pictures[caaStep] = "C"
        val loader = loader()

        assertThat(loader.step(caaStep, 250)).isEqualTo("C@256")
        sources.reads.clear()
        assertThat(loader().step(caaStep, 250)).isEqualTo("C@256")
        assertThat(sources.reads).isEmpty()
        assertThat(loader.step(thumbnail, 96)).isNull()
    }

    // The fake Cover Art Archive through the real client, its gate and its limiter of 1 s (TI §5.2, §8).

    @StartStop
    private val caaServer = MockWebServer()

    @StartStop
    private val archiveServer = MockWebServer()

    private val onlineGate = TestServices.openGate()
    private val failures = InMemoryFailureCache()
    private lateinit var log: RecordingRequestLog

    /** Albums by key, each with the release of [mbid]. */
    private class AlbumFacts(val releases: Map<String, CoverRelease>) : CoverFacts {
        override suspend fun file(fileId: Long): CoverFile? = null
        override suspend fun albumFile(albumKey: String): CoverFile? = null
        override suspend fun artistAlbums(artistKey: String): List<ArtistAlbum> = emptyList()
        override suspend fun artistFile(artistKey: String): CoverFile? = null
        override suspend fun albumRelease(albumKey: String) = releases[albumKey]
    }

    private object NoOwn : OwnCoverIndex {
        override fun track(identityId: Long): String? = null
        override fun album(albumKey: String): String? = null
    }

    /** Runs OkHttp's calls on the caller's thread, so that a request leaves before virtual time moves on. */
    private object CallerThread : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private fun mbid(i: Int) = "%08x-0000-4000-8000-%012x".format(i, i)

    /** A loader whose plan is the real one for albums [1..count], each with a release that the fake has images of. */
    private fun TestScope.caaLoader(count: Int, withImages: Boolean = true): CoverLoader<String> {
        log = RecordingRequestLog { testScheduler.currentTime }
        val fake = FakeCaa { archiveServer.url("/") }
        if (withImages) (1..count).forEach { fake.withImages(mbid(it)) }
        caaServer.dispatcher = log.wrap(fake)
        archiveServer.dispatcher = log.wrap(fake.archive)
        val io = StandardTestDispatcher(testScheduler)
        val client = CoverArtArchiveClient(
            http = HttpClients.create(TestServices.USER_AGENT, onlineGate).newBuilder().dispatcher(Dispatcher(CallerThread)).build(),
            limiter = RateLimiter(1.seconds, testTimeSource),
            retryPolicy = RetryPolicy(maxRetries = 3),
            access = TestServices.access(ProviderId.COVER_ART_ARCHIVE, onlineGate),
            failures = failures,
            baseUrl = caaServer.url("/"),
            ioDispatcher = io,
        )
        val caa = CaaCoverSource(client::cover, onlineGate, onlineGate.consent.map { it.allows(OnlineConsent.CURRENT_DISCLOSURE_VERSION) }) { bytes, size ->
            "caa ${bytes.size}@$size"
        }
        val facts = AlbumFacts((1..count).associate { "album$it" to CoverRelease(mbid(it)) })
        return CoverLoader(
            plan = { request -> CoverPlan.of(request, facts, NoOwn, online = onlineGate.isOpen()) },
            sources = { step, size -> if (step is CoverStep.Caa) caa.load(step, size) else sources.load(step, size) },
            codec = Codec,
            disk = disk,
            memoryWeight = 100_000,
            dispatcher = io,
        )
    }

    /** How many of the albums [1..count] have their Cover Art Archive picture on disk at the 96 px step. */
    private fun cachedRows(count: Int) = (1..count).count { disk.read("caa/${mbid(it)}/-@96") is Cached.Picture }

    private val caaHost get() = "${caaServer.hostName}:${caaServer.port}"

    @Test
    fun fiftyAlbumRowsKeepASecondBetweenTheirRequestsAndLoadFront250() = runTest {
        val loader = caaLoader(50)

        val rows = (1..50).map { async { loader.load(CoverRequest.Album("album$it"), 150) } }
        advanceUntilIdle()

        assertThat(rows.map { it.await() }).allSatisfy { assertThat(it).startsWith("caa ").endsWith("@192") }
        val requests = log.to(caaHost)
        assertThat(requests).hasSize(50)
        assertThat(requests).allSatisfy { assertThat(it.path).endsWith("/front-250") }
        val times = requests.map { it.timeMs }.sorted()
        assertThat(times.zipWithNext { a, b -> b - a }).allSatisfy { assertThat(it).isGreaterThanOrEqualTo(1_000) }
    }

    @Test
    fun rowsCancelledWhileTheyWaitSendNothing() = runTest {
        val loader = caaLoader(10)
        val rows = (1..10).map { async { loader.load(CoverRequest.Album("album$it"), 96) } }
        advanceTimeBy(500)
        assertThat(log.to(caaHost)).hasSize(1)

        // The rows leave the screen.
        rows.filterNot { it.isCompleted }.forEach { it.cancel() }
        advanceUntilIdle()

        assertThat(log.to(caaHost)).hasSize(1)
        assertThat(cachedRows(10)).isEqualTo(1)
    }

    @Test
    fun aLiveFailureEntrySendsNothing() = runTest {
        val loader = caaLoader(1)
        val original = CoverUrls.image(caaServer.url("/"), CoverSource(CoverEntity.RELEASE, mbid(1)), CoverSize.ORIGINAL)
        failures.put(original.toString(), FailureTtlPolicy(Clock.systemUTC()).notFound())

        assertThat(loader.load(CoverRequest.Album("album1"), 96)).isNull()
        assertThat(log.entries).isEmpty()
    }

    @Test
    fun aMissingCoverIsAskedOnceAndThenAnsweredByTheFailureEntry() = runTest {
        val loader = caaLoader(1, withImages = false)

        assertThat(loader.load(CoverRequest.Album("album1"), 96)).isNull()
        assertThat(log.to(caaHost)).hasSize(1)
        assertThat(cachedRows(1)).isZero()
        assertThat(disk.read("caa/${mbid(1)}/-@96")).isNull()

        // Another process: memory is empty, the failure entry still answers.
        assertThat(caaLoader(1, withImages = false).load(CoverRequest.Album("album1"), 96)).isNull()
        assertThat(log.entries).isEmpty()
    }

    @Test
    fun withOnlineLookupsOffNothingIsSent() = runTest {
        onlineGate.set(OnlineConsent.NONE)
        val loader = caaLoader(3)

        val rows = (1..3).map { async { loader.load(CoverRequest.Album("album$it"), 96) } }
        advanceUntilIdle()

        assertThat(rows.map { it.await() }).containsOnlyNulls()
        assertThat(log.entries).isEmpty()
    }

    @Test
    fun turningOnlineLookupsOffDuringTheLoadsCancelsThemSendsNothingMoreAndStoresNothing() = runTest {
        val loader = caaLoader(5)
        val rows = (1..5).map { async { loader.load(CoverRequest.Album("album$it"), 96) } }
        advanceTimeBy(500)
        assertThat(log.to(caaHost)).hasSize(1)

        onlineGate.set(OnlineConsent.NONE)
        advanceTimeBy(1)

        // At once, not at their turns at the limiter.
        assertThat(rows.all { it.isCompleted }).isTrue()
        assertThat(rows.map { it.await() }.count { it != null }).isEqualTo(1)
        advanceUntilIdle()
        assertThat(log.to(caaHost)).hasSize(1)
        assertThat(cachedRows(5)).isEqualTo(1)
        assertThat((1..5).count { loader.peek(CoverRequest.Album("album$it"), 96) != null }).isEqualTo(1)

        // Nothing of the cancelled loads was remembered: turned on again, they load.
        onlineGate.set(OnlineConsent(enabled = true, disclosureVersion = OnlineConsent.CURRENT_DISCLOSURE_VERSION))
        val again = (1..5).map { async { loader.load(CoverRequest.Album("album$it"), 96) } }
        advanceUntilIdle()
        assertThat(again.map { it.await() }).doesNotContainNull()
        assertThat(log.to(caaHost)).hasSize(5)
    }
}
