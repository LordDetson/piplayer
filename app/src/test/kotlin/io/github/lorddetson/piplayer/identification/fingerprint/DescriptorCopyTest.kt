package io.github.lorddetson.piplayer.identification.fingerprint

import io.github.lorddetson.piplayer.identification.fingerprint.DecodingException.CopyRefused.Reason
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The bounded copy of a descriptor that cannot be read at random positions (TI §3.2), on a virtual clock. */
class DescriptorCopyTest {

    @TempDir
    lateinit var root: File

    private val directory by lazy { File(root, "copies") }

    /** Virtual milliseconds; a read that waits moves it by the time it waited. */
    private var now = 1_000L

    private var wallNow = 1_800_000_000_000L

    private fun copies(
        maxBytes: Long = 1_000,
        maxMillis: Long = 10_000,
        reserveBytes: Long = 100,
        free: Long = 1_000_000,
    ) = DescriptorCopy(
        directory, maxBytes, maxMillis, reserveBytes, clock = { now }, wallClock = { wallNow }, freeSpace = { free },
    )

    /** Gives [chunks] in order, one per read, then the end; a null chunk is a read that waits its whole timeout. */
    private class ChunkReader(private val chunks: List<ByteArray?>, private val clock: (Long) -> Unit) : TimedReader {
        var reads = 0
        val timeouts = mutableListOf<Long>()

        override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
            timeouts += timeoutMillis
            if (reads >= chunks.size) return -1
            val chunk = chunks[reads++]
            if (chunk == null) {
                clock(timeoutMillis)
                return 0
            }
            chunk.copyInto(buffer)
            return chunk.size
        }
    }

    private fun reader(vararg chunks: ByteArray?) = ChunkReader(chunks.toList()) { now += it }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { (it * 31 + seed).toByte() }

    private fun leftovers(): List<String> = directory.list()?.toList().orEmpty()

    @Test
    fun everythingTheReaderGivesReachesTheCopyInOrder() {
        val parts = listOf(bytes(300, 1), bytes(1, 2), bytes(299, 3))

        val copy = copies().copy(reader(parts[0], null, parts[1], null, null, parts[2]))

        assertThat(copy.parentFile).isEqualTo(directory)
        assertThat(copy.readBytes()).isEqualTo(parts[0] + parts[1] + parts[2])
    }

    @Test
    fun aCopyOfExactlyTheSizeBoundIsKept() {
        val copy = copies(maxBytes = 600).copy(reader(bytes(300, 1), bytes(300, 2)))

        assertThat(copy.length()).isEqualTo(600)
    }

    @Test
    fun aCopyPastTheSizeBoundIsRefusedAndDeleted() {
        val source = reader(bytes(300, 1), bytes(300, 2), bytes(1, 3), bytes(300, 4))

        assertThatThrownBy { copies(maxBytes = 600).copy(source) }
            .isInstanceOfSatisfying(DecodingException.CopyRefused::class.java) {
                assertThat(it.reason).isEqualTo(Reason.TOO_LARGE)
            }
        assertThat(leftovers()).isEmpty()
        // It stops at the first byte too many instead of reading on.
        assertThat(source.reads).isEqualTo(3)
    }

    @Test
    fun aReaderThatStopsGivingDataWithoutEndingIsRefusedAtTheDeadline() {
        val source = reader(bytes(10, 1), *arrayOfNulls<ByteArray>(1_000))
        val start = now

        assertThatThrownBy { copies(maxMillis = 2_000).copy(source) }
            .isInstanceOfSatisfying(DecodingException.CopyRefused::class.java) {
                assertThat(it.reason).isEqualTo(Reason.TOO_SLOW)
            }
        assertThat(leftovers()).isEmpty()
        // No wait runs past the deadline, and the copy gives up once it is reached.
        assertThat(now - start).isEqualTo(2_000)
        assertThat(source.timeouts).allSatisfy {
            assertThat(it).isPositive().isLessThanOrEqualTo(DescriptorCopy.POLL_MILLIS)
        }
    }

    @Test
    fun aCopyThatWouldLeaveLessThanTheReserveFreeIsRefusedAndDeleted() {
        val source = reader(bytes(300, 1), bytes(300, 2))

        assertThatThrownBy { copies(maxBytes = 10_000, reserveBytes = 500, free = 1_000).copy(source) }
            .isInstanceOfSatisfying(DecodingException.CopyRefused::class.java) {
                assertThat(it.reason).isEqualTo(Reason.NO_SPACE)
            }
        assertThat(leftovers()).isEmpty()
    }

    /** Another copy (the other lane) or another app takes space while this copy runs: the reserve still holds. */
    @Test
    fun aCopyStopsWhenTheFreeSpaceFallsBelowTheReserveWhileItRuns() {
        var free = 1_000_000L
        val copies = DescriptorCopy(
            directory, maxBytes = 10_000, maxMillis = 10_000, reserveBytes = 100, clock = { now },
            wallClock = { wallNow }, freeSpace = { free }, spaceCheckBytes = 500,
        )
        val source = ChunkReader(List(20) { bytes(300, it) }) { now += it }
        val shrinking = TimedReader { buffer, timeout ->
            source.read(buffer, timeout).also { if (source.reads == 2) free = 99 }
        }

        assertThatThrownBy { copies.copy(shrinking) }
            .isInstanceOfSatisfying(DecodingException.CopyRefused::class.java) {
                assertThat(it.reason).isEqualTo(Reason.NO_SPACE)
            }
        assertThat(leftovers()).isEmpty()
        // Checked once at least every 500 bytes: after the second read (600 bytes) at the latest after the fourth.
        assertThat(source.reads).isLessThanOrEqualTo(4)
    }

    @Test
    fun noCopyStartsWithoutSpaceAboveTheReserve() {
        val source = reader(bytes(1, 1))

        assertThatThrownBy { copies(reserveBytes = 500, free = 500).copy(source) }
            .isInstanceOfSatisfying(DecodingException.CopyRefused::class.java) {
                assertThat(it.reason).isEqualTo(Reason.NO_SPACE)
            }
        assertThat(source.reads).isZero()
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun aCancelledCopyStopsAndIsDeleted() {
        var cancel = false
        val source = ChunkReader(List(100) { bytes(5, it) }) { now += it }
        val counting = TimedReader { buffer, timeout ->
            source.read(buffer, timeout).also { if (source.reads == 3) cancel = true }
        }

        assertThatThrownBy { copies().copy(counting) { cancel } }.isInstanceOf(CancellationException::class.java)
        assertThat(source.reads).isEqualTo(3)
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun aReaderThatFailsLeavesNoPartialCopy() {
        var reads = 0
        val failing = TimedReader { buffer, _ ->
            if (reads++ == 2) throw IOException("The provider went away")
            buffer[0] = 1
            1
        }

        assertThatThrownBy { copies().copy(failing) }.isInstanceOf(IOException::class.java)
        assertThat(leftovers()).isEmpty()
    }

    /** A reader whose reads come from [source] and whose end check runs [check]. */
    private fun ending(source: TimedReader, check: () -> Unit) = object : TimedReader {
        override fun read(buffer: ByteArray, timeoutMillis: Long): Int = source.read(buffer, timeoutMillis)

        override fun checkEnd() = check()
    }

    /** The writer of a reliable pipe that fails (TI §3.2) ends it as a writer that is done would: the end check tells. */
    @Test
    fun aWriterErrorReportedAtTheEndLeavesNoPartialCopy() {
        var checks = 0
        val failing = ending(reader(bytes(300, 1), bytes(300, 2))) {
            checks++
            throw IOException("The device was unplugged")
        }

        assertThatThrownBy { copies().copy(failing) }
            .isInstanceOf(IOException::class.java)
            .hasMessage("The device was unplugged")
        assertThat(checks).isEqualTo(1)
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun theEndIsCheckedOnceAfterTheLastRead() {
        val source = reader(bytes(3, 1), null, bytes(3, 2))
        val readsAtCheck = mutableListOf<Int>()

        val copy = copies().copy(ending(source) { readsAtCheck += source.timeouts.size })

        // Three reads gave data or waited and the fourth gave the end; the check came after it, once.
        assertThat(readsAtCheck).containsExactly(4)
        assertThat(copy.readBytes()).isEqualTo(bytes(3, 1) + bytes(3, 2))
    }

    /**
     * Another copy sweeps while this one is written (the other lane, TI §3.2) after the wall clock jumped ahead by more
     * than an hour, so this copy's file looks an hour old: it is still not deleted. (Linux deletes a file that is open
     * for writing, as Android does; Windows refuses, so there this test cannot fail.)
     */
    @Test
    fun aCopyStillBeingWrittenIsNotSweptWhenTheWallClockJumps() {
        val jumped = DescriptorCopy(
            directory, maxBytes = 1_000, maxMillis = 10_000, reserveBytes = 100, clock = { now },
            wallClock = { System.currentTimeMillis() + 2 * DescriptorCopy.STALE_MILLIS }, freeSpace = { 1_000_000 },
        )
        val source = reader(bytes(300, 1), bytes(300, 2))
        var during: List<String>? = null
        val slow = TimedReader { buffer, timeout ->
            if (source.reads == 1 && during == null) {
                jumped.copy(reader(bytes(3, 3)))
                during = leftovers()
            }
            source.read(buffer, timeout)
        }

        val copy = copies().copy(slow)

        assertThat(during).contains(copy.name)
        assertThat(copy).exists()
        assertThat(copy.readBytes()).isEqualTo(bytes(300, 1) + bytes(300, 2))
    }

    @Test
    fun aCopyDeletesOnlyCopiesAnHourOld() {
        directory.mkdirs()
        val old = File(directory, "copy-old").apply { writeBytes(bytes(10, 1)) }
        old.setLastModified(wallNow - DescriptorCopy.STALE_MILLIS - 1_000)
        val recent = File(directory, "copy-recent").apply { writeBytes(bytes(10, 2)) }
        recent.setLastModified(wallNow - DescriptorCopy.STALE_MILLIS + 60_000)

        val copy = copies().copy(reader(bytes(3, 3)))

        assertThat(leftovers()).containsExactlyInAnyOrder(recent.name, copy.name)
    }
}
