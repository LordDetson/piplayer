package io.github.lorddetson.piplayer.net

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ConnectivityTest {

    @ParameterizedTest(name = "internet={0}, validated={1}, portal={2} -> {3}")
    @CsvSource(
        "true, true, false, VALIDATED",
        // Connected, but Android has not seen it reach the internet (yet): nothing is sent.
        "true, false, false, NO_NETWORK",
        // A local-only network (a printer's Wi-Fi) is never validated for the internet.
        "false, true, false, NO_NETWORK",
        "false, false, false, NO_NETWORK",
        "true, false, true, CAPTIVE_PORTAL",
        "false, false, true, CAPTIVE_PORTAL",
    )
    fun `a lookup needs both INTERNET and VALIDATED`(
        hasInternet: Boolean,
        validated: Boolean,
        captivePortal: Boolean,
        expected: NetworkState,
    ) {
        assertThat(Connectivity.stateOf(hasInternet, validated, captivePortal)).isEqualTo(expected)
    }
}
