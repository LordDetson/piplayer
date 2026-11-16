package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.pipeline.ComputedFingerprint
import io.github.lorddetson.piplayer.identification.pipeline.LocalFile
import io.github.lorddetson.piplayer.identification.pipeline.StageDuration
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The debug spike's fingerprint cache (TI §3 "One run"): it decodes the way the app's does but keeps what it finds in
 * memory, so a spike run, whose gate is not the user's, writes nothing to the library database's fingerprint cache.
 */
class SpikeFingerprintCacheTest {

    private val file = LocalFile(
        target = IdentificationTarget.OpenedFile("content://a", KEY, "a.mp3", 1_000),
        fileName = "a.mp3",
        tags = null,
        duration = StageDuration.Exact(214_000),
        format = "MPEG",
        folder = null,
        uri = "content://a",
    )

    @Test
    fun `a computed fingerprint and its AcoustID track ID are kept in memory and served from there`() = runTest {
        var decodes = 0
        val cache = SpikeFingerprintCache {
            decodes++
            ComputedFingerprint(RAW, "AQAAsession")
        }
        assertThat(cache.cached(KEY)).isNull()

        val computed = cache.compute(file)
        cache.keepAcoustIdTrack(KEY, TRACK)

        assertThat(computed.encoded).isEqualTo("AQAAsession")
        assertThat(cache.cached(KEY)!!.raw).isEqualTo(RAW)
        assertThat(cache.acoustIdTrack(KEY)).isEqualTo(TRACK)
        assertThat(cache.cached("cd".repeat(32))).isNull()
        assertThat(decodes).isEqualTo(1)
    }

    private companion object {
        val KEY = "ab".repeat(32)
        const val TRACK = "6f1c1f4e-3b0d-4c8e-9a52-000000000009"
        val RAW = IntArray(948) { it * 7 }
    }
}
