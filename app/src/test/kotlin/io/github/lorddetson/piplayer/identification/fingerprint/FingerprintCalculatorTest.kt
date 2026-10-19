package io.github.lorddetson.piplayer.identification.fingerprint

import io.github.lorddetson.piplayer.nativelib.chromaprint.Fingerprint
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FingerprintCalculatorTest {

    private val info = SourceInfo("audio/raw", "fake.decoder", durationUs = null)

    private fun mono16(rate: Int) = PcmFormat(rate, 1, PcmEncoding.PCM_16BIT)

    private fun pcm16(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putShort(it.toShort()) }; flip() }

    private fun silence(frames: Int, channels: Int = 1): ByteBuffer = ByteBuffer.allocate(frames * channels * 2)

    /** Hands out its pieces in order and remembers how many the consumer took and whether it asked to stop. */
    private class FakeSource(private val pieces: List<Pair<ByteBuffer, PcmFormat>>, private val info: SourceInfo) :
        PcmSource {
        var delivered = 0
        var stoppedEarly = false
        var thread: Thread? = null

        override fun decode(consumer: (ByteBuffer, PcmFormat) -> Boolean): SourceInfo {
            thread = Thread.currentThread()
            for ((pcm, format) in pieces) {
                delivered++
                if (!consumer(pcm, format)) {
                    stoppedEarly = true
                    break
                }
            }
            return info
        }
    }

    /** Keeps every [ratio]-th sample; its flush returns [tail]. */
    private class FakeResampler(val inputRate: Int, val outputRate: Int, private val tail: FloatArray) : MonoResampler {
        private val ratio = inputRate / outputRate
        var processed = 0
        var flushed = false
        var closed = false

        override fun process(input: FloatArray, offset: Int, length: Int): FloatArray {
            check(!flushed && !closed)
            processed += length
            return FloatArray(length / ratio) { input[offset + it * ratio] }
        }

        override fun flush(): FloatArray {
            check(!flushed && !closed)
            flushed = true
            return tail
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeResamplers(private val tail: FloatArray = floatArrayOf(0.25f, 0.25f)) : ResamplerFactory {
        val created = mutableListOf<FakeResampler>()
        override fun create(inputRate: Int, outputRate: Int): MonoResampler =
            FakeResampler(inputRate, outputRate, tail).also { created += it }
    }

    private class FakeSink : FingerprintSink {
        val fed = mutableListOf<Short>()
        var finished = false
        var closed = false

        override fun feed(samples: ShortArray, length: Int) {
            check(!finished)
            repeat(length) { fed += samples[it] }
        }

        override fun finish(): Fingerprint {
            finished = true
            return Fingerprint(IntArray(fed.size / 1000), "fake")
        }

        override fun close() {
            closed = true
        }
    }

    private fun calculator(resamplers: ResamplerFactory, sink: FakeSink, maxSeconds: Int = 120) =
        FingerprintCalculator(resamplers, { sink }, maxSeconds, Dispatchers.Default)

    @Test
    fun `audio at 11025 Hz is fed as it is, without a resampler`() = runTest {
        val resamplers = FakeResamplers()
        val sink = FakeSink()
        val source = FakeSource(listOf(pcm16(0, 16_384, -16_384) to mono16(11_025)), info)

        val result = calculator(resamplers, sink).fingerprint(source)

        assertThat(resamplers.created).isEmpty()
        assertThat(sink.fed).containsExactly(0, 16_384, -16_384)
        assertThat(sink.finished).isTrue()
        assertThat(result.fedSamples).isEqualTo(3)
        assertThat(result.inputFormat).isEqualTo(mono16(11_025))
        assertThat(result.source).isEqualTo(info)
        assertThat(result.fingerprint.encoded).isEqualTo("fake")
        assertThat(result.reachedEnd).isTrue()
    }

    @Test
    fun `other rates go through one resampler, whose tail is fed at the end of the stream`() = runTest {
        val resamplers = FakeResamplers()
        val sink = FakeSink()
        val stereo = PcmFormat(44_100, 2, PcmEncoding.PCM_16BIT)
        // Eight stereo frames: both channels 0.5, then both -0.5.
        val first = pcm16(16_384, 16_384, 16_384, 16_384, 16_384, 16_384, 16_384, 16_384)
        val second = pcm16(-16_384, -16_384, -16_384, -16_384, -16_384, -16_384, -16_384, -16_384)
        val source = FakeSource(listOf(first to stereo, second to stereo), info)

        val result = calculator(resamplers, sink).fingerprint(source)

        val resampler = resamplers.created.single()
        assertThat(resampler.inputRate).isEqualTo(44_100)
        assertThat(resampler.outputRate).isEqualTo(11_025)
        assertThat(resampler.processed).isEqualTo(8)
        assertThat(resampler.flushed).isTrue()
        assertThat(resampler.closed).isTrue()
        assertThat(sink.fed).containsExactly(16_384, -16_384, 8_192, 8_192)
        assertThat(result.fedSamples).isEqualTo(4)
        assertThat(result.inputFormat).isEqualTo(stereo)
        assertThat(sink.closed).isTrue()
    }

    @Test
    fun `decoding stops once the time limit is reached, without the tail`() = runTest {
        val resamplers = FakeResamplers()
        val sink = FakeSink()
        val pieces = List(4) { silence(5_000) to mono16(11_025) }
        val source = FakeSource(pieces, info)

        val result = calculator(resamplers, sink, maxSeconds = 1).fingerprint(source)

        assertThat(result.fedSamples).isEqualTo(11_025)
        assertThat(sink.fed).hasSize(11_025)
        assertThat(source.stoppedEarly).isTrue()
        assertThat(source.delivered).isEqualTo(3)
        assertThat(result.reachedEnd).isFalse()
    }

    @Test
    fun `the limit also cuts the resampler's tail`() = runTest {
        val resamplers = FakeResamplers(tail = FloatArray(100) { 0.5f })
        val sink = FakeSink()
        val source = FakeSource(listOf(silence(4 * 11_000) to mono16(44_100)), info)

        val result = calculator(resamplers, sink, maxSeconds = 1).fingerprint(source)

        assertThat(result.fedSamples).isEqualTo(11_025)
        assertThat(sink.fed.takeLast(25)).containsOnly(16_384)
        assertThat(sink.fed[10_999]).isEqualTo(0)
    }

    @Test
    fun `a decoder that stopped early does not count as having reached the end`() = runTest {
        val cut = info.copy(endedEarly = "The decoder failed after 12 buffers")
        val source = FakeSource(listOf(silence(4) to mono16(11_025)), cut)

        val result = calculator(FakeResamplers(), FakeSink()).fingerprint(source)

        assertThat(result.reachedEnd).isFalse()
        assertThat(result.source.endedEarly).isEqualTo("The decoder failed after 12 buffers")
        assertThat(result.fedSamples).isEqualTo(4)
    }

    @Test
    fun `a format change ends the old resampler and starts a new one`() = runTest {
        val resamplers = FakeResamplers(tail = floatArrayOf(0.5f))
        val sink = FakeSink()
        val source = FakeSource(
            listOf(silence(8) to mono16(44_100), silence(8) to mono16(88_200), silence(4) to mono16(11_025)),
            info,
        )

        val result = calculator(resamplers, sink).fingerprint(source)

        assertThat(resamplers.created.map { it.inputRate }).containsExactly(44_100, 88_200)
        assertThat(resamplers.created).allSatisfy {
            assertThat(it.flushed).isTrue()
            assertThat(it.closed).isTrue()
        }
        // 2 + tail, 1 + tail, then 4 passed through.
        assertThat(sink.fed).containsExactly(0, 0, 16_384, 0, 16_384, 0, 0, 0, 0)
        assertThat(result.inputFormat).isEqualTo(mono16(44_100))
    }

    @Test
    fun `a source without any PCM still gives a (silent) result`() = runTest {
        val resamplers = FakeResamplers()
        val sink = FakeSink()

        val result = calculator(resamplers, sink).fingerprint(FakeSource(emptyList(), info))

        assertThat(resamplers.created).isEmpty()
        assertThat(sink.finished).isTrue()
        assertThat(result.fedSamples).isZero()
        assertThat(result.inputFormat).isNull()
        assertThat(result.fingerprint.raw).isEmpty()
    }

    @Test
    fun `the resampler and the sink are closed when decoding fails`() = runTest {
        val resamplers = FakeResamplers()
        val sink = FakeSink()
        val failing = PcmSource { consumer ->
            consumer(silence(8), mono16(44_100))
            throw IOException("decoder died")
        }

        val error = runCatching { calculator(resamplers, sink).fingerprint(failing) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java).hasMessage("decoder died")
        assertThat(resamplers.created.single().closed).isTrue()
        assertThat(sink.closed).isTrue()
        assertThat(sink.finished).isFalse()
    }

    @Test
    fun `decoding runs on the given dispatcher`() = runTest {
        val dispatcher = object : CoroutineDispatcher() {
            @Volatile var used = false
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                used = true
                Dispatchers.IO.dispatch(context, block)
            }
        }
        val source = FakeSource(listOf(silence(4) to mono16(11_025)), info)

        FingerprintCalculator(FakeResamplers(), { FakeSink() }, dispatcher = dispatcher).fingerprint(source)

        assertThat(dispatcher.used).isTrue()
    }

    @Test
    fun `cancelling the caller stops the decoder at its next piece`() = runTest {
        val firstPiece = CountDownLatch(1)
        val release = CountDownLatch(1)
        var seen: Throwable? = null
        var pieces = 0
        val endless = PcmSource { consumer ->
            try {
                while (pieces < 1_000_000) {
                    pieces++
                    consumer(silence(10), mono16(11_025))
                    if (pieces == 1) {
                        firstPiece.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
            } catch (e: Throwable) {
                seen = e
                throw e
            }
            info
        }
        val sink = FakeSink()
        val job = async(Dispatchers.Default) { calculator(FakeResamplers(), sink).fingerprint(endless) }

        assertThat(firstPiece.await(5, TimeUnit.SECONDS)).isTrue()
        job.cancel()
        release.countDown()
        job.cancelAndJoin()

        assertThat(seen).isInstanceOf(CancellationException::class.java)
        assertThat(pieces).isLessThan(10)
        assertThat(sink.closed).isTrue()
    }
}
