package io.github.lorddetson.piplayer.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The spike's pipeline as SpikeServices builds it in the app (TI §3 "One run", §11.2): its fingerprints go to the
 * spike's own cache in memory, never to the library database's fingerprint cache, which only the app's own runs fill
 * behind the user's choice. Building the pipeline sends and reads nothing.
 */
@RunWith(AndroidJUnit4::class)
class SpikeServicesDeviceTest {

    private val context = TestFiles.targetContext

    @Test
    fun theSpikePipelineKeepsItsFingerprintsInTheSpikeCache() {
        val pipeline = SpikeServices.pipeline(context)

        assertThat(pipeline.fingerprints).isInstanceOf(SpikeFingerprintCache::class.java)
        assertThat(pipeline.fingerprints).isSameAs(SpikeServices.fingerprints(context))
    }
}
