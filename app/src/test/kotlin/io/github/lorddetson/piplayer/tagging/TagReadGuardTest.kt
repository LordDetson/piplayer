package io.github.lorddetson.piplayer.tagging

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TagReadGuardTest {

    /** Counts that log their changes; a change is committed once it is in [log]. */
    private class FakeCounts : UnsafeCounts {
        val counts: MutableMap<TagTarget, Int> = Collections.synchronizedMap(mutableMapOf())
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun count(target: TagTarget): Int = counts[target] ?: 0

        override suspend fun raise(target: TagTarget) {
            counts[target] = count(target) + 1
            log += "raise"
        }

        override suspend fun reset(target: TagTarget) {
            counts.remove(target)
            log += "reset"
        }
    }

    private val file = TagTarget.LibraryFile(7)
    private val counts = FakeCounts()
    private val kills = AtomicInteger()
    private val guard = TagReadGuard(counts, timeout = 300.milliseconds)

    private fun read(target: TagTarget = file, kill: () -> Unit = { kills.incrementAndGet() }, call: () -> String) =
        runBlocking { guard.run(target, kill, call) }

    private fun crash(): Nothing = throw TaggerDiedException()

    @Test
    fun `the count is raised and committed before the call and cleared by its reply`() {
        val result = read {
            counts.log += "call with count ${counts.counts[file]}"
            "tags"
        }

        assertThat(result).isEqualTo(TaggerResult.Replied("tags"))
        assertThat(counts.log).containsExactly("raise", "call with count 1", "reset")
        assertThat(counts.counts).doesNotContainKey(file)
    }

    @Test
    fun `a call during which the process dies counts as a crash`() {
        assertThat(read { crash() }).isEqualTo(TaggerResult.Crashed)

        assertThat(counts.counts[file]).isEqualTo(1)
        assertThat(kills.get()).isZero()
    }

    @Test
    fun `a call that hangs past the timeout is killed and counted`() {
        val killed = CountDownLatch(1)
        val start = TimeSource.Monotonic.markNow()

        val result = read(kill = { kills.incrementAndGet(); killed.countDown() }) {
            // As a binder call does once its process is killed.
            killed.await(10, TimeUnit.SECONDS)
            crash()
        }

        assertThat(result).isEqualTo(TaggerResult.TimedOut)
        assertThat(start.elapsedNow()).isGreaterThanOrEqualTo(300.milliseconds).isLessThan(5.seconds)
        assertThat(kills.get()).isEqualTo(1)
        assertThat(counts.counts[file]).isEqualTo(1)
    }

    @Test
    fun `the second failure makes the file unsafe and later calls never start`() {
        read { crash() }
        read { crash() }
        val calls = AtomicInteger()

        val result = read { calls.incrementAndGet(); "tags" }

        assertThat(result).isEqualTo(TaggerResult.Unsafe)
        assertThat(calls.get()).isZero()
        assertThat(counts.counts[file]).isEqualTo(TagReadGuard.UNSAFE_COUNT)
        assertThat(counts.log).containsExactly("raise", "raise")
    }

    @Test
    fun `a reply after one failure clears the count`() {
        read { crash() }
        read { "tags" }

        read { crash() }

        assertThat(counts.counts[file]).isEqualTo(1)
    }

    @Test
    fun `an unsafe file leaves other files alone`() {
        read { crash() }
        read { crash() }

        val other = TagTarget.Outside("content://media/external/audio/media/8", size = 10, mtimeMs = 20)
        assertThat(read(other) { "tags" }).isEqualTo(TaggerResult.Replied("tags"))
    }

    @Test
    fun `other exceptions reach the caller with the count raised`() {
        assertThatThrownBy { read { throw IllegalStateException("bad parcel") } }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(counts.counts[file]).isEqualTo(1)
    }

    @Test
    fun `calls run one at a time`() = runBlocking<Unit> {
        val running = AtomicInteger()
        val most = AtomicInteger()

        (1..4L).map { id ->
            async(Dispatchers.Default) {
                guard.run(TagTarget.LibraryFile(id), kill = {}) {
                    most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    Thread.sleep(30)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()

        assertThat(most.get()).isEqualTo(1)
    }

    @Test
    fun `a cancelled call kills the process and leaves the count as it was`() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val killed = CountDownLatch(1)
        val slow = TagReadGuard(counts, timeout = 10.seconds)

        val call = async(Dispatchers.Default) {
            slow.run(file, kill = { kills.incrementAndGet(); killed.countDown() }) {
                started.countDown()
                killed.await(10, TimeUnit.SECONDS)
                crash()
            }
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        call.cancel()
        call.join()

        assertThat(kills.get()).isEqualTo(1)
        assertThat(counts.count(file)).isZero()
    }

    @Test
    fun `the timeout is 30 s by default`() {
        assertThat(TagReadGuard.DEFAULT_TIMEOUT).isEqualTo(30.seconds)
    }
}
