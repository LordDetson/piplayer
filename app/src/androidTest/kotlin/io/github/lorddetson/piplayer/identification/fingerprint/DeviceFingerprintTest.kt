package io.github.lorddetson.piplayer.identification.fingerprint

import android.util.Log
import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.UserAgent
import io.github.lorddetson.piplayer.nativelib.chromaprint.FingerprintComparison
import java.io.File
import java.io.FileNotFoundException
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The whole fingerprint path on the device against the desktop fpcalc 1.6.2 on the same file (TI §11.2, M0): Media3's
 * extractor and the platform decoder instead of FFmpeg, speexdsp's resampler instead of swresample. At most 10 % of
 * the bits may differ, and the fingerprint must find the recording's AcoustID track. The recordings and fpcalc's
 * output come from scripts/prepare_fingerprint_samples.py (mode "files") through the prepareRecordings task.
 */
@RunWith(Parameterized::class)
class DeviceFingerprintTest(private val sample: String) {

    companion object {
        const val MAX_BIT_ERROR_RATE = 0.10
        private const val TAG = "DeviceFingerprintTest"

        private val index: Map<String, JSONObject> by lazy {
            val text = try {
                TestFiles.assets.open("recordings/index.json").bufferedReader().readText()
            } catch (e: FileNotFoundException) {
                throw AssertionError("No prepared recordings: run scripts/prepare_fingerprint_samples.py --mode files", e)
            }
            val entries = JSONArray(text)
            List(entries.length()) { entries.getJSONObject(it) }.associateBy { it.getString("id") }
        }

        /** One fingerprint per recording, shared by both tests. */
        private val results = mutableMapOf<String, FingerprintResult>()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun samples(): List<Array<Any>> = index.keys.sorted().map { arrayOf(it) }

        @JvmStatic
        @AfterClass
        fun forget() = results.clear()
    }

    private val entry get() = index.getValue(sample)

    private fun fingerprint(): FingerprintResult = results.getOrPut(sample) {
        val file: File = TestFiles.copy("recordings/${entry.getString("file")}")
        try {
            TestFiles.open(file).use { pfd ->
                val source = MediaCodecPcmSource(TestFiles.targetContext, pfd.fileDescriptor)
                val (result, time) = measureTimedValue {
                    runBlocking { NativeFingerprinting.calculator().fingerprint(source) }
                }
                val items = result.fingerprint.raw.size
                Log.i(TAG, "$sample: $items items from ${result.inputFormat} via ${result.source.decoderName} in $time")
                result
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun matchesFpcalcOnTheSameFile() {
        val reference = JSONObject(TestFiles.assets.open("recordings/$sample.fpcalc.json").bufferedReader().readText())
            .getJSONArray("fingerprint")
            .let { items -> IntArray(items.length()) { items.getLong(it).toInt() } }

        val ours = fingerprint().fingerprint.raw
        val match = FingerprintComparison.bestMatch(ours, reference)!!
        Log.i(TAG, "$sample: ${ours.size} items, fpcalc ${reference.size}, $match")

        assertThat(ours.size).isCloseTo(reference.size, within(2))
        assertThat(match.offset).isBetween(-1, 1)
        assertThat(match.bitErrorRate).isLessThanOrEqualTo(MAX_BIT_ERROR_RATE)
    }

    @Test
    fun findsTheRecordingsAcoustIdTrack() {
        assumeTrue("No AcoustID key in local.properties", BuildConfig.ACOUSTID_API_KEY.isNotBlank())
        val result = fingerprint()
        val client = AcoustIdClient(
            HttpClients.create(UserAgent.of(BuildConfig.VERSION_NAME)),
            RateLimiter(AcoustIdClient.MIN_INTERVAL),
            RetryPolicy(maxRetries = 3),
            BuildConfig.ACOUSTID_API_KEY,
        )
        val durationMs = (result.source.durationUs ?: error("$sample: no duration")) / 1_000

        val response = runBlocking { client.lookup(result.fingerprint.encoded, durationMs) }
        Log.i(TAG, "$sample: AcoustID ${response.results.map { "${it.id} ${it.score}" }}")

        assertThat(response.results.map { it.id }).contains(entry.getString("acoustidTrack"))
    }
}
