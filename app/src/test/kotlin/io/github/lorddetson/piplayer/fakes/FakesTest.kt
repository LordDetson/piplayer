package io.github.lorddetson.piplayer.fakes

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdJson
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdResponse
import io.github.lorddetson.piplayer.net.HttpClients
import javax.imageio.ImageIO
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The shared fakes answer as the services do, so the steps that build on them start from true fixtures. */
class FakesTest {

    @StartStop
    private val server = MockWebServer()

    @StartStop
    private val archiveServer = MockWebServer()

    private val http: OkHttpClient = HttpClients.create(TestServices.USER_AGENT, TestServices.openGate())

    private fun get(path: String): Response = http.newCall(Request.Builder().url(server.url(path)).build()).execute()

    private fun lookup(fingerprint: String, client: String = "testKey123"): Response {
        val form = FormBody.Builder().add("client", client).add("duration", "214").add("fingerprint", fingerprint).build()
        return http.newCall(Request.Builder().url(server.url("/v2/lookup")).post(form).build()).execute()
    }

    private fun decode(response: Response): AcoustIdResponse =
        response.use { AcoustIdJson.decodeFromString(AcoustIdResponse.serializer(), it.body.string()) }

    @Test
    fun `the AcoustID fake answers by fingerprint with each fixture`() {
        server.dispatcher = FakeAcoustId()
            .on("empty", FakeAcoustId.EMPTY)
            .on("bad-key", FakeAcoustId.ERROR_4)
            .on("busy", FakeAcoustId.ERROR_14)
            .on("bare", FakeAcoustId.NO_RECORDINGS)
            .on("close", FakeAcoustId.CLOSE_SCORES)

        assertThat(decode(lookup("anything")).results).hasSize(2)
        assertThat(decode(lookup("empty")).let { it.status to it.results }).isEqualTo("ok" to emptyList<Any>())
        lookup("bad-key").use { assertThat(it.code).isEqualTo(400) }
        assertThat(decode(lookup("bad-key")).error!!.code).isEqualTo(4)
        lookup("busy").use { assertThat(it.code).isEqualTo(429) }
        assertThat(decode(lookup("busy")).error!!.code).isEqualTo(14)
        assertThat(decode(lookup("bare")).results.map { it.recordings }).containsExactly(emptyList(), emptyList())
        val close = decode(lookup("close")).results.map { it.score }
        assertThat(close).hasSize(2)
        assertThat(close[0] - close[1]).isPositive().isLessThan(0.10)
        assertThat(decode(lookup("anything", client = "")).error!!.code).isEqualTo(2)
    }

    @Test
    fun `the MusicBrainz fake answers searches, ISRCs and unknown MBIDs`() {
        server.dispatcher = FakeMusicBrainz()
        get("/ws/2/recording?query=paper&fmt=json").use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.body.string()).contains("Paper Lanterns")
        }
        get("/ws/2/isrc/${FakeMusicBrainz.KNOWN_ISRC}?fmt=json").use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.body.string()).contains("11111111-1111-4111-8111-111111111111")
        }
        get("/ws/2/recording/00000000-0000-4000-8000-000000000000?fmt=json").use {
            assertThat(it.code).isEqualTo(404)
            assertThat(it.body.string()).contains("Not Found")
        }
    }

    @Test
    fun `the Cover Art Archive fake redirects to its image host and serves JPEGs of known sizes`() {
        val log = RecordingRequestLog()
        val caa = FakeCaa { archiveServer.url("/") }
            .withImages(RELEASE)
            .missingAfterRedirect(NO_THUMBNAIL)
            .busy(BUSY, retryAfterSeconds = 30)
        server.dispatcher = log.wrap(caa)
        archiveServer.dispatcher = log.wrap(caa.archive)

        for ((name, image) in listOf(
            "front-250" to FakeCaa.FRONT_250, "front-500" to FakeCaa.FRONT_500,
            "front-1200" to FakeCaa.FRONT_1200, "front" to FakeCaa.ORIGINAL,
        )) {
            get("/release/$RELEASE/$name").use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.priorResponse!!.code).isEqualTo(307)
                assertThat(response.request.url.port).isEqualTo(archiveServer.port)
                val picture = ImageIO.read(response.body.byteStream())
                assertThat(picture.width to picture.height).isEqualTo(image.width to image.height)
            }
        }
        get("/release-group/$RELEASE/front-500").use { assertThat(it.code).isEqualTo(200) }

        get("/release/$NO_THUMBNAIL/front-250").use {
            assertThat(it.code).isEqualTo(404)
            assertThat(it.priorResponse!!.code).isEqualTo(307)
        }
        get("/release/$BUSY/front-250").use {
            assertThat(it.code).isEqualTo(503)
            assertThat(it.header("Retry-After")).isEqualTo("30")
        }
        get("/release/00000000-0000-4000-8000-000000000000/front-250").use {
            assertThat(it.code).isEqualTo(404)
            assertThat(it.priorResponse).isNull()
        }
        assertThat(log.entries.map { it.userAgent }).containsOnly(TestServices.USER_AGENT)
    }

    @Test
    fun `the request log keeps time, host, User-Agent and body, and hides the AcoustID key when printed`() {
        var now = 1_000L
        val log = RecordingRequestLog { now }
        server.dispatcher = log.wrap(FakeAcoustId())
        lookup("fp", client = "secretKey9").close()
        now = 2_000L
        get("/v2/other").close()

        val (post, other) = log.entries
        assertThat(post.timeMs).isEqualTo(1_000)
        assertThat(other.timeMs).isEqualTo(2_000)
        assertThat(post.host).isEqualTo("${server.hostName}:${server.port}")
        assertThat(post.userAgent).isEqualTo(TestServices.USER_AGENT)
        assertThat(post.body).contains("client=secretKey9").contains("fingerprint=fp")
        assertThat(post.toString()).doesNotContain("secretKey9").contains("client=<redacted>")
    }

    private companion object {
        const val RELEASE = "44444444-4444-4444-8444-444444444444"
        const val NO_THUMBNAIL = "66666666-6666-4666-8666-666666666666"
        const val BUSY = "77777777-7777-4777-8777-777777777777"
    }
}
