package io.github.lorddetson.piplayer.identification.fingerprint

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.LiveNetwork
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.net.AndroidConnectivity
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
 * the bits may differ. The recordings and fpcalc's output come from scripts/prepare_fingerprint_samples.py (mode
 * "files") through the prepareRecordings task.
 *
 * The fingerprint must also find the recording's AcoustID track, but that is a real call to AcoustID: it runs only with
 * `-e liveNetwork true` and an AcoustID key in local.properties, never by default (worktrees copy local.properties, key
 * included) and never on CI, which has no key.
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
        fun samples(): List<Array<Any>> {
            check(REQUIRED.containsAll(CHANNELS.keys)) {
                "Channel counts of unknown recordings: ${CHANNELS.keys - REQUIRED}"
            }
            return (index.keys + REQUIRED).sorted().map { arrayOf(it) }
        }

        /**
         * The recordings TI §3.2 and §11.2 ask for, so that none drops out of the manifest unnoticed: the five CC
         * recordings of M0 and, since M3, a 5.1 AAC file, a stereo AAC file and an MP3 without a LAME or Xing header,
         * which the preparation script makes from them.
         */
        private val REQUIRED = setOf(
            "goldberg-aria-da-capo", "grieg-morning-mood", "figaro-overture", "art-of-fugue-contrapunctus-1",
            "radetzky-march", "figaro-overture-5.1-aac", "art-of-fugue-aac", "grieg-morning-mood-mp3-no-header",
        )

        /**
         * Channel counts the decoder must hand on: the 5.1 recording is there for the downmix of all six channels
         * (TI §3.2), which a decoder that downmixes to stereo itself would leave untested while fpcalc still matches.
         */
        private val CHANNELS = mapOf("figaro-overture-5.1-aac" to 6)

        @JvmStatic
        @AfterClass
        fun forget() = results.clear()
    }

    private val entry
        get() = index[sample] ?: throw AssertionError("$sample is not prepared: check nativelib/fingerprint-samples.json")

    private fun fingerprint(): FingerprintResult = results.getOrPut(sample) {
        val file: File = TestFiles.copy("recordings/${entry.getString("file")}")
        try {
            TestFiles.open(file).use { pfd ->
                val source = MediaCodecPcmSource(TestFiles.targetContext, pfd.fileDescriptor, checkError = pfd::checkError)
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
    fun reachesTheDownmixWithAllItsChannels() {
        val channels = CHANNELS[sample]
        assumeTrue("$sample: no channel count pinned", channels != null)

        assertThat(fingerprint().inputFormat?.channelCount).isEqualTo(channels)
    }

    @Test
    fun findsTheRecordingsAcoustIdTrack() {
        assumeTrue(
            "Calls AcoustID; run with -e ${LiveNetwork.ARGUMENT} true",
            LiveNetwork.allowed(InstrumentationRegistry.getArguments()),
        )
        assumeTrue("No AcoustID key in local.properties", BuildConfig.ACOUSTID_API_KEY.isNotBlank())
        val result = fingerprint()
        // The argument is the consent: the test runs only when asked, with a key in local.properties (never on CI).
        val gate = TestServices.openGate()
        val client = AcoustIdClient(
            HttpClients.create(UserAgent.of(BuildConfig.VERSION_NAME), gate),
            RateLimiter(AcoustIdClient.MIN_INTERVAL),
            RetryPolicy(maxRetries = 3),
            TestServices.access(ProviderId.ACOUSTID, gate = gate, connectivity = AndroidConnectivity(TestFiles.targetContext)),
            BuildConfig.ACOUSTID_API_KEY,
        )
        val durationMs = (result.source.durationUs ?: error("$sample: no duration")) / 1_000

        val response = runBlocking { client.lookup(result.fingerprint.encoded, durationMs) }
        Log.i(TAG, "$sample: AcoustID ${response.results.map { "${it.id} ${it.score}" }}")

        assertThat(response.results.map { it.id }).contains(entry.getString("acoustidTrack"))
    }
}
