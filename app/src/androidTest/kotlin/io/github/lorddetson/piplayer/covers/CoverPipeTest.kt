package io.github.lorddetson.piplayer.covers

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Writing a cover into a controller's pipe (docs/library.md §13.8.4). */
@RunWith(AndroidJUnit4::class)
class CoverPipeTest {

    private val bytes = ByteArray(1_000_000) { (it * 31).toByte() }
    private val pool = Executors.newSingleThreadExecutor()

    @Test
    fun aReaderGetsEveryByte() {
        val (source, sink) = ParcelFileDescriptor.createReliablePipe()
        val read = pool.submit<ByteArray> { ParcelFileDescriptor.AutoCloseInputStream(source).use { it.readBytes() } }

        val written = sink.use { CoverPipe.write(it.fileDescriptor, bytes, idleTimeoutMs = 2_000) }

        assertThat(written).isTrue()
        assertThat(read.get(10, TimeUnit.SECONDS)).isEqualTo(bytes)
    }

    @Test
    fun aReaderThatNeverReadsDoesNotKeepTheWriter() {
        val (source, sink) = ParcelFileDescriptor.createReliablePipe()
        try {
            val started = System.nanoTime()

            val written = sink.use { CoverPipe.write(it.fileDescriptor, bytes, idleTimeoutMs = 300) }

            assertThat(written).isFalse()
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3_000)
        } finally {
            source.close()
        }
    }

    @Test
    fun aReaderThatTakesAPageNowAndThenDoesNotKeepTheWriterEither() {
        val (source, sink) = ParcelFileDescriptor.createReliablePipe()
        val reader = pool.submit {
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                val page = ByteArray(CoverPipe.CHUNK)
                while (input.read(page) > 0) Thread.sleep(150)
            }
        }
        val started = System.nanoTime()

        val written = sink.use { CoverPipe.write(it.fileDescriptor, bytes, idleTimeoutMs = 1_000, totalTimeoutMs = 600) }

        assertThat(written).isFalse()
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000)
        reader.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun aGoneReaderIsSeenWithoutWaiting() {
        val (source, sink) = ParcelFileDescriptor.createReliablePipe()
        sink.use {
            assertThat(CoverPipe.readerGone(it.fileDescriptor)).isFalse()
            source.close()
            assertThat(CoverPipe.readerGone(it.fileDescriptor)).isTrue()
        }
    }

    @Test
    fun aReaderThatWentAwayEndsTheWriteAtOnce() {
        val (source, sink) = ParcelFileDescriptor.createReliablePipe()
        source.close()
        val started = System.nanoTime()

        val written = sink.use { CoverPipe.write(it.fileDescriptor, bytes, idleTimeoutMs = 5_000) }

        assertThat(written).isFalse()
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000)
    }
}
