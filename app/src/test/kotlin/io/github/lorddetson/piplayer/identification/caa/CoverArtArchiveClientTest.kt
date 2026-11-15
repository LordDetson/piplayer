package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.fakes.FakeCaa
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.provider.ProviderAccess
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStatusStore
import io.github.lorddetson.piplayer.identification.provider.ProviderStoppedException
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.library.details.PictureSize
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.HttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * TI §11.1 "Cover URLs" on the fake Cover Art Archive and its image host: redirects with the User-Agent on each hop,
 * the fallback after a missing thumbnail, failure entries, the 503 pause, the release index and the gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverArtArchiveClientTest {

    @StartStop
    private val server = MockWebServer()

    /** The image host, like archive.org. */
    @StartStop
    private val archive = MockWebServer()

    private val clock = TestClock()
    private val policy = FailureTtlPolicy(clock)
    private val failures = InMemoryFailureCache()
    private lateinit var caa: FakeCaa
    private lateinit var log: RecordingRequestLog

    private val base: HttpUrl get() = server.url("/")

    /** The fake services, with a request log on virtual time. */
    private fun TestScope.fake(configure: FakeCaa.() -> Unit = {}): FakeCaa {
        log = RecordingRequestLog { testScheduler.currentTime }
        caa = FakeCaa { archive.url("/") }.apply(configure)
        server.dispatcher = log.wrap(caa)
        archive.dispatcher = log.wrap(caa.archive)
        return caa
    }

    /** [interval] far below the backoff, so a wait a test sees comes from a pause and not from the spacing. */
    private fun TestScope.client(
        interval: Duration = 10.milliseconds,
        gate: OnlineGate = TestServices.openGate(),
        statuses: ProviderStatusStore = ProviderStatusStore(),
        maxRetries: Int = 3,
        maxImageBytes: Long = CoverArtArchiveClient.MAX_IMAGE_BYTES,
        failures: FailureCache = this@CoverArtArchiveClientTest.failures,
    ) = CoverArtArchiveClient(
        http = HttpClients.create(TestServices.USER_AGENT, gate),
        limiter = RateLimiter(interval, testTimeSource),
        retryPolicy = RetryPolicy(maxRetries = maxRetries, random = Random(1)),
        access = ProviderAccess(ProviderId.COVER_ART_ARCHIVE, gate, TestServices.FakeConnectivity(), statuses),
        failures = failures,
        baseUrl = base,
        clock = clock,
        backoff = RetryPolicy(maxRetries = Int.MAX_VALUE, random = Random(1)),
        maxImageBytes = maxImageBytes,
    )

    private fun release(mbid: String) = CoverSource(CoverEntity.RELEASE, mbid)

    private fun key(mbid: String, size: CoverSize, entity: CoverEntity = CoverEntity.RELEASE) =
        CoverUrls.image(base, CoverSource(entity, mbid), size).toString()

    private fun <T> CaaResult<T>.found(): T {
        assertThat(this).isInstanceOf(CaaResult.Found::class.java)
        @Suppress("UNCHECKED_CAST")
        return (this as CaaResult.Found<T>).value
    }

    private val caaHost get() = "${server.hostName}:${server.port}"
    private val archiveHost get() = "${archive.hostName}:${archive.port}"

    @Test
    fun `a cover comes through the redirect with the User-Agent on each hop`() = runTest {
        fake { withImages(RELEASE) }

        val image = client().cover(release(RELEASE), CoverSize.DISPLAY).found()

        assertThat(image.bytes).isEqualTo(FakeCaa.FRONT_500.fixture.bytes())
        assertThat(image.format).isEqualTo(ImageFormat.JPEG)
        assertThat(image.size).isEqualTo(PictureSize(500, 500))
        assertThat(image.original).isFalse()
        // The configured base address is used as it is: a local cleartext fake here.
        assertThat(image.requested.toString()).isEqualTo("http://$caaHost/release/$RELEASE/front-500")
        assertThat(image.url.port).isEqualTo(archive.port)
        assertThat(log.entries.map { it.host }).containsExactly(caaHost, archiveHost)
        assertThat(log.entries.map { it.userAgent }).containsOnly(TestServices.USER_AGENT)
    }

    @Test
    fun `each size asks for its own image, and the original is marked as such`() = runTest {
        fake { withImages(RELEASE) }
        val client = client()

        val sizes = listOf(CoverSize.LIST, CoverSize.EMBED, CoverSize.ORIGINAL).map { size ->
            client.cover(release(RELEASE), size).found().let { it.size to it.original }
        }

        assertThat(sizes).containsExactly(
            PictureSize(250, 250) to false,
            PictureSize(1200, 1200) to false,
            PictureSize(1500, 1400) to true,
        )
        assertThat(log.to(caaHost).map { it.path }).containsExactly(
            "/release/$RELEASE/front-250",
            "/release/$RELEASE/front-1200",
            "/release/$RELEASE/front",
        )
    }

    @Test
    fun `a release group cover comes from the release group path`() = runTest {
        fake { withImages(GROUP) }

        val image = client().cover(CoverSource(CoverEntity.RELEASE_GROUP, GROUP), CoverSize.DISPLAY).found()

        assertThat(image.size).isEqualTo(PictureSize(500, 500))
        assertThat(log.to(caaHost).single().path).isEqualTo("/release-group/$GROUP/front-500")
    }

    @Test
    fun `a release without a front image is followed by its release group, marked as another edition`() = runTest {
        fake { withImages(GROUP) }

        val image = client().cover(CoverUrls.sources(UNKNOWN, GROUP, flags = null), CoverSize.DISPLAY).found()

        assertThat(image.source).isEqualTo(CoverSource(CoverEntity.RELEASE_GROUP, GROUP))
        assertThat(image.anotherEdition).isTrue()
        assertThat(image.size).isEqualTo(PictureSize(500, 500))
        assertThat(log.to(caaHost).map { it.path }).containsExactly("/release/$UNKNOWN/front-500", "/release-group/$GROUP/front-500")
    }

    @Test
    fun `a release with a front image is not followed by its release group`() = runTest {
        fake { withImages(RELEASE).withImages(GROUP) }

        val image = client().cover(CoverUrls.sources(RELEASE, GROUP, flags = null), CoverSize.DISPLAY).found()

        assertThat(image.source).isEqualTo(CoverSource(CoverEntity.RELEASE, RELEASE))
        assertThat(image.anotherEdition).isFalse()
        assertThat(log.to(caaHost).map { it.path }).containsExactly("/release/$RELEASE/front-500")
    }

    @Test
    fun `a busy release does not ask its release group`() = runTest {
        fake {
            busy(BUSY, retryAfterSeconds = 1)
            withImages(GROUP)
        }

        val answer = client().cover(CoverUrls.sources(BUSY, GROUP, flags = null), CoverSize.DISPLAY)

        assertThat(answer).isInstanceOf(CaaResult.Busy::class.java)
        assertThat(log.to(caaHost).map { it.path }).containsExactly("/release/$BUSY/front-500")
    }

    @Test
    fun `without any source there is no cover and nothing is sent`() = runTest {
        fake()
        assertThat(client().cover(emptyList(), CoverSize.DISPLAY)).isEqualTo(CaaResult.NotFound)
        assertThat(log.size).isZero()
    }

    @Test
    fun `a missing front-1200 after the redirect falls back to the original and is not asked for again`() = runTest {
        fake { withoutThumbnails(RELEASE, "front-1200") }
        val client = client()

        val image = client.cover(release(RELEASE), CoverSize.EMBED).found()

        assertThat(image.bytes).isEqualTo(FakeCaa.ORIGINAL.fixture.bytes())
        assertThat(image.original).isTrue()
        assertThat(image.size).isEqualTo(PictureSize(1500, 1400))
        assertThat(image.requested.toString()).isEqualTo(key(RELEASE, CoverSize.ORIGINAL))
        assertThat(log.entries.map { it.host to it.path.substringAfterLast('/') }).containsExactly(
            caaHost to "front-1200",
            archiveHost to "mbid-$RELEASE-1234567890_thumb1200.jpg",
            caaHost to "front",
            archiveHost to "mbid-$RELEASE-1234567890.jpg",
        )
        assertThat(failures.get(key(RELEASE, CoverSize.EMBED))).isEqualTo(policy.notFound())
        assertThat(failures.get(key(RELEASE, CoverSize.ORIGINAL))).isNull()

        // The 404 entry of the thumbnail sends the next request straight to the original.
        assertThat(client.cover(release(RELEASE), CoverSize.EMBED).found().original).isTrue()
        assertThat(log.to(caaHost).map { it.path.substringAfterLast('/') }).containsExactly("front-1200", "front", "front")
    }

    @Test
    fun `a 404 after the redirect for the original too gives not found and records both addresses`() = runTest {
        fake { missingAfterRedirect(RELEASE) }
        val client = client()

        assertThat(client.cover(release(RELEASE), CoverSize.DISPLAY)).isEqualTo(CaaResult.NotFound)
        assertThat(log.size).isEqualTo(4)
        assertThat(failures.get(key(RELEASE, CoverSize.DISPLAY))?.kind).isEqualTo(FailureKind.NOT_FOUND)
        assertThat(failures.get(key(RELEASE, CoverSize.ORIGINAL))?.kind).isEqualTo(FailureKind.NOT_FOUND)

        assertThat(client.cover(release(RELEASE), CoverSize.DISPLAY)).isEqualTo(CaaResult.NotFound)
        assertThat(log.size).isEqualTo(4)
    }

    @Test
    fun `a 404 from CAA itself does not ask for the original and records it too`() = runTest {
        fake()
        val client = client()

        assertThat(client.cover(release(UNKNOWN), CoverSize.LIST)).isEqualTo(CaaResult.NotFound)
        assertThat(log.entries.map { it.path }).containsExactly("/release/$UNKNOWN/front-250")
        assertThat(failures.get(key(UNKNOWN, CoverSize.LIST))).isEqualTo(policy.notFound())
        assertThat(failures.get(key(UNKNOWN, CoverSize.ORIGINAL))).isEqualTo(policy.notFound())

        assertThat(client.cover(release(UNKNOWN), CoverSize.DISPLAY)).isEqualTo(CaaResult.NotFound)
        assertThat(client.cover(release(UNKNOWN), CoverSize.EMBED)).isEqualTo(CaaResult.NotFound)
        assertThat(client.cover(release(UNKNOWN), CoverSize.ORIGINAL)).isEqualTo(CaaResult.NotFound)
        // No thumbnail exists without the original, so the original's entry answers every other size.
        assertThat(log.entries.map { it.path }).containsExactly("/release/$UNKNOWN/front-250")
    }

    @Test
    fun `every failure entry is read and written off the caller's thread, the original's entry after a CAA 404 too`() = runTest {
        fake { withImages(RELEASE) }
        val caller = Thread.currentThread()
        val onCaller = mutableListOf<String>()
        val watched = object : FailureCache {
            private fun check(call: String) {
                if (Thread.currentThread() == caller) synchronized(onCaller) { onCaller += call }
            }

            override fun get(key: String): FailureEntry? = failures.get(key).also { check("get $key") }

            override fun put(key: String, entry: FailureEntry) {
                check("put $key")
                failures.put(key, entry)
            }

            override fun remove(key: String) {
                check("remove $key")
                failures.remove(key)
            }
        }
        val client = client(failures = watched)

        // A 404 from CAA itself for the thumbnail, which records the original's address as well.
        assertThat(client.cover(release(UNKNOWN), CoverSize.LIST)).isEqualTo(CaaResult.NotFound)
        assertThat(failures.get(key(UNKNOWN, CoverSize.ORIGINAL))).isEqualTo(policy.notFound())
        client.cover(release(RELEASE), CoverSize.LIST).found()

        // The app keeps its entries in library.db, so none of these may block a caller on the main thread.
        assertThat(onCaller).isEmpty()
    }

    @Test
    fun `a 404 entry of a front original answers every thumbnail size, and an expired one lets them go`() = runTest {
        fake { withImages(RELEASE) }
        val client = client()
        failures.put(key(RELEASE, CoverSize.ORIGINAL), policy.notFound())

        for (size in listOf(CoverSize.LIST, CoverSize.DISPLAY, CoverSize.EMBED)) {
            assertThat(client.cover(release(RELEASE), size)).isEqualTo(CaaResult.NotFound)
        }
        assertThat(log.size).isZero()

        clock.advance(FailureTtlPolicy.NOT_FOUND_TTL)
        assertThat(client.cover(release(RELEASE), CoverSize.LIST).found().size).isEqualTo(PictureSize(250, 250))
    }

    @Test
    fun `a 503 entry of a front original does not hold the thumbnails back`() = runTest {
        fake { withImages(RELEASE) }
        failures.put(key(RELEASE, CoverSize.ORIGINAL), policy.busy(null))

        assertThat(client().cover(release(RELEASE), CoverSize.LIST).found().size).isEqualTo(PictureSize(250, 250))
    }

    @Test
    fun `a 503 is not retried, pauses the CAA limiter and records a failure for 5 minutes`() = runTest {
        fake {
            busy(BUSY, retryAfterSeconds = 30)
            withImages(RELEASE)
        }
        val client = client()

        val answer = client.cover(release(BUSY), CoverSize.LIST)

        assertThat(answer).isEqualTo(CaaResult.Busy(clock.after(5.minutes)))
        assertThat(failures.get(key(BUSY, CoverSize.LIST)))
            .isEqualTo(FailureEntry(FailureKind.BUSY, clock.now, clock.after(5.minutes), busyInARow = 1))
        assertThat(failures.get(key(BUSY, CoverSize.ORIGINAL))).isNull()

        client.cover(release(RELEASE), CoverSize.LIST).found()
        assertThat(log.to(caaHost).map { it.path }).containsExactly("/release/$BUSY/front-250", "/release/$RELEASE/front-250")
        // The next request of any address waits out the Retry-After.
        assertThat(log.to(caaHost)[1].timeMs).isGreaterThanOrEqualTo(30_000)
    }

    @Test
    fun `a 503 with a Retry-After over 120 s pauses every CAA request for it, and later calls fail without a request`() = runTest {
        fake {
            busy(BUSY, retryAfterSeconds = 600)
            withImages(RELEASE)
        }
        val client = client()

        assertThat(client.cover(release(BUSY), CoverSize.LIST)).isEqualTo(CaaResult.Busy(clock.after(5.minutes)))
        assertThat(failures.get(key(BUSY, CoverSize.LIST)))
            .isEqualTo(FailureEntry(FailureKind.BUSY, clock.now, clock.after(5.minutes), busyInARow = 1))

        val error = runCatching { client.cover(release(RELEASE), CoverSize.LIST) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CaaException.Paused::class.java)
        assertThat((error as CaaException.Paused).remaining).isBetween(599.seconds, 600.seconds)
        assertThat(server.requestCount + archive.requestCount).isEqualTo(1)
        // A blocked call records nothing for its own address.
        assertThat(failures.get(key(RELEASE, CoverSize.LIST))).isNull()
    }

    @Test
    fun `a 503 with a Retry-After of years pauses every CAA request for 1 hour`() = runTest {
        fake { withImages(RELEASE) }
        // About 63 years, a number OkHttp's own 503 handling can hold.
        caa.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "2000000000").body("busy").build())
        val client = client()

        assertThat(client.cover(release(OTHER), CoverSize.LIST)).isInstanceOf(CaaResult.Busy::class.java)
        val error = runCatching { client.cover(release(RELEASE), CoverSize.LIST) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CaaException.Paused::class.java)
        assertThat((error as CaaException.Paused).remaining).isBetween(1.hours - 1.seconds, 1.hours)
        assertThat(server.requestCount + archive.requestCount).isEqualTo(1)
    }

    @Test
    fun `a 503 with a Retry-After too long for OkHttp to read pauses every CAA request for 1 hour`() = runTest {
        fake { withImages(RELEASE) }
        caa.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "99999999999999999999").body("busy").build())
        val client = client()

        assertThat(client.cover(release(OTHER), CoverSize.LIST)).isInstanceOf(CaaResult.Busy::class.java)
        val error = runCatching { client.cover(release(RELEASE), CoverSize.LIST) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CaaException.Paused::class.java)
        assertThat((error as CaaException.Paused).remaining).isBetween(1.hours - 1.seconds, 1.hours)
        assertThat(server.requestCount + archive.requestCount).isEqualTo(1)
    }

    @Test
    fun `503s in a row pause the limiter longer each time`() = runTest {
        fake { withImages(RELEASE) }
        caa.enqueue(busyAnswer(), busyAnswer())
        val client = client()

        assertThat(client.cover(release(OTHER), CoverSize.LIST)).isInstanceOf(CaaResult.Busy::class.java)
        assertThat(client.cover(release(UNKNOWN), CoverSize.LIST)).isInstanceOf(CaaResult.Busy::class.java)
        client.cover(release(RELEASE), CoverSize.LIST).found()

        val times = log.to(caaHost).map { it.timeMs }
        // The backoff of TI §9: 0.5 to 1 s after the first 503, 1 to 2 s after the second.
        assertThat(times[1] - times[0]).isBetween(500L, 1_000L)
        assertThat(times[2] - times[1]).isBetween(1_000L, 2_000L)
    }

    @Test
    fun `a further 503 of the same address doubles its time`() = runTest {
        fake { busy(BUSY, retryAfterSeconds = 1) }
        val client = client()

        client.cover(release(BUSY), CoverSize.LIST)
        clock.advance(6.minutes)
        val answer = client.cover(release(BUSY), CoverSize.LIST)

        assertThat(answer).isEqualTo(CaaResult.Busy(clock.after(10.minutes)))
        assertThat(failures.get(key(BUSY, CoverSize.LIST))?.busyInARow).isEqualTo(2)
    }

    @Test
    fun `a live failure entry answers without a request, and an expired one lets the request go`() = runTest {
        fake { withImages(RELEASE) }
        val client = client()
        val entry = policy.busy(null)
        failures.put(key(RELEASE, CoverSize.DISPLAY), entry)

        assertThat(client.cover(release(RELEASE), CoverSize.DISPLAY)).isEqualTo(CaaResult.Busy(entry.expiresAt))
        assertThat(log.size).isZero()

        clock.advance(5.minutes)
        client.cover(release(RELEASE), CoverSize.DISPLAY).found()
        assertThat(log.size).isEqualTo(2)
        // A success clears the address's entry.
        assertThat(failures.get(key(RELEASE, CoverSize.DISPLAY))).isNull()
    }

    @Test
    fun `a live 503 entry of a thumbnail does not send the request to the original`() = runTest {
        fake { withImages(RELEASE) }
        failures.put(key(RELEASE, CoverSize.EMBED), policy.busy(null))

        assertThat(client().cover(release(RELEASE), CoverSize.EMBED)).isInstanceOf(CaaResult.Busy::class.java)
        assertThat(log.size).isZero()
    }

    @Test
    fun `with the gate closed nothing is sent and nothing is recorded`() = runTest {
        fake {
            withImages(RELEASE)
            withIndex(FakeCaa.INDEX_RELEASE)
        }
        val client = client(gate = InMemoryOnlineGate())

        val cover = runCatching { client.cover(release(RELEASE), CoverSize.DISPLAY) }.exceptionOrNull()
        val index = runCatching { client.releaseIndex(FakeCaa.INDEX_RELEASE) }.exceptionOrNull()

        assertThat(cover).isInstanceOf(GateClosedException::class.java)
        assertThat(index).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount + archive.requestCount).isZero()
        assertThat(failures.get(key(RELEASE, CoverSize.DISPLAY))).isNull()
        assertThat(failures.get(key(RELEASE, CoverSize.ORIGINAL))).isNull()
    }

    @Test
    fun `a stopped Cover Art Archive sends nothing`() = runTest {
        fake { withImages(RELEASE) }
        val statuses = ProviderStatusStore().apply { stop(ProviderId.COVER_ART_ARCHIVE, StopReason.USER_AGENT_REJECTED) }

        val error = runCatching { client(statuses = statuses).cover(release(RELEASE), CoverSize.DISPLAY) }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderStoppedException::class.java)
        assertThat(server.requestCount + archive.requestCount).isZero()
    }

    @Test
    fun `the release index lists the images on the configured CAA address, through the redirect`() = runTest {
        fake { withIndex(FakeCaa.INDEX_RELEASE) }
        val id = FakeCaa.INDEX_RELEASE

        val index = client().releaseIndex(id).found()

        assertThat(log.entries.map { it.host to it.path }).containsExactly(
            caaHost to "/release/$id",
            archiveHost to "/download/mbid-$id/index.json",
        )
        assertThat(log.entries.map { it.userAgent }).containsOnly(TestServices.USER_AGENT)
        // The fixture's links name coverartarchive.org; they are moved onto the configured (fake) base address.
        val prefix = "http://$caaHost/release/$id"
        assertThat(index.images.map { it.id }).containsExactly("1234567890", "2234567890")
        val front = index.images[0]
        assertThat(index.front).isEqualTo(front)
        assertThat(front.front).isTrue()
        assertThat(front.types).containsExactly("Front")
        assertThat(front.approved).isTrue()
        assertThat(front.original.toString()).isEqualTo("$prefix/1234567890.jpg")
        assertThat(front.thumbnails.mapValues { it.value.toString() }).isEqualTo(
            mapOf(250 to "$prefix/1234567890-250.jpg", 500 to "$prefix/1234567890-500.jpg", 1200 to "$prefix/1234567890-1200.jpg"),
        )
        val back = index.images[1]
        assertThat(back.front).isFalse()
        assertThat(back.types).containsExactly("Back", "Tray")
        assertThat(back.comment).isEqualTo("tray")
        assertThat(back.original.toString()).isEqualTo("$prefix/2234567890.png")
        // Known only by the older names small and large.
        assertThat(back.thumbnail(250).toString()).isEqualTo("$prefix/2234567890-250.jpg")
        assertThat(back.thumbnail(500).toString()).isEqualTo("$prefix/2234567890-500.jpg")
        assertThat(back.thumbnail(1200)).isNull()
    }

    @Test
    fun `an image of the release index is fetched from the fake CAA, never from coverartarchive_org`() = runTest {
        fake { withIndex(FakeCaa.INDEX_RELEASE) }
        val client = client()
        val index = client.releaseIndex(FakeCaa.INDEX_RELEASE).found()
        // Checked before anything is fetched, so a wrong address fails here and never reaches the real service.
        val addresses = index.images.flatMap { listOf(it.original) + it.thumbnails.values }
        assertThat(addresses.map { "${it.host}:${it.port}" }).containsOnly(caaHost)
        val before = log.size

        val thumbnail = client.image(index.front!!, 250).found()
        val back = client.image(index.images[1], null).found()

        assertThat(thumbnail.size).isEqualTo(PictureSize(250, 250))
        assertThat(thumbnail.original).isFalse()
        assertThat(back.original).isTrue()
        assertThat(log.entries.drop(before).map { it.host to it.path.substringAfterLast('/') }).containsExactly(
            caaHost to "1234567890-250.jpg",
            archiveHost to "mbid-${FakeCaa.INDEX_RELEASE}-1234567890_thumb250.jpg",
            caaHost to "2234567890.png",
            archiveHost to "mbid-${FakeCaa.INDEX_RELEASE}-2234567890.jpg",
        )
        assertThat(log.entries.map { it.userAgent }).containsOnly(TestServices.USER_AGENT)
    }

    @Test
    fun `a release without images answers 404, which is recorded`() = runTest {
        fake()
        val client = client()

        assertThat(client.releaseIndex(UNKNOWN)).isEqualTo(CaaResult.NotFound)
        assertThat(client.releaseIndex(UNKNOWN)).isEqualTo(CaaResult.NotFound)
        assertThat(log.size).isEqualTo(1)
        assertThat(failures.get(CoverUrls.releaseIndex(base, UNKNOWN).toString())).isEqualTo(policy.notFound())
    }

    @Test
    fun `a release index that is not CAA's JSON is retried, then reported unavailable`() = runTest {
        fake { withIndex(FakeCaa.INDEX_RELEASE) }
        val page = MockResponse.Builder().setHeader("Content-Type", "text/html").body("<html>Sign in</html>").build()
        caa.enqueue(page, page)

        val error = runCatching { client(maxRetries = 1).releaseIndex(FakeCaa.INDEX_RELEASE) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CaaException.Unavailable::class.java)
        assertThat(error!!.cause).isInstanceOf(CaaException.Unreadable::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `an ID that is no MBID sends nothing`() = runTest {
        fake()
        assertThat(client().releaseIndex("not an mbid")).isEqualTo(CaaResult.NotFound)
        assertThat(log.size).isZero()
    }

    @Test
    fun `an image of the release index comes through its thumbnail, else through its original`() = runTest {
        fake { withoutThumbnails(RELEASE, "front-1200") }
        val image = CaaImage(
            id = "1234567890",
            front = true,
            types = listOf("Front"),
            comment = "",
            approved = true,
            original = server.url("/release/$RELEASE/front"),
            thumbnails = mapOf(
                250 to server.url("/release/$RELEASE/front-250"),
                1200 to server.url("/release/$RELEASE/front-1200"),
            ),
        )
        val client = client()

        assertThat(client.image(image, 250).found().size).isEqualTo(PictureSize(250, 250))
        assertThat(client.image(image, 500).found().let { it.size to it.original }).isEqualTo(PictureSize(1500, 1400) to true)
        assertThat(client.image(image, null).found().original).isTrue()
        // A thumbnail missing after its redirect falls back to the original.
        assertThat(client.image(image, 1200).found().let { it.size to it.original }).isEqualTo(PictureSize(1500, 1400) to true)
    }

    @Test
    fun `a release index thumbnail missing without a redirect still falls back to the image's own original`() = runTest {
        fake { withImages(RELEASE) }
        val image = CaaImage(
            id = "1234567890",
            front = false,
            types = listOf("Back"),
            comment = "",
            approved = true,
            original = server.url("/release/$RELEASE/front"),
            thumbnails = mapOf(250 to server.url("/release/$UNKNOWN/front-250")),
        )

        val found = client().image(image, 250).found()

        // The front rule (no front image, so no original) is about front addresses only; this image's original exists.
        assertThat(found.original).isTrue()
        assertThat(found.size).isEqualTo(PictureSize(1500, 1400))
        assertThat(failures.get(image.original.toString())).isNull()
    }

    @Test
    fun `a 404 after a redirect that stays on CAA counts as CAA's own and does not ask for the original`() = runTest {
        fake()
        // The release group's thumbnail sends the client to a release on CAA itself, which has no front image.
        caa.enqueue(MockResponse.Builder().code(307).addHeader("Location", server.url("/release/$UNKNOWN/front-500")).build())

        val answer = client().cover(CoverSource(CoverEntity.RELEASE_GROUP, GROUP), CoverSize.DISPLAY)

        assertThat(answer).isEqualTo(CaaResult.NotFound)
        assertThat(log.entries.map { it.path }).containsExactly("/release-group/$GROUP/front-500", "/release/$UNKNOWN/front-500")
        assertThat(failures.get(key(GROUP, CoverSize.ORIGINAL, CoverEntity.RELEASE_GROUP))).isEqualTo(policy.notFound())
    }

    @Test
    fun `429, 502 and 504 are retried after a pause, as for the other providers, and recorded as nothing`() = runTest {
        fake { withImages(RELEASE) }
        val client = client()

        for (code in listOf(429, 502, 504)) {
            caa.enqueue(MockResponse.Builder().code(code).body("busy").build())
            val before = log.to(caaHost).size

            assertThat(client.cover(release(RELEASE), CoverSize.LIST).found().size).isEqualTo(PictureSize(250, 250))

            val tries = log.to(caaHost).drop(before)
            assertThat(tries).hasSize(2)
            // The first retry of TI §9 waits 0.5 to 1 s.
            assertThat(tries[1].timeMs - tries[0].timeMs).isGreaterThanOrEqualTo(500)
        }
        assertThat(failures.get(key(RELEASE, CoverSize.LIST))).isNull()
    }

    @Test
    fun `a 502 that stays through every retry is reported unavailable and recorded as nothing`() = runTest {
        fake { withImages(RELEASE) }
        val bad = MockResponse.Builder().code(502).body("Bad Gateway").build()
        caa.enqueue(bad, bad)

        val error = runCatching { client(maxRetries = 1).cover(release(RELEASE), CoverSize.LIST) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CaaException.Unavailable::class.java)
        assertThat(failures.get(key(RELEASE, CoverSize.LIST))).isNull()
    }

    @Test
    fun `other error statuses fail at once`() = runTest {
        fake { withImages(RELEASE) }
        caa.enqueue(MockResponse.Builder().code(500).body("Internal Server Error").build())

        val error = runCatching { client().cover(release(RELEASE), CoverSize.LIST) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CaaException.Http::class.java)
        assertThat((error as CaaException.Http).code).isEqualTo(500)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a text page with status 200 is retried as a broken answer`() = runTest {
        fake { withImages(RELEASE) }
        caa.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/html; charset=utf-8").body("<html>Sign in</html>").build())

        val image = client().cover(release(RELEASE), CoverSize.LIST).found()

        assertThat(image.size).isEqualTo(PictureSize(250, 250))
        assertThat(log.to(caaHost)).hasSize(2)
    }

    @Test
    fun `any other body that is no image fails without a retry`() = runTest {
        fake { withImages(RELEASE) }
        caa.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/pdf").body("%PDF-1.4").build())

        val error = runCatching { client().cover(release(RELEASE), CoverSize.ORIGINAL) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CaaException.NotAnImage::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an image over the size limit fails`() = runTest {
        fake { withImages(RELEASE) }

        val error = runCatching { client(maxImageBytes = 1_000).cover(release(RELEASE), CoverSize.DISPLAY) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CaaException.TooLarge::class.java)
    }

    @Test
    fun `the services hold one client with their HTTP client and the CAA limiter of 1 s`() = runTest {
        fake { withImages(RELEASE).withImages(OTHER) }
        val services = TestServices.services(
            baseUrls = TestServices.baseUrls(acoustId = server.url("/v2/"), musicBrainz = server.url("/ws/2/"), coverArtArchive = base),
            clock = clock,
            timeSource = testTimeSource,
        )
        val client = services.coverArtArchive
        // One per process, so the 503s in a row and the failure entries are the provider's, not a caller's.
        assertThat(services.coverArtArchive).isSameAs(client)

        client.cover(release(RELEASE), CoverSize.LIST).found()
        client.cover(release(OTHER), CoverSize.LIST).found()

        val caaRequests = log.to(caaHost)
        assertThat(caaRequests[1].timeMs - caaRequests[0].timeMs).isGreaterThanOrEqualTo(1_000)
        assertThat(log.entries.map { it.userAgent }).containsOnly(TestServices.USER_AGENT)
        // The redirect hop takes no slot of its own.
        assertThat(log.to(archiveHost)[0].timeMs).isEqualTo(caaRequests[0].timeMs)
    }

    private fun busyAnswer() = MockResponse.Builder().code(503).body("Service Temporarily Unavailable").build()

    private companion object {
        const val RELEASE = "11111111-1111-4111-8111-111111111111"
        const val GROUP = "22222222-2222-4222-8222-222222222222"
        const val OTHER = "33333333-3333-4333-8333-333333333333"
        const val BUSY = "44444444-4444-4444-8444-444444444444"
        const val UNKNOWN = "00000000-0000-4000-8000-000000000000"
    }
}
