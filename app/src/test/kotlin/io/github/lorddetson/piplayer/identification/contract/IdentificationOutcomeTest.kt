package io.github.lorddetson.piplayer.identification.contract

import io.github.lorddetson.piplayer.identification.FingerprintStage
import io.github.lorddetson.piplayer.identification.UnavailableReason
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient
import io.github.lorddetson.piplayer.identification.caa.CaaException
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.ProviderStoppedException
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.release.LookupPlanTest
import io.github.lorddetson.piplayer.identification.release.ReleaseChoice
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.net.CaptivePortalException
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.NoNetworkException
import io.github.lorddetson.piplayer.net.RateLimitPauseException
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The typed outcomes of one identification (TI §11.1, M3 "Identification outcomes"). */
class IdentificationOutcomeTest {

    private val withKey = FingerprintStage.Available(acoustIdClientNobodyCalls())
    private val withoutKey = FingerprintStage.Unavailable(UnavailableReason.NoKey)

    @Test
    fun `a call the online gate blocked means online lookups are off`() {
        assertThat(IdentificationOutcome.blocked(GateClosedException())).isEqualTo(IdentificationOutcome.Disabled)
    }

    @Test
    fun `a call without a validated network and one behind a captive portal have outcomes of their own`() {
        assertThat(IdentificationOutcome.blocked(NoNetworkException())).isEqualTo(IdentificationOutcome.NoNetwork)
        assertThat(IdentificationOutcome.blocked(CaptivePortalException())).isEqualTo(IdentificationOutcome.CaptivePortal)
    }

    @Test
    fun `a stopped provider keeps its name and the reason it stopped`() {
        val stopped = ProviderStoppedException(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED)
        assertThat(IdentificationOutcome.blocked(stopped))
            .isEqualTo(IdentificationOutcome.ProviderStopped(ProviderId.MUSICBRAINZ, StopReason.USER_AGENT_REJECTED))
        assertThat(IdentificationOutcome.blocked(ProviderStoppedException(ProviderId.ACOUSTID, StopReason.INVALID_API_KEY)))
            .isEqualTo(IdentificationOutcome.ProviderStopped(ProviderId.ACOUSTID, StopReason.INVALID_API_KEY))
    }

    @Test
    fun `a blocked call wrapped by a client keeps its outcome`() {
        val wrapped = IOException("search ended", CaaException.Unavailable(1, GateClosedException()))
        assertThat(IdentificationOutcome.blocked(wrapped)).isEqualTo(IdentificationOutcome.Disabled)
    }

    @Test
    fun `other errors are no blocked outcome`() {
        assertThat(IdentificationOutcome.blocked(IOException("connection reset"))).isNull()
        assertThat(IdentificationOutcome.blocked(CaaException.Http(500))).isNull()
        assertThat(IdentificationOutcome.blocked(RateLimitPauseException(300.seconds))).isNull()
        assertThat(IdentificationOutcome.blocked(IllegalStateException())).isNull()
    }

    @Test
    fun `a match keeps its band with or without a key`() {
        val high = LookupPlanTest.ranking(0.97)
        val medium = LookupPlanTest.ranking(0.72)
        for (stage in listOf(withKey, withoutKey)) {
            val found = IdentificationOutcome.finished(high, ReleaseChoice.NONE, "acoustid-track", stage)
            assertThat(found).isInstanceOf(IdentificationOutcome.Found::class.java)
            found as IdentificationOutcome.Found
            assertThat(found.band).isEqualTo(Band.HIGH)
            assertThat(found.ranking).isSameAs(high)
            assertThat(found.acoustIdTrackId).isEqualTo("acoustid-track")
            val second = IdentificationOutcome.finished(medium, ReleaseChoice.NONE, null, stage) as IdentificationOutcome.Found
            assertThat(second.band).isEqualTo(Band.MEDIUM)
        }
    }

    @Test
    fun `nothing above 0_50 is no match with a key and the missing key without one`() {
        val weak = LookupPlanTest.ranking(0.31)
        val none = Ranking(emptyList(), null, Band.NO_MATCH)

        val withKeyWeak = IdentificationOutcome.finished(weak, ReleaseChoice.NONE, null, withKey)
        assertThat((withKeyWeak as IdentificationOutcome.Found).band).isEqualTo(Band.NO_MATCH)
        val withKeyNone = IdentificationOutcome.finished(none, ReleaseChoice.NONE, null, withKey)
        assertThat((withKeyNone as IdentificationOutcome.Found).band).isEqualTo(Band.NO_MATCH)

        assertThat(IdentificationOutcome.finished(weak, ReleaseChoice.NONE, null, withoutKey)).isEqualTo(IdentificationOutcome.NoKey)
        assertThat(IdentificationOutcome.finished(none, ReleaseChoice.NONE, null, withoutKey)).isEqualTo(IdentificationOutcome.NoKey)
    }

    /** A stage with a key; the outcome rules never call its client. */
    private fun acoustIdClientNobodyCalls(): AcoustIdClient =
        io.github.lorddetson.piplayer.fakes.TestServices.services(
            io.github.lorddetson.piplayer.fakes.TestServices.baseUrls(
                okhttp3.HttpUrl.Builder().scheme("https").host("localhost").build(),
                okhttp3.HttpUrl.Builder().scheme("https").host("localhost").build(),
            ),
            acoustIdApiKey = "testKey123",
        ).let { (it.fingerprintStage as FingerprintStage.Available).acoustId }
}
