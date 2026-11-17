package io.github.lorddetson.piplayer.identification.batch

import androidx.work.NetworkType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The WorkManager constraints of the batch jobs (TI §8, §11.1 M4a "Work constraints"): "unmetered only" gives
 * `UNMETERED`, "Use mobile data this time" `NOT_ROAMING`, roaming allowed `CONNECTED`, and both phases refuse a low
 * battery; phase (a) takes no network constraint.
 */
class BatchConstraintsTest {

    @Test
    fun `unmetered only wins over every one-off answer`() {
        assertThat(batchNetworkType(unmeteredOnly = true, useMobileData = true, allowRoaming = true))
            .isEqualTo(NetworkType.UNMETERED)
        assertThat(batchNetworkType(unmeteredOnly = true, useMobileData = false, allowRoaming = false))
            .isEqualTo(NetworkType.UNMETERED)
    }

    @Test
    fun `use mobile data this time keeps the batch off roaming`() {
        assertThat(batchNetworkType(unmeteredOnly = false, useMobileData = true, allowRoaming = false))
            .isEqualTo(NetworkType.NOT_ROAMING)
    }

    @Test
    fun `without the unmetered and mobile-data choices the batch may use any network`() {
        assertThat(batchNetworkType(unmeteredOnly = false, useMobileData = false, allowRoaming = false))
            .isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun `roaming allowed lifts the not-roaming answer to any network`() {
        assertThat(batchNetworkType(unmeteredOnly = false, useMobileData = true, allowRoaming = true))
            .isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun `the lookup constraint is the chosen network with a full battery`() {
        val constraints = lookupConstraints(unmeteredOnly = true)
        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(constraints.requiresBatteryNotLow()).isTrue()
    }

    @Test
    fun `the scan constraint asks for a full battery and no network`() {
        val constraints = scanConstraints()
        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(constraints.requiresBatteryNotLow()).isTrue()
    }
}
