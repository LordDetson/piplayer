package io.github.lorddetson.piplayer.identification.pipeline

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.fakes.TestTls
import io.github.lorddetson.piplayer.fakes.formFields
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.ProviderBaseUrls
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.release.EditionSource
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.tagging.Tagger
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pipeline end to end on a device (TI §3 "One run", §11.2): a fixture file opened by its URI goes through `:tagger`,
 * the platform's decoders, Chromaprint and the native encoder, and the HTTPS fakes of AcoustID and MusicBrainz answer
 * its lookups. The library database is one of the test's own, in memory. Nothing here reaches a real host.
 */
@RunWith(AndroidJUnit4::class)
class IdentificationPipelineDeviceTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val acoustIdServer = MockWebServer()
    private val musicBrainzServer = MockWebServer()
    private val servers = listOf(acoustIdServer, musicBrainzServer)
    private val log = RecordingRequestLog()
    private val files = mutableListOf<File>()

    /** The fingerprints AcoustID was sent, in order; the bodies of the log redact nothing but the key. */
    private val sentFingerprints = CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        // Whatever the device computes, AcoustID links it to the Harbor Lights recording by its ID only.
        val acoustId = FakeAcoustId().otherwise(FakeAcoustId.ID_ONLY)
        acoustIdServer.dispatcher = log.wrap(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                request.formFields()["fingerprint"]?.let(sentFingerprints::add)
                return acoustId.dispatch(request)
            }
        })
        musicBrainzServer.dispatcher = log.wrap(FakeMusicBrainz().withHarborLights())
        servers.forEach {
            TestTls.useHttps(it)
            it.start()
        }
    }

    @After
    fun stop() {
        servers.forEach { it.close() }
        library.close()
        files.forEach { it.delete() }
    }

    private fun services(gate: OnlineGate = TestServices.openGate()): IdentificationServices = TestServices.services(
        baseUrls = ProviderBaseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/"), acoustIdServer.url("/caa/")),
        gate = gate,
        acoustIdApiKey = KEY,
        configureHttp = { TestTls.trustTestServers(this) },
    )

    private fun pipeline(services: IdentificationServices) = IdentificationPipeline(
        services = services,
        local = AndroidLocalStage(context, Tagger.client(context), LibraryAccess.of(library)),
        fingerprints = LibraryFingerprintCache(context, LibraryAccess.of(library)),
        encoder = ChromaprintEncoder,
        country = { "GB" },
    )

    private fun fixture(): String = Uri.fromFile(TestFiles.copy(FIXTURE).also { files += it }).toString()

    @Test
    fun aFixtureFileIsIdentifiedEndToEndAndASecondRunTakesItsCachedFingerprint() = runBlocking<Unit> {
        val uri = fixture()
        val progress = Collections.synchronizedList(mutableListOf<PipelineProgress>())

        val first = pipeline(services()).run(FileRef.Opened(uri)) { progress += it }

        val found = first.outcome as IdentificationOutcome.Found
        assertThat(found.band).describedAs("stages %s, duration %s ms", first.stages, first.durationMs).isEqualTo(Band.HIGH)
        assertThat(found.choice.suggested!!.release.id).isEqualTo(FakeMusicBrainz.HARBOR_RELEASE)
        assertThat(found.choice.suggested.source).isEqualTo(EditionSource.RANKING)
        assertThat(found.acoustIdTrackId).isEqualTo(ID_ONLY_TRACK)
        assertThat(first.fingerprint).isEqualTo(FingerprintSource.COMPUTED)
        assertThat(first.durationMs).isBetween(1_900L, 2_200L)
        assertThat(progress).contains(
            PipelineProgress.Decoding,
            PipelineProgress.ReleasePages(FakeMusicBrainz.HARBOR_RECORDING, 1),
            PipelineProgress.ReleasePages(FakeMusicBrainz.HARBOR_RECORDING, 2),
        )
        val key = first.file!!.target.contentKey
        val row = library.library().fingerprint(key)!!
        assertThat(row.acoustIdId).isEqualTo(ID_ONLY_TRACK)
        assertThat(row.decoderPath).startsWith("media3-")
        assertThat(sentFingerprints).hasSize(1)
        assertThat(sentFingerprints.single()).startsWith("AQ")
        assertThat(log.entries.single { it.path.startsWith("/v2/") }.body).contains("duration=2&")
        assertThat(log.entries.toString()).doesNotContain(KEY)

        progress.clear()
        val second = pipeline(services()).run(FileRef.Opened(uri)) { progress += it }

        assertThat(second.fingerprint).isEqualTo(FingerprintSource.CACHED)
        assertThat(progress).doesNotContain(PipelineProgress.Decoding)
        assertThat((second.outcome as IdentificationOutcome.Found).band).isEqualTo(Band.HIGH)
        // The cached raw items encode to the string the session sent.
        assertThat(sentFingerprints).hasSize(2)
        assertThat(sentFingerprints[1]).isEqualTo(sentFingerprints[0])
        assertThat(library.library().fingerprint(key)!!.acoustIdId).isEqualTo(ID_ONLY_TRACK)
    }

    @Test
    fun withTheGateClosedNothingIsReadDecodedOrSent() = runBlocking<Unit> {
        val result = pipeline(services(gate = InMemoryOnlineGate())).run(FileRef.Opened(fixture()))

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Disabled)
        assertThat(result.file).isNull()
        assertThat(servers.sumOf { it.requestCount }).isZero()
        assertThat(library.library().fingerprintKeys()).isEmpty()
    }

    private companion object {
        const val KEY = "testKey123"
        const val FIXTURE = "decoding/mp3-44100-stereo.mp3"
        const val ID_ONLY_TRACK = "6f1c1f4e-3b0d-4c8e-9a52-000000000009"
    }
}
