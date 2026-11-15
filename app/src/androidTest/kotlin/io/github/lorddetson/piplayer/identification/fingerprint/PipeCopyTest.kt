package io.github.lorddetson.piplayer.identification.fingerprint

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.fingerprint.DecodingException.CopyRefused.Reason
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A descriptor that is not a regular file, a pipe as a document provider may hand out, is copied into the cache before
 * Media3's extractor reads it (TI §3.2), within the copy's bounds, and the copy is deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PipeCopyTest {

    private val context = TestFiles.targetContext
    private val made = mutableListOf<File>()
    private val open = mutableListOf<ParcelFileDescriptor>()
    private val writers = mutableListOf<Thread>()
    private val streams = mutableListOf<ParcelFileDescriptor.AutoCloseOutputStream>()

    /** The directory of the app's own copies, emptied before each test. */
    private val copies = File(context.cacheDir, DescriptorCopy.DIRECTORY)

    /** A directory of this test's own, for copies with other bounds. */
    private val testCopies = File(context.cacheDir, "pipe-copy-test")

    @Before
    fun clean() {
        copies.deleteRecursively()
        testCopies.deleteRecursively()
    }

    @After
    fun close() {
        streams.forEach { runCatching { it.close() } }
        open.forEach { runCatching { it.close() } }
        writers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
        made.forEach { it.delete() }
        testCopies.deleteRecursively()
    }

    private fun leftovers(directory: File): List<String> = directory.list()?.toList().orEmpty()

    /**
     * The read end of a new pipe; its own thread writes [bytes] into the other end and then closes it, or with
     * [keepOpen] leaves it open without writing more, as a provider that stalls.
     */
    private fun pipe(bytes: ByteArray, keepOpen: Boolean = false): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        open += read
        open += write
        // Held until the test ends: a stream left to the garbage collector closes the pipe when it is finalized, which
        // ended the stalling writer early on API 30.
        val out = ParcelFileDescriptor.AutoCloseOutputStream(write).also { streams += it }
        writers += Thread({
            try {
                out.write(bytes)
                out.flush()
                if (!keepOpen) out.close()
            } catch (_: IOException) {
                // The reader gave up and closed its end.
            }
        }, "pipe-writer").apply { isDaemon = true; start() }
        return read
    }

    private fun fingerprint(descriptor: ParcelFileDescriptor): FingerprintResult =
        fingerprint(MediaCodecPcmSource(context, descriptor.fileDescriptor, checkError = descriptor::checkError))

    private fun fingerprint(source: PcmSource): FingerprintResult =
        runBlocking { NativeFingerprinting.calculator().fingerprint(source) }

    /**
     * The read end of a new reliable pipe, as some document providers hand out (USB or MTP storage): its own thread
     * writes [bytes] into the other end and then closes it with [error], as a provider whose device went away does.
     */
    private fun failingReliablePipe(bytes: ByteArray, error: String): ParcelFileDescriptor {
        val (read, writeEnd) = ParcelFileDescriptor.createReliablePipe()
        open += read
        open += writeEnd
        writers += Thread({
            try {
                // Not owning the descriptor, the stream leaves it to closeWithError.
                FileOutputStream(writeEnd.fileDescriptor).apply {
                    write(bytes)
                    flush()
                }
                writeEnd.closeWithError(error)
            } catch (_: IOException) {
                // The reader gave up and closed its end.
            }
        }, "pipe-writer").apply { isDaemon = true; start() }
        return read
    }

    private fun fixture(name: String): File = TestFiles.copy("decoding/$name").also { made += it }

    /** 12 s of a tune, a new random note every quarter second: long enough for a fingerprint with items. */
    private fun tuneWav(): File {
        val rate = 22_050
        val random = Random(5)
        val samples = ShortArray(rate * 12)
        var frequency = 440.0
        for (i in samples.indices) {
            if (i % (rate / 4) == 0) frequency = 200.0 + random.nextInt(1_800)
            samples[i] = (sin(2 * PI * frequency * i / rate) * 12_000).toInt().toShort()
        }
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        return File(context.cacheDir, "pipe-tune.wav").apply { writeBytes(header.array() + data.array()) }
            .also { made += it }
    }

    private fun assertThroughAPipeAsFromTheFile(file: File) {
        val fromFile = TestFiles.open(file).use { fingerprint(it) }

        val fromPipe = fingerprint(pipe(file.readBytes()))

        assertThat(fromPipe.source).isEqualTo(fromFile.source)
        assertThat(fromPipe.fedSamples).isEqualTo(fromFile.fedSamples)
        assertThat(fromPipe.inputFormat).isEqualTo(fromFile.inputFormat)
        assertThat(fromPipe.reachedEnd).isEqualTo(fromFile.reachedEnd).isTrue()
        assertThat(fromPipe.fingerprint.raw).isEqualTo(fromFile.fingerprint.raw)
        assertThat(leftovers(copies)).isEmpty()
    }

    @Test
    fun aFlacFileThroughAPipeDecodesAsTheFileItself() =
        assertThroughAPipeAsFromTheFile(fixture("flac-96000-24bit-stereo.flac"))

    /** Its index (`moov`) comes after the audio, so the extractor must seek back. */
    @Test
    fun anMp4FileWithItsIndexAtTheEndThroughAPipeDecodesAsTheFileItself() =
        assertThroughAPipeAsFromTheFile(fixture("aac-44100-stereo.m4a"))

    @Test
    fun aWavFileThroughAPipeGivesTheSameFingerprintAsTheFileItself() {
        val file = tuneWav()

        assertThroughAPipeAsFromTheFile(file)
        assertThat(TestFiles.open(file).use { fingerprint(it) }.fingerprint.raw).isNotEmpty()
    }

    @Test
    fun aRegularFileIsReadWithoutACopy() {
        val file = fixture("flac-96000-24bit-stereo.flac")
        TestFiles.open(file).use { descriptor ->
            var handed: FileDescriptor? = null

            DescriptorCopy(testCopies).readable(descriptor.fileDescriptor, checkError = descriptor::checkError) {
                handed = it
            }

            assertThat(handed).isSameAs(descriptor.fileDescriptor)
        }
        assertThat(leftovers(testCopies)).isEmpty()
    }

    @Test
    fun aPipeIsReadFromACopyThatIsGoneAfterwards() {
        val bytes = ByteArray(100_000) { it.toByte() }
        val read = pipe(bytes)
        var handed: FileDescriptor? = null
        var copied: ByteArray? = null

        DescriptorCopy(testCopies).readable(read.fileDescriptor, checkError = read::checkError) { fd ->
            handed = fd
            copied = ParcelFileDescriptor.dup(fd).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }

        assertThat(handed).isNotSameAs(read.fileDescriptor)
        assertThat(copied).isEqualTo(bytes)
        assertThat(leftovers(testCopies)).isEmpty()
    }

    @Test
    fun aPipeLongerThanTheSizeBoundIsRefusedWithoutACopyLeft() {
        val read = pipe(ByteArray(50_000))
        var called = false

        val copies = DescriptorCopy(testCopies, maxBytes = 10_000)

        assertThatThrownBy { copies.readable(read.fileDescriptor, checkError = read::checkError) { called = true } }
            .isInstanceOfSatisfying(DecodingException.CopyRefused::class.java) {
                assertThat(it.reason).isEqualTo(Reason.TOO_LARGE)
            }
        assertThat(called).isFalse()
        assertThat(leftovers(testCopies)).isEmpty()
    }

    /** A provider that stops writing without closing its end cannot hang the copy: it ends at its deadline. */
    @Test
    fun aPipeWhoseWriterStallsIsRefusedAtTheDeadlineWithoutACopyLeft() {
        val read = pipe(ByteArray(1_000), keepOpen = true)

        val copies = DescriptorCopy(testCopies, maxMillis = 1_000)

        assertThatThrownBy { copies.readable(read.fileDescriptor, checkError = read::checkError) { } }
            .isInstanceOfSatisfying(DecodingException.CopyRefused::class.java) {
                assertThat(it.reason).isEqualTo(Reason.TOO_SLOW)
            }
        assertThat(leftovers(testCopies)).isEmpty()
    }

    @Test
    fun aCancelledCopyOfAPipeStopsWithoutACopyLeft() {
        val read = pipe(ByteArray(1_000), keepOpen = true)
        val asked = AtomicInteger()

        assertThatThrownBy {
            DescriptorCopy(testCopies).readable(
                read.fileDescriptor, cancelled = { asked.incrementAndGet() > 3 }, checkError = read::checkError,
            ) { }
        }.isInstanceOf(CancellationException::class.java)
        assertThat(leftovers(testCopies)).isEmpty()
    }

    /** Its end reads like a normal one; only the descriptor can tell that the writer failed (TI §3.2). */
    @Test
    fun aReliablePipeWhoseWriterFailsIsRefusedWithoutACopyLeft() {
        val read = failingReliablePipe(ByteArray(50_000) { it.toByte() }, "The device was unplugged")
        var called = false

        assertThatThrownBy {
            DescriptorCopy(testCopies).readable(read.fileDescriptor, checkError = read::checkError) { called = true }
        }.isInstanceOfSatisfying(DecodingException.Failed::class.java) {
            assertThat(it).hasMessageContaining("The device was unplugged")
        }
        assertThat(called).isFalse()
        assertThat(leftovers(testCopies)).isEmpty()
    }

    /** Half a WAV file would decode, shorter: the decoding source refuses it, so no shortened fingerprint is made. */
    @Test
    fun aReliablePipeWhoseWriterFailsIsNotDecoded() {
        val wav = tuneWav().readBytes()
        val read = failingReliablePipe(wav.copyOf(wav.size / 2), "The device was unplugged")

        assertThatThrownBy {
            fingerprint(MediaCodecPcmSource(context, read.fileDescriptor, checkError = read::checkError))
        }.isInstanceOfSatisfying(DecodingException.Failed::class.java) {
            assertThat(it).hasMessageContaining("The device was unplugged")
        }
        assertThat(leftovers(copies)).isEmpty()
    }

    @Test
    fun aCopyAnHourOldIsSweptByTheNextCopy() {
        testCopies.mkdirs()
        val old = File(testCopies, "copy-old").apply { writeBytes(ByteArray(10)) }
        old.setLastModified(System.currentTimeMillis() - DescriptorCopy.STALE_MILLIS - 60_000)
        val recent = File(testCopies, "copy-recent").apply { writeBytes(ByteArray(10)) }

        val read = pipe(ByteArray(10))
        DescriptorCopy(testCopies).readable(read.fileDescriptor, checkError = read::checkError) { }

        assertThat(leftovers(testCopies)).containsExactly(recent.name)
    }
}
