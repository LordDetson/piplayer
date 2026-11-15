package io.github.lorddetson.piplayer.identification.score

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The bands of TI §4.1; an empty runner-up means a single candidate. */
class BandsTest {

    @ParameterizedTest(name = "best {0}, runner-up {1} -> {2}")
    @CsvSource(
        "1.0, , HIGH",
        "0.95, 0.80, HIGH",
        // Boundaries belong to the higher band, also where 0.9 - 0.8 rounds below 0.1 in floating point.
        "0.90, 0.80, HIGH",
        "0.90, , HIGH",
        "0.95, 0.88, MEDIUM",
        "0.95, 0.90, MEDIUM",
        "0.8999, , MEDIUM",
        "0.85, , MEDIUM",
        "0.50, 0.49, MEDIUM",
        "0.50, 0.50, MEDIUM",
        "0.4999, , NO_MATCH",
        "0.0, , NO_MATCH",
    )
    fun `bands follow the score and the runner-up gap`(best: Double, runnerUp: Double?, expected: Band) {
        assertThat(Bands.of(best, runnerUp)).isEqualTo(expected)
    }

    @Test
    fun `no candidate is no match`() {
        assertThat(Bands.of(null, null)).isEqualTo(Band.NO_MATCH)
    }
}
