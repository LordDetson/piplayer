package io.github.lorddetson.piplayer.identification.provider

import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.net.CaptivePortalException
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.NetworkState
import io.github.lorddetson.piplayer.net.NoNetworkException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderAccessTest {

    @Test
    fun `an open gate, an active provider and a validated network let the call through`() {
        TestServices.access(ProviderId.MUSICBRAINZ).check()
    }

    @Test
    fun `the gate is checked first, so a closed gate never looks at the network`() {
        val connectivity = object : io.github.lorddetson.piplayer.net.Connectivity {
            var asked = false
            override fun state(): NetworkState = NetworkState.VALIDATED.also { asked = true }
        }
        val access = TestServices.access(ProviderId.ACOUSTID, gate = InMemoryOnlineGate(), connectivity = connectivity)
        assertThat(runCatching { access.check() }.exceptionOrNull()).isInstanceOf(GateClosedException::class.java)
        assertThat(connectivity.asked).isFalse()
    }

    @Test
    fun `no validated network and a captive portal block the call`() {
        val offline = TestServices.access(ProviderId.ACOUSTID, connectivity = TestServices.FakeConnectivity(NetworkState.NO_NETWORK))
        assertThat(runCatching { offline.check() }.exceptionOrNull()).isInstanceOf(NoNetworkException::class.java)
        val portal = TestServices.access(ProviderId.ACOUSTID, connectivity = TestServices.FakeConnectivity(NetworkState.CAPTIVE_PORTAL))
        assertThat(runCatching { portal.check() }.exceptionOrNull()).isInstanceOf(CaptivePortalException::class.java)
    }

    @Test
    fun `a stopped provider blocks its own calls and no other provider's`() {
        val statuses = ProviderStatusStore()
        val musicBrainz = TestServices.access(ProviderId.MUSICBRAINZ, statuses = statuses)
        val acoustId = TestServices.access(ProviderId.ACOUSTID, statuses = statuses)

        musicBrainz.stop(StopReason.USER_AGENT_REJECTED)

        val error = runCatching { musicBrainz.check() }.exceptionOrNull()
        assertThat(error).isInstanceOf(ProviderStoppedException::class.java)
        assertThat((error as ProviderStoppedException).provider).isEqualTo(ProviderId.MUSICBRAINZ)
        assertThat(musicBrainz.status.value).isEqualTo(ProviderStatus.Stopped(StopReason.USER_AGENT_REJECTED))
        acoustId.check()
    }

    @Test
    fun `a stop keeps its first reason`() {
        val statuses = ProviderStatusStore()
        statuses.stop(ProviderId.ACOUSTID, StopReason.INVALID_API_KEY)
        statuses.stop(ProviderId.ACOUSTID, StopReason.USER_AGENT_REJECTED)
        assertThat(statuses.status(ProviderId.ACOUSTID).value).isEqualTo(ProviderStatus.Stopped(StopReason.INVALID_API_KEY))
    }

    @Test
    fun `stores do not share their stops`() {
        val one = ProviderStatusStore()
        val other = ProviderStatusStore()
        one.stop(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED)
        assertThat(other.status(ProviderId.MUSICBRAINZ).value).isEqualTo(ProviderStatus.Active)
    }
}
