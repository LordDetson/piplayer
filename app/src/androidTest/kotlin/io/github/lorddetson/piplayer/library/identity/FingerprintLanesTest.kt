package io.github.lorddetson.piplayer.library.identity

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.fingerprint.PcmEncoding
import io.github.lorddetson.piplayer.identification.fingerprint.PcmFormat
import io.github.lorddetson.piplayer.identification.fingerprint.PcmSource
import io.github.lorddetson.piplayer.identification.fingerprint.SourceInfo
import io.github.lorddetson.piplayer.library.db.FingerprintEntity
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.nativelib.chromaprint.Chromaprint
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lanes of the fingerprint cache (TI §3.2 "Threads"): a user's request does not wait behind re-attachment work
 * that was queued before it. The sources are fakes that wait on a gate, so the test checks the order in which the
 * computations run and end, never how fast they are; the timeouts only keep a broken lane from hanging the run.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintLanesTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val files = mutableListOf<File>()

    @After
    fun close() {
        library.close()
        files.forEach { it.delete() }
    }

    /** Where a fake source decoded. */
    private data class Run(val name: String, val thread: String, val priority: Int)

    /** The runs in the order they started. */
    private val runs = Collections.synchronizedList(mutableListOf<Run>())

    /** 4 s of a tune at Chromaprint's own rate, so the calculator needs no resampler and makes a few items. */
    private val tune: ByteBuffer = run {
        val rate = Chromaprint.SAMPLE_RATE
        val buffer = ByteBuffer.allocate(rate * 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        var frequency = 300.0
        for (i in 0 until rate * 4) {
            if (i % (rate / 4) == 0) frequency = 300.0 + (i / (rate / 4)) % 7 * 150
            buffer.putShort((sin(2 * PI * frequency * i / rate) * 12_000).toInt().toShort())
        }
        buffer.flip()
        buffer
    }

    /** A source that notes where it runs, waits for [gate] when there is one and then hands out the tune. */
    private inner class GatedSource(
        private val name: String,
        private val gate: CountDownLatch?,
        private val started: CountDownLatch?,
    ) : PcmSource {
        override fun decode(consumer: (ByteBuffer, PcmFormat) -> Boolean): SourceInfo {
            runs += Run(name, Thread.currentThread().name, Process.getThreadPriority(Process.myTid()))
            started?.countDown()
            gate?.let { check(it.await(GUARD_SECONDS, TimeUnit.SECONDS)) { "The gate of $name never opened" } }
            val format = PcmFormat(Chromaprint.SAMPLE_RATE, 1, PcmEncoding.PCM_16BIT)
            consumer(tune.duplicate().order(ByteOrder.LITTLE_ENDIAN), format)
            return SourceInfo("audio/test", "test.decoder", null, decoderPath = "test:$name")
        }
    }

    /** A file that holds its own name, so the fake source knows which file the cache opened. */
    private fun file(name: String): Uri = Uri.fromFile(
        File(context.cacheDir, "lanes-$name.bin").apply { writeText(name) }.also { files += it },
    )

    private fun nameIn(fd: FileDescriptor): String =
        ParcelFileDescriptor.dup(fd).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().decodeToString() }

    @Test
    fun aUserRequestCompletesBeforeReattachmentWorkQueuedEarlier() = runBlocking<Unit> {
        val gate = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val fingerprints = LibraryFingerprints(context, library, uriOf = { error("not used") }, sources = { descriptor, _ ->
            when (val name = nameIn(descriptor.fileDescriptor)) {
                "user" -> GatedSource(name, gate = null, started = null)
                "r1" -> GatedSource(name, gate, firstStarted)
                else -> GatedSource(name, gate, started = null)
            }
        })

        fun reattach(name: String) = file(name).let { uri ->
            // Undispatched, so the computation is queued on its lane before this returns.
            async(start = CoroutineStart.UNDISPATCHED) {
                fingerprints.compute(uri, "key-$name", "test", LibraryFingerprints.Lane.REATTACHMENT)
            }
        }

        // Re-attachment's first computation decodes and waits at the gate; three more are queued behind it.
        val first = reattach("r1")
        try {
            assertThat(firstStarted.await(GUARD_SECONDS, TimeUnit.SECONDS)).isTrue()
            val reattachment = listOf(first) + listOf("r2", "r3", "r4").map(::reattach)

            val user = withTimeout(TimeUnit.SECONDS.toMillis(GUARD_SECONDS)) {
                fingerprints.compute(file("user"), "key-user", "test", LibraryFingerprints.Lane.USER)
            }

            // The user's request ended while re-attachment still waited at its first computation.
            assertThat(user.fingerprint.raw).isNotEmpty()
            assertThat(reattachment).allSatisfy { assertThat(it.isCompleted).isFalse() }
            assertThat(runs.map { it.name }).containsExactly("r1", "user")
            assertThat(library.library().fingerprint("key-user")?.decoderPath).isEqualTo("test:user")

            gate.countDown()
            withTimeout(TimeUnit.SECONDS.toMillis(GUARD_SECONDS)) { reattachment.awaitAll() }
        } finally {
            // A failed check must not leave the queued computations waiting for the guard.
            gate.countDown()
        }

        // Re-attachment ran one computation after another, in the order queued, on its thread at background priority.
        assertThat(runs.map { it.name }).containsExactly("r1", "user", "r2", "r3", "r4")
        val (userRuns, reattachmentRuns) = runs.partition { it.name == "user" }
        assertThat(reattachmentRuns).allSatisfy {
            assertThat(it.thread).isEqualTo("fingerprints")
            assertThat(it.priority).isEqualTo(Process.THREAD_PRIORITY_BACKGROUND)
        }
        assertThat(userRuns.single().thread).isNotEqualTo("fingerprints")
        assertThat(userRuns.single().priority).isEqualTo(Process.THREAD_PRIORITY_DEFAULT)
        for (name in listOf("r1", "r2", "r3", "r4")) {
            assertThat(library.library().fingerprint("key-$name")?.decoderPath).isEqualTo("test:$name")
        }
    }

    /**
     * A request holds its lane only while it decodes (TI §3.2 "Threads"): of two user requests in a row, the first
     * completes, cached, while the second still decodes, instead of waiting for the lane's thread to come back to it.
     */
    @Test
    fun aUserRequestThatHasDecodedCompletesWhileTheNextOneInItsLaneDecodes() = runBlocking<Unit> {
        val firstGate = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val secondGate = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val fingerprints = LibraryFingerprints(context, library, uriOf = { error("not used") }, sources = { descriptor, _ ->
            when (val name = nameIn(descriptor.fileDescriptor)) {
                "u1" -> GatedSource(name, firstGate, firstStarted)
                else -> GatedSource(name, secondGate, secondStarted)
            }
        })

        fun user(name: String) = file(name).let { uri ->
            async(start = CoroutineStart.UNDISPATCHED) {
                fingerprints.compute(uri, "key-$name", "test", LibraryFingerprints.Lane.USER)
            }
        }

        val first = user("u1")
        try {
            assertThat(firstStarted.await(GUARD_SECONDS, TimeUnit.SECONDS)).isTrue()
            // Queued behind the first on the user's lane; the first decodes once its gate opens.
            val second = user("u2")
            firstGate.countDown()
            assertThat(secondStarted.await(GUARD_SECONDS, TimeUnit.SECONDS)).isTrue()

            val result = withTimeout(TimeUnit.SECONDS.toMillis(GUARD_SECONDS)) { first.await() }

            assertThat(result.fingerprint.raw).isNotEmpty()
            assertThat(second.isCompleted).isFalse()
            assertThat(library.library().fingerprint("key-u1")?.decoderPath).isEqualTo("test:u1")

            secondGate.countDown()
            withTimeout(TimeUnit.SECONDS.toMillis(GUARD_SECONDS)) { second.await() }
        } finally {
            firstGate.countDown()
            secondGate.countDown()
        }
        assertThat(runs.map { it.name }).containsExactly("u1", "u2")
        assertThat(library.library().fingerprint("key-u2")?.decoderPath).isEqualTo("test:u2")
    }

    /**
     * The AcoustID track ID cached for a content key (TI §3.2, §10.1) survives a new computation on either lane, which
     * replaces the rest of the row: a slower re-attachment decode that ends after the user's lookup must not drop it.
     */
    @Test
    fun aRecomputedFingerprintKeepsTheCachedAcoustIdTrack() = runBlocking<Unit> {
        val fingerprints = LibraryFingerprints(
            context, library, uriOf = { error("not used") }, clock = { 2_000 },
            sources = { descriptor, _ -> GatedSource(nameIn(descriptor.fileDescriptor), gate = null, started = null) },
        )

        for (lane in LibraryFingerprints.Lane.entries) {
            val key = "key-again-$lane"
            library.library().saveFingerprint(
                FingerprintEntity(
                    key, byteArrayOf(0, 0, 0, 1), "old", 1_000, acoustIdId = "acoustid-$lane", decoderPath = "test:old",
                ),
            )

            val result = withTimeout(TimeUnit.SECONDS.toMillis(GUARD_SECONDS)) {
                fingerprints.compute(file("again-$lane"), key, "test", lane)
            }

            val row = library.library().fingerprint(key)!!
            assertThat(row.acoustIdId).isEqualTo("acoustid-$lane")
            assertThat(LibraryFingerprints.items(row.fingerprint)).isEqualTo(result.fingerprint.raw).isNotEmpty()
            assertThat(row.format).isEqualTo("test")
            assertThat(row.computedAt).isEqualTo(2_000)
            assertThat(row.decoderPath).isEqualTo("test:again-$lane")
        }
    }

    private companion object {
        /** Only keeps a broken lane from hanging the run; no step is expected to come near it. */
        const val GUARD_SECONDS = 60L
    }
}
