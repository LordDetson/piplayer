package io.github.lorddetson.piplayer.nativelib.chromaprint

import android.util.Log
import io.github.lorddetson.piplayer.nativelib.TestFiles
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Our Chromaprint build against the desktop fpcalc 1.6.2 on byte-identical PCM (TI §11.2, M0): at most 10 % of the
 * bits may differ over the aligned overlap. The recordings and fpcalc's output come from
 * scripts/prepare_fingerprint_samples.py through the prepareFingerprintSamples task.
 */
@RunWith(Parameterized::class)
class ChromaprintFpcalcTest(private val sample: String) {

    companion object {
        const val MAX_BIT_ERROR_RATE = 0.10

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun samples(): List<Array<Any>> {
            val index = try {
                TestFiles.assets.open("fingerprints/index.json").bufferedReader().readText()
            } catch (e: FileNotFoundException) {
                throw AssertionError("No prepared samples: run scripts/prepare_fingerprint_samples.py", e)
            }
            val entries = JSONArray(index)
            return List(entries.length()) { arrayOf<Any>(entries.getJSONObject(it).getString("id")) }
        }
    }

    @Test
    fun matchesFpcalcOnTheSamePcm() {
        val bytes = TestFiles.assets.open("fingerprints/$sample.pcm").use { it.readBytes() }
        val pcm = ShortArray(bytes.size / 2).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
        }
        val reference = JSONObject(TestFiles.assets.open("fingerprints/$sample.fpcalc.json").bufferedReader().readText())
            .getJSONArray("fingerprint")
            .let { items -> IntArray(items.length()) { items.getLong(it).toInt() } }

        val ours = Chromaprint.fingerprint(pcm).raw
        val match = FingerprintComparison.bestMatch(ours, reference)!!
        Log.i("ChromaprintFpcalcTest", "$sample: ${ours.size} items, fpcalc ${reference.size}, $match")

        assertThat(ours.size).isEqualTo(reference.size)
        assertThat(match.offset).isEqualTo(0)
        assertThat(match.bitErrorRate).isLessThanOrEqualTo(MAX_BIT_ERROR_RATE)
    }
}
