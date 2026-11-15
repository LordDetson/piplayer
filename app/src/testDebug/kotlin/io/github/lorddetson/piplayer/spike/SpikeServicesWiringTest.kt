package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.fakes.FakeAcoustId
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.RecordingRequestLog
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.FingerprintStage
import io.github.lorddetson.piplayer.identification.fingerprint.FingerprintResult
import io.github.lorddetson.piplayer.identification.fingerprint.PcmEncoding
import io.github.lorddetson.piplayer.identification.fingerprint.PcmFormat
import io.github.lorddetson.piplayer.identification.fingerprint.PcmSource
import io.github.lorddetson.piplayer.identification.fingerprint.SourceInfo
import io.github.lorddetson.piplayer.nativelib.chromaprint.Fingerprint
import io.github.lorddetson.piplayer.nativelib.taglib.AudioProperties
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The spike's services as SpikeServices builds them, against the shared fakes instead of the real hosts. */
class SpikeServicesWiringTest {

    @StartStop
    private val acoustIdServer = MockWebServer()

    @StartStop
    private val musicBrainzServer = MockWebServer()

    private val key = "testKey123"

    private object File : SpikeFile {
        override val displayName = "01 - The Quiet Harbors - Paper Lanterns.mp3"
        override val sizeBytes = 8_600_000L
        val source = PcmSource { SourceInfo("audio/mpeg", "c2.android.mp3.decoder", durationUs = 214_000_000) }
        override suspend fun readTags() = TagReadResult.Success(
            properties = mapOf("TITLE" to listOf("Paper Lanterns"), "ARTIST" to listOf("The Quiet Harbors")),
            audio = AudioProperties(lengthMs = 214_000, bitrateKbps = 320, sampleRate = 44_100, channels = 2),
            pictures = emptyList(),
        )
        override fun pcmSource() = source
    }

    private val fingerprinter: suspend (PcmSource) -> FingerprintResult = {
        FingerprintResult(
            Fingerprint(IntArray(948), "AQAAencoded"),
            fedSamples = 1_323_000,
            inputFormat = PcmFormat(44_100, 2, PcmEncoding.PCM_16BIT),
            source = SourceInfo("audio/mpeg", "c2.android.mp3.decoder", 214_000_000),
            reachedEnd = false,
        )
    }

    private fun TestScope.spike(log: RecordingRequestLog): IdentificationSpike {
        acoustIdServer.dispatcher = log.wrap(FakeAcoustId())
        musicBrainzServer.dispatcher = log.wrap(FakeMusicBrainz())
        val services = SpikeServices.create(
            userAgent = TestServices.USER_AGENT,
            connectivity = TestServices.FakeConnectivity(),
            acoustIdApiKey = key,
            baseUrls = TestServices.baseUrls(acoustIdServer.url("/v2/"), musicBrainzServer.url("/ws/2/")),
            timeSource = testTimeSource,
        )
        // The spike's services know the build's key like the app's, so their stage 3 agrees with the spike.
        assertThat(services.fingerprintStage).isInstanceOf(FingerprintStage.Available::class.java)
        return SpikeServices.spike(services, key, fingerprinter)
    }

    @Test
    fun `with the spike gate open, the spike reaches AcoustID and MusicBrainz through the services`() = runTest {
        val log = RecordingRequestLog { testScheduler.currentTime }
        val spike = spike(log)

        val report = SpikeGate.open().use { spike.run(File) }

        val candidates = (report.acoustId as StageOutcome.Done).value
        assertThat(candidates.first().recordingId).isEqualTo("b0e4d1f2-1111-4111-8111-000000000001")
        assertThat((report.textSearch as StageOutcome.Done).value.candidates).isNotEmpty()
        assertThat(log.to("${acoustIdServer.hostName}:${acoustIdServer.port}")).hasSize(1)
        assertThat(log.to("${musicBrainzServer.hostName}:${musicBrainzServer.port}")).isNotEmpty()
        assertThat(log.entries.map { it.userAgent }).containsOnly(TestServices.USER_AGENT)
        assertThat(SpikeGate.isOpen()).isFalse()
    }

    @Test
    fun `without a spike run the gate is closed and nothing is sent`() = runTest {
        val log = RecordingRequestLog { testScheduler.currentTime }
        val report = spike(log).run(File)

        assertThat(report.acoustId).isInstanceOf(StageOutcome.Failed::class.java)
        assertThat((report.acoustId as StageOutcome.Failed).error).startsWith("GateClosedException")
        assertThat(report.textSearch).isInstanceOf(StageOutcome.Failed::class.java)
        assertThat(log.size).isZero()
    }

    @Test
    fun `the spike gate opens while a handle is held and closes when the last one is closed`() {
        assertThat(SpikeGate.isOpen()).isFalse()
        val first = SpikeGate.open()
        val second = SpikeGate.open()
        first.close()
        first.close()
        assertThat(SpikeGate.isOpen()).isTrue()
        second.close()
        assertThat(SpikeGate.isOpen()).isFalse()
    }
}
