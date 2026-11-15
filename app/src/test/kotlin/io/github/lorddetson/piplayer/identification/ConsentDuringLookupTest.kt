package io.github.lorddetson.piplayer.identification

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.settings.DataStoreOnlineGate
import io.github.lorddetson.piplayer.settings.IdentificationSettings
import io.github.lorddetson.piplayer.settings.MemoryDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The persisted opt-in reaches calls that are already under way (TI §9): turning online lookups off in Settings stops a
 * lookup between its attempts and a lookup that waits for the limiter, without another request.
 */
class ConsentDuringLookupTest {

    @StartStop
    private val server = MockWebServer()

    private val gateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun stop() = gateScope.cancel()

    private val gate = DataStoreOnlineGate(IdentificationSettings(MemoryDataStore()), gateScope)

    private val query = "recording:(paper lanterns)"

    /**
     * Turns lookups on and returns once the gate is open. The gate reads the file on another thread, and [runTest]
     * would skip the virtual time of the wait for that read, so it waits on real time.
     */
    private suspend fun enableLookups() {
        withContext(Dispatchers.Default) { gate.enable() }
        assertThat(gate.isOpen()).describedAs("gate open after enable").isTrue()
    }

    private fun TestScope.services() = TestServices.services(
        baseUrls = TestServices.baseUrls(server.url("/v2/"), server.url("/ws/2/")),
        gate = gate,
        timeSource = testTimeSource,
    )

    @Test
    fun `turning lookups off while a lookup waits for its retry sends nothing more`() = runTest {
        enableLookups()
        val services = services()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // The user turns lookups off while the first answer, a busy one, is on its way.
                runBlocking { gate.turnOff() }
                return FakeMusicBrainz.busy()
            }
        }

        val error = runCatching { services.musicBrainz.searchRecordings(query, 25) }.exceptionOrNull()

        assertThat(error).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `turning lookups off while a second lookup waits for the limiter keeps it from sending`() = runTest {
        enableLookups()
        val services = services()
        server.dispatcher = FakeMusicBrainz()
        assertThat(services.musicBrainz.searchRecordings(query, 25).recordings).hasSize(2)

        val second = async { runCatching { services.musicBrainz.searchRecordings(query, 25) }.exceptionOrNull() }
        runCurrent()
        assertThat(second.isCompleted).describedAs("waiting for the limiter").isFalse()

        gate.turnOff()

        assertThat(second.await()).isInstanceOf(GateClosedException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }
}
