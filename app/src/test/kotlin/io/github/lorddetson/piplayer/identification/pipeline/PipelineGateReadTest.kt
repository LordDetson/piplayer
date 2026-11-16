package io.github.lorddetson.piplayer.identification.pipeline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.net.OnlineConsent
import io.github.lorddetson.piplayer.settings.DataStoreOnlineGate
import io.github.lorddetson.piplayer.settings.IdentificationSettings
import io.github.lorddetson.piplayer.settings.MemoryDataStore
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The app's gate is the user's stored choice (TI §9), which is read from the `settings` file after the process starts:
 * a run that starts before the read waits for it instead of reporting "online lookups off" (TI §3 "One run"). The read
 * runs on another thread with real delays, so this test runs on real time.
 */
class PipelineGateReadTest {

    @StartStop
    private val server = MockWebServer()

    private val gateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun stop() = gateScope.cancel()

    /** A settings file that holds [initial] and takes [readDelayMs] to give its first state. */
    private class SlowFile(initial: Preferences, private val readDelayMs: Long) : DataStore<Preferences> {
        private val memory = MemoryDataStore(initial)

        override val data: Flow<Preferences> = flow {
            delay(readDelayMs)
            emitAll(memory.data)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            memory.updateData(transform)
    }

    private fun gate(enabled: Boolean) = DataStoreOnlineGate(
        IdentificationSettings(
            SlowFile(
                mutablePreferencesOf(
                    IdentificationSettings.ENABLED to enabled,
                    IdentificationSettings.VERSION to OnlineConsent.CURRENT_DISCLOSURE_VERSION,
                ),
                readDelayMs = 300,
            ),
        ),
        gateScope,
    )

    private val stage = object : LocalStage {
        override suspend fun read(file: FileRef) = LocalFile(
            target = IdentificationTarget.OpenedFile("file:///music/track.mp3", KEY, "track.mp3", 1),
            fileName = "track.mp3",
            tags = TrackTags(title = "Paper Lanterns", artist = "The Quiet Harbors"),
            duration = StageDuration.Exact(214_000),
            format = null,
            folder = null,
            uri = "file:///music/track.mp3",
        )

        override suspend fun walk(file: LocalFile, plan: DurationPlan): Long? = null
    }

    private val fingerprints = object : FingerprintCache {
        override suspend fun cached(contentKey: String): CachedFingerprint? = null
        override suspend fun compute(file: LocalFile): ComputedFingerprint = error("no key, no fingerprint")
        override suspend fun keepAcoustIdTrack(contentKey: String, trackId: String) = Unit
    }

    private fun pipeline(gate: DataStoreOnlineGate): IdentificationPipeline {
        server.dispatcher = FakeMusicBrainz()
        val services = TestServices.services(
            baseUrls = TestServices.baseUrls(server.url("/v2/"), server.url("/ws/2/")),
            gate = gate,
        )
        return IdentificationPipeline(services, stage, fingerprints, { error("not used") })
    }

    @Test
    fun `a run that starts before the stored choice is read waits for it`() = runBlocking {
        val gate = gate(enabled = true)
        assertThat(gate.isOpen()).describedAs("closed before the file is read").isFalse()

        val result = pipeline(gate).run(FileRef.Opened("file:///music/track.mp3"))

        assertThat(result.outcome).isInstanceOf(IdentificationOutcome.Found::class.java)
        assertThat(server.requestCount).isGreaterThan(0)
    }

    @Test
    fun `a stored choice of off read after the start gives online lookups off`() = runBlocking {
        val result = pipeline(gate(enabled = false)).run(FileRef.Opened("file:///music/track.mp3"))

        assertThat(result.outcome).isEqualTo(IdentificationOutcome.Disabled)
        assertThat(server.requestCount).isZero()
    }

    private companion object {
        const val KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
