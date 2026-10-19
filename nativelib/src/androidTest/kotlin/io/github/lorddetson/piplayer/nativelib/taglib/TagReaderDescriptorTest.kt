package io.github.lorddetson.piplayer.nativelib.taglib

import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.nativelib.TestFiles
import java.io.File
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Errors and descriptor ownership: the caller's fd is never closed or leaked, and bad input never crashes. */
@RunWith(AndroidJUnit4::class)
class TagReaderDescriptorTest {

    private val flac: File = TestFiles.copy("tags/vorbis-comment.flac")

    @Test
    fun aNegativeDescriptorIsInvalid() {
        assertThat(TagReader.read(-1)).isEqualTo(TagReadResult.Failure(TagReadError.INVALID_FD))
    }

    @Test
    fun aPipeIsNotSeekable() {
        val (read, write) = ParcelFileDescriptor.createPipe()
        write.use { read.use { assertThat(TagReader.read(it.fd)).isEqualTo(TagReadResult.Failure(TagReadError.NOT_SEEKABLE)) } }
    }

    @Test
    fun randomBytesAreAnUnsupportedFormat() {
        val garbage = TestFiles.write("garbage.bin", Random(1).nextBytes(64 * 1024))
        val result = TestFiles.open(garbage).use { TagReader.read(it.fd, "garbage.bin") }
        assertThat(result).isEqualTo(TagReadResult.Failure(TagReadError.UNSUPPORTED_FORMAT))
    }

    @Test
    fun randomBytesNamedLikeAnMp3AreAnUnsupportedFormat() {
        val garbage = TestFiles.write("garbage.mp3", Random(4).nextBytes(64 * 1024))
        val result = TestFiles.open(garbage).use { TagReader.read(it.fd, "garbage.mp3") }
        assertThat(result).isEqualTo(TagReadResult.Failure(TagReadError.UNSUPPORTED_FORMAT))
    }

    @Test
    fun aDescriptorOpenedForWritingOnlyIsAnIoError() {
        val copy = TestFiles.write("write-only.flac", flac.readBytes())
        val result = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_WRITE_ONLY).use {
            TagReader.read(it.fd, "write-only.flac")
        }
        assertThat(result).isEqualTo(TagReadResult.Failure(TagReadError.IO_ERROR))
    }

    @Test
    fun aTagSizeLargerThanTheFileDoesNotAllocateWhatItClaims() {
        // An ID3v2 header claiming 256 MiB (synchsafe 7F 7F 7F 7F) in front of a small MP3.
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F)
        val file = TestFiles.write("huge-tag.mp3", header + TestFiles.copy("tags/id3v1.mp3").readBytes())
        val before = peakResidentKb()
        TestFiles.open(file).use { TagReader.read(it.fd, "huge-tag.mp3") }
        assertThat(peakResidentKb() - before).isLessThan(64 * 1024)
    }

    @Test
    fun anEmptyFileIsAnUnsupportedFormat() {
        val empty = TestFiles.write("empty.mp3", ByteArray(0))
        val result = TestFiles.open(empty).use { TagReader.read(it.fd, "empty.mp3") }
        assertThat(result).isEqualTo(TagReadResult.Failure(TagReadError.UNSUPPORTED_FORMAT))
    }

    @Test
    fun aTruncatedFileDoesNotCrash() {
        val truncated = TestFiles.write("truncated.flac", flac.readBytes().copyOf(200))
        TestFiles.open(truncated).use { TagReader.read(it.fd, "truncated.flac") }
    }

    @Test
    fun theDescriptorStaysOpenAndKeepsItsPosition() {
        TestFiles.open(flac).use { pfd ->
            Os.lseek(pfd.fileDescriptor, 4, android.system.OsConstants.SEEK_SET)
            TagReader.read(pfd.fd, "vorbis-comment.flac")
            assertThat(Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_CUR)).isEqualTo(4)
            val head = ByteArray(4)
            Os.pread(pfd.fileDescriptor, head, 0, 4, 0)
            assertThat(String(head, Charsets.US_ASCII)).isEqualTo("fLaC")
        }
    }

    @Test
    fun manyReadsLeakNoDescriptors() {
        val garbage = TestFiles.write("garbage-leak.bin", Random(2).nextBytes(4096))
        val before = openDescriptors()
        repeat(250) {
            TestFiles.open(flac).use { TagReader.read(it.fd, "vorbis-comment.flac", readPictures = true) }
            TestFiles.open(garbage).use { TagReader.read(it.fd) }
        }
        assertThat(openDescriptors()).isEqualTo(before)
    }

    private fun openDescriptors(): Int = File("/proc/self/fd").list()!!.size

    /** The process's peak resident memory (VmHWM), which a zero-filled allocation raises for good. */
    private fun peakResidentKb(): Long =
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong()
}
