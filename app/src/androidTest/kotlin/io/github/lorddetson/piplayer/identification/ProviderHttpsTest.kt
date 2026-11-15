package io.github.lorddetson.piplayer.identification

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeCaa
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.Fixtures
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.fakes.TestTls
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.OnlineGate
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared fakes on a device (M3 step 1): served over HTTPS with the test certificate, since Android blocks
 * cleartext and the app allows none. Nothing here reaches a real host.
 */
@RunWith(AndroidJUnit4::class)
class ProviderHttpsTest {

    private val acoustIdServer = MockWebServer()
    private val musicBrainzServer = MockWebServer()
    private val caaServer = MockWebServer()
    private val archiveServer = MockWebServer()
    private val servers = listOf(acoustIdServer, musicBrainzServer, caaServer, archiveServer)
    private val log = RecordingRequestLog()

    @Before
    fun start() {
        val caa = FakeCaa { archiveServer.url("/") }.withImages(RELEASE)
        acoustIdServer.dispatcher = log.wrap(FakeAcoustId())
        musicBrainzServer.dispatcher = log.wrap(FakeMusicBrainz())
        caaServer.dispatcher = log.wrap(caa)
        archiveServer.dispatcher = log.wrap(caa.archive)
        servers.forEach {
            TestTls.useHttps(it)
            it.start()
        }
    }

    @After
    fun stop() {
        servers.forEach { it.close() }
    }

    private fun services(gate: OnlineGate = TestServices.openGate()) = TestServices.services(
        baseUrls = ProviderBaseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/"), caaServer.url("/")),
        gate = gate,
        configureHttp = { TestTls.trustTestServers(this) },
    )

    @Test
    fun theClientsReachTheFakesOverHttps() {
        val services = services()
        assertThat(services.baseUrls.musicBrainz.isHttps).isTrue()

        val recordings = runBlocking { services.musicBrainz.searchRecordings("recording:(paper lanterns)", 25) }
        val tracks = runBlocking { services.acoustId("testKey123").lookup("AQAAT0mUaEkSRZEGAA", 214_400) }
        val cover = services.http.newCall(
            Request.Builder().url(services.baseUrls.coverArtArchive.resolve("release/$RELEASE/front-250")!!).build(),
        ).execute().use { it.body.bytes() }

        assertThat(recordings.recordings).hasSize(2)
        assertThat(tracks.results).hasSize(2)
        val bitmap = BitmapFactory.decodeByteArray(cover, 0, cover.size)
        assertThat(bitmap.width to bitmap.height).isEqualTo(FakeCaa.FRONT_250.width to FakeCaa.FRONT_250.height)
        assertThat(log.entries.map { it.userAgent }).hasSize(4).containsOnly(TestServices.USER_AGENT)
        assertThat(log.entries.toString()).doesNotContain("testKey123")
    }

    @Test
    fun nothingIsSentWhileTheGateIsClosed() {
        val services = services(gate = InMemoryOnlineGate())
        val error = runCatching { runBlocking { services.musicBrainz.searchRecordings("recording:(x)", 25) } }.exceptionOrNull()
        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(servers.sumOf { it.requestCount }).isZero()
    }

    @Test
    fun onlyTheTestClientTrustsTheTestCertificate() {
        val appClient = HttpClients.create(TestServices.USER_AGENT, TestServices.openGate())
        val error = runCatching {
            appClient.newCall(Request.Builder().url(musicBrainzServer.url("/ws/2/recording?query=x")).build()).execute()
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(SSLException::class.java)
        // The server may book the failed handshake, but no HTTP request (with its User-Agent) ever arrived.
        assertThat(log.entries.mapNotNull { it.userAgent }).isEmpty()
    }

    @Test
    fun theFixturesComeFromTheTestApk() {
        assertThat(Fixtures.text("acoustid/lookup.json")).contains("Paper Lanterns")
        assertThat(FakeCaa.ORIGINAL.fixture.bytes()).isNotEmpty()
    }

    private companion object {
        const val RELEASE = "44444444-4444-4444-8444-444444444444"
    }
}
