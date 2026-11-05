package io.github.lorddetson.piplayer.tagging.contentkey

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.fingerprint.MediaCodecPcmSource
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.tagging.TaggerTesting.readFile
import io.github.lorddetson.piplayer.tagging.TaggerTesting.report
import java.io.File
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Exact duration on real files: TI §11.2, M2a. */
@RunWith(AndroidJUnit4::class)
class ExactDurationDeviceTest {

    private val context = TestFiles.targetContext
    private val opened = TaggerTesting.freshDatabase("exact-duration-test.db")
    private val database = opened.first
    private val client: TaggerClient = TaggerTesting.client(database, 30.seconds)
    private val cache = context.cacheDir

    @After
    fun close() {
        client.close()
        database.close()
    }

    private fun bytes(asset: String) = TestFiles.assets.open(asset).use { it.readBytes() }

    private fun file(name: String, content: ByteArray) = File(cache, name).apply { writeBytes(content) }

    private fun plan(file: File): DurationPlan =
        requireNotNull(client.readFile(file).report().content) { "No content report for ${file.name}" }.duration

    private fun walked(file: File, plan: DurationPlan): Long = TestFiles.open(file).use { descriptor ->
        val target = TagTarget.Outside(file.path, file.length(), file.lastModified())
        val result = runBlocking { client.walkDuration(target, descriptor, plan) }
        assertThat(result).describedAs(file.name).isInstanceOf(TaggerResult.Replied::class.java)
        requireNotNull((result as TaggerResult.Replied).value) { "The walk of ${file.name} found no length" }
    }

    /** The length of what the platform's decoder makes of [file]. */
    private fun decodedMs(file: File): Long = TestFiles.open(file).use { descriptor ->
        var frames = 0L
        var rate = 0
        MediaCodecPcmSource(context, descriptor.fileDescriptor).decode { pcm, format ->
            frames += pcm.remaining() / format.bytesPerFrame
            rate = format.sampleRate
            true
        }
        frames * 1_000 / rate
    }

    /** Removes the first frame of an MPEG-1 Layer III file that starts with one. */
    private fun withoutFirstFrame(mp3: ByteArray): ByteArray {
        val rates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val kbps = rates[(mp3[2].toInt() and 0xFF) shr 4]
        val sampleRates = intArrayOf(44_100, 48_000, 32_000)
        val rate = sampleRates[(mp3[2].toInt() shr 2) and 3]
        val padding = (mp3[2].toInt() shr 1) and 1
        return mp3.copyOfRange(144 * kbps * 1_000 / rate + padding, mp3.size)
    }

    private fun assertNear(actual: Long, expected: Long, toleranceMs: Long, what: String) {
        assertThat(abs(actual - expected)).describedAs("$what: $actual ms against $expected ms").isLessThanOrEqualTo(toleranceMs)
    }

    @Test
    fun vbrMp3WithoutAFrameCountHeaderIsWalkedToItsDecodedLength() {
        val vbr = file("vbr-no-header.mp3", withoutFirstFrame(bytes("playback/seek-mp3-vbr-xing.mp3")))
        val taglibMs = client.readFile(vbr).report().audio!!.lengthMs

        val plan = plan(vbr)
        val decoded = decodedMs(vbr)

        assertThat(plan).isEqualTo(DurationPlan(null, DurationCheck.WALK))
        // Without the walk, TagLib's estimate from the first frame's bitrate is far off.
        assertThat(abs(taglibMs - decoded)).describedAs("TagLib said $taglibMs ms, decoded $decoded ms").isGreaterThan(500)
        assertNear(walked(vbr, plan), decoded, MP3_FRAME_MS, "the walk")
    }

    @Test
    fun cbrMp3WithoutAHeaderIsWalkedToItsDecodedLength() {
        val cbr = TestFiles.copy("playback/seek-mp3-cbr-no-header.mp3")

        val plan = plan(cbr)

        assertThat(plan.check).isEqualTo(DurationCheck.WALK)
        assertNear(walked(cbr, plan), decodedMs(cbr), MP3_FRAME_MS, "the walk")
    }

    @Test
    fun rawAdtsIsWalkedToItsDecodedLength() {
        val adts = TestFiles.copy("playback/seek-aac-adts.aac")

        val plan = plan(adts)

        assertThat(plan).isEqualTo(DurationPlan(null, DurationCheck.WALK))
        assertNear(walked(adts, plan), decodedMs(adts), AAC_FRAME_MS, "the walk")
    }

    @Test
    fun mp3WithAHeaderTakesItWithoutAWalk() {
        for (asset in listOf("playback/seek-mp3-vbr-xing.mp3", "playback/seek-mp3-vbr-xing-without-toc.mp3", "contentkey/long.mp3")) {
            val mp3 = TestFiles.copy(asset)

            val plan = plan(mp3)

            assertThat(plan.check).describedAs(asset).isEqualTo(DurationCheck.NONE)
            assertNear(plan.headerMs!!, decodedMs(mp3), 2 * MP3_FRAME_MS, asset)
        }
    }

    @Test
    fun aVbrHeaderWithAWrongByteCountIsWalked() {
        val data = bytes("playback/seek-mp3-vbr-xing.mp3")
        val tag = String(data, 0, 2_000, Charsets.ISO_8859_1).indexOf("Xing")
        // The byte count follows the tag, the flags and the frame count.
        for (at in tag + 12 until tag + 16) data[at] = 0x7F
        val wrong = file("wrong-bytes.mp3", data)

        val plan = plan(wrong)

        assertThat(plan.check).isEqualTo(DurationCheck.WALK)
        assertNear(walked(wrong, plan), 12_000, 2 * MP3_FRAME_MS, "the walk")
    }

    @Test
    fun oggFormatsTakeTheirFinalGranule() {
        for ((asset, seconds) in listOf("contentkey/long.ogg" to 20_000L, "contentkey/long.opus" to 20_000L)) {
            val plan = plan(TestFiles.copy(asset))

            assertThat(plan).describedAs(asset).isEqualTo(DurationPlan(seconds, DurationCheck.NONE))
        }
    }

    @Test
    fun aWholeFlacKeepsItsHeaderValueAfterTheCheckOfItsEnd() {
        val flac = TestFiles.copy("contentkey/long.flac")

        val plan = plan(flac)

        assertThat(plan.check).isEqualTo(DurationCheck.FLAC_END)
        assertThat(plan.headerMs).isEqualTo(3_000)
        assertThat(walked(flac, plan)).isEqualTo(3_000)
    }

    @Test
    fun aFlacCutOffMoreThan2SecondsBeforeItsHeaderLengthReportsWhatIsLeft() {
        val whole = bytes("contentkey/long.flac")
        val cut = file("cut.flac", whole.copyOf(whole.size / 4))

        val plan = plan(cut)
        val walked = walked(cut, plan)

        assertThat(plan.headerMs).isEqualTo(3_000)
        // A quarter of the file, less its 8 KB of metadata, at 125 KB/s.
        assertNear(walked, 700, FLAC_FRAME_MS, "the walk")
    }

    @Test
    fun aFlacCutOffLessThan2SecondsBeforeItsHeaderLengthKeepsTheHeader() {
        val whole = bytes("contentkey/long.flac")
        val cut = file("cut-slightly.flac", whole.copyOf(whole.size / 4 * 3))

        val plan = plan(cut)

        assertThat(walked(cut, plan)).isEqualTo(3_000)
    }

    @Test
    fun aWavCutOffReportsWhatIsLeft() {
        val whole = bytes("decoding/wav-44100-16bit-stereo.wav")
        val cut = file("cut.wav", whole.copyOf(whole.size / 2))

        val plan = plan(cut)

        assertThat(plan).isEqualTo(DurationPlan(null, DurationCheck.WALK))
        // The extractor hands out samples of up to a tenth of a second.
        assertNear(walked(cut, plan), 1_000, WAV_SAMPLE_MS, "the walk")
    }

    @Test
    fun anMp4CutOffReportsWhatIsLeft() {
        val whole = bytes("contentkey/moov-first.m4a")
        val cut = file("cut.m4a", whole.copyOf(whole.size / 2))

        val plan = plan(cut)
        val walked = walked(cut, plan)

        assertThat(plan).isEqualTo(DurationPlan(null, DurationCheck.WALK))
        assertNear(walked, decodedMs(cut), AAC_FRAME_MS, "the walk")
        assertThat(walked).isBetween(8_000, 12_000)
    }

    @Test
    fun aFileNoExtractorCanReadHasNoLength() {
        val garbage = file("garbage.mp3", ByteArray(4_096) { (it * 7).toByte() })
        val target = TagTarget.Outside(garbage.path, garbage.length(), garbage.lastModified())

        val result = TestFiles.open(garbage).use { descriptor ->
            runBlocking { client.walkDuration(target, descriptor, DurationPlan(null, DurationCheck.WALK)) }
        }

        assertThat(result).isEqualTo(TaggerResult.Replied(null))
    }

    /** A long raw AAC stream made by repeating one file. */
    private fun largeAdts(name: String, megabytes: Int): File {
        val once = bytes("playback/seek-aac-adts.aac")
        return File(cache, name).apply {
            outputStream().buffered().use { out -> repeat(megabytes * 1_048_576 / once.size) { out.write(once) } }
        }
    }

    @Test
    fun aWalkTakesLittleTimeAndIsInterrupted() {
        val once = walked(TestFiles.copy("playback/seek-aac-adts.aac"), DurationPlan(null, DurationCheck.WALK))
        val large = largeAdts("large.aac", 64)
        val target = TagTarget.Outside(large.path, large.length(), large.lastModified())
        val plan = DurationPlan(null, DurationCheck.WALK)

        val start = TimeSource.Monotonic.markNow()
        val length = walked(large, plan)
        Log.i(TAG, "The walk of ${large.length() / 1_048_576} MiB took ${start.elapsedNow()}, length $length ms")
        val repeats = large.length() / bytes("playback/seek-aac-adts.aac").size
        // Each repeat may round its millisecond the other way.
        assertNear(length, once * repeats, repeats, "the walk")

        val pidBefore = TaggerTesting.taggerPid()
        runBlocking(Dispatchers.Default) {
            TestFiles.open(large).use { descriptor ->
                val walk = async { client.walkDuration(target, descriptor, plan) }
                delay(50)
                walk.cancelAndJoin()
            }
        }

        // The process dies soon after the kill.
        if (pidBefore != null) eventually("the killed :tagger is gone") { !TaggerTesting.processExists(pidBefore) }
        // The interruption proved nothing about the file, which stays readable.
        assertThat(client.readFile(TestFiles.copy("contentkey/long.flac")).report().content).isNotNull()
        assertThat(runBlocking { TaggerTesting.counts(database).count(target) }).isEqualTo(0)
    }

    private companion object {
        const val TAG = "ExactDurationTest"
        const val MP3_FRAME_MS = 27L
        const val AAC_FRAME_MS = 24L
        const val FLAC_FRAME_MS = 100L
        const val WAV_SAMPLE_MS = 101L
    }
}
