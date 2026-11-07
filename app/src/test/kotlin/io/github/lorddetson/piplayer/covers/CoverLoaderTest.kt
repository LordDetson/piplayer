package io.github.lorddetson.piplayer.covers

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Loading covers through their sources and the caches (docs/library.md §13.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverLoaderTest {

    @TempDir
    lateinit var dir: File

    private val song = CoverFile(1, "content://media/1", 10, 20, embedded = true)
    private val own = CoverStep.Own("own.jpg")
    private val embedded = CoverStep.Embedded(song)
    private val thumbnail = CoverStep.Thumbnail(song)

    /** Pictures are text here: the step's key and the size it was decoded for. */
    private object Codec : ImageCodec<String> {
        override fun encode(image: String) = image.toByteArray()
        override fun decode(bytes: ByteArray, size: Int) = String(bytes)
        override fun weight(image: String) = image.length.toLong()
    }

    /** Sources that give what [pictures] holds for a step, count their reads, and may throw or wait. */
    private class Sources(val pictures: MutableMap<CoverStep, String?> = mutableMapOf()) : CoverSources<String> {
        val reads = mutableListOf<CoverStep>()
        val failing = mutableSetOf<CoverStep>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun load(step: CoverStep, size: Int): String? {
            reads += step
            gate?.await()
            if (step in failing) throw IOException("cannot open now")
            return pictures[step]?.let { "$it@$size" }
        }
    }

    private var steps = listOf<CoverStep>(own, embedded, thumbnail)
    private var plans = 0
    private val sources = Sources()
    private val disk by lazy { CoverDiskCache(dir) }

    private fun TestScope.loader(disk: CoverDiskCache? = this@CoverLoaderTest.disk, memory: Long = 1_000) = CoverLoader(
        plan = { plans++; steps },
        sources = sources,
        codec = Codec,
        disk = disk,
        memoryWeight = memory,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private val request = CoverRequest.Track(1)

    @Test
    fun theFirstSourceWithAPictureWins() = runTest {
        sources.pictures[embedded] = "E"
        sources.pictures[thumbnail] = "T"

        assertThat(loader().load(request, 150)).isEqualTo("E@192")
        assertThat(sources.reads).containsExactly(own, embedded)
    }

    @Test
    fun aSourceWithoutAPictureIsRememberedOnDiskAndInMemory() = runTest {
        sources.pictures[thumbnail] = "T"
        val loader = loader()
        loader.load(request, 150)
        sources.reads.clear()

        assertThat(loader.load(request, 150)).isEqualTo("T@192")
        assertThat(sources.reads).isEmpty()

        // Another process start: memory is empty, the disk still knows.
        assertThat(loader().load(request, 150)).isEqualTo("T@192")
        assertThat(sources.reads).isEmpty()
    }

    @Test
    fun aDiskHitReadsNoSource() = runTest {
        sources.pictures[embedded] = "E"
        loader().load(request, 100)
        sources.reads.clear()

        val other = loader()

        assertThat(other.peek(request, 100)).isNull()
        assertThat(other.load(request, 100)).isEqualTo("E@128")
        assertThat(sources.reads).isEmpty()
        assertThat(other.peek(request, 100)).isEqualTo("E@128")
    }

    @Test
    fun aMemoryHitNeedsNoPlan() = runTest {
        sources.pictures[own] = "O"
        val loader = loader(disk = null)
        loader.load(request, 96)
        val plansBefore = plans

        assertThat(loader.peek(request, 90)).isEqualTo("O@96")
        assertThat(loader.load(request, 96)).isEqualTo("O@96")
        assertThat(plans).isEqualTo(plansBefore)
    }

    @Test
    fun noPictureAnywhereIsNullAndRemembered() = runTest {
        val loader = loader()

        assertThat(loader.load(request, 96)).isNull()
        sources.reads.clear()
        assertThat(loader.load(request, 96)).isNull()
        assertThat(sources.reads).isEmpty()
    }

    @Test
    fun aRequestWithoutSourcesIsNotRemembered() = runTest {
        steps = emptyList()
        val loader = loader()
        assertThat(loader.load(request, 96)).isNull()

        // The library has learnt of the file since.
        steps = listOf(embedded)
        sources.pictures[embedded] = "E"
        assertThat(loader.load(request, 96)).isEqualTo("E@96")
    }

    @Test
    fun aSourceThatFailsIsSkippedButNotRemembered() = runTest {
        sources.pictures[embedded] = "E"
        sources.pictures[thumbnail] = "T"
        sources.failing += embedded
        val loader = loader(memory = 0)

        assertThat(loader.load(request, 96)).isEqualTo("T@96")
        sources.failing.clear()
        sources.reads.clear()
        assertThat(loader.load(request, 96)).isEqualTo("E@96")
        // The own cover's absence was remembered, the failure was not.
        assertThat(sources.reads).containsExactly(embedded)
    }

    @Test
    fun aChangeOfOwnCoversLoadsAgain() = runTest {
        sources.pictures[embedded] = "E"
        val loader = loader()
        assertThat(loader.load(request, 96)).isEqualTo("E@96")

        sources.pictures[own] = "O"
        loader.invalidate()

        assertThat(loader.peek(request, 96)).isNull()
        assertThat(loader.load(request, 96)).isEqualTo("O@96")
    }

    @Test
    fun aLoadThatEndsAfterAChangeOfOwnCoversIsNotKeptInMemory() = runTest {
        sources.pictures[embedded] = "E"
        sources.gate = CompletableDeferred()
        val loader = loader(disk = null)
        val load = async { loader.load(request, 96) }
        advanceUntilIdle()

        loader.invalidate()
        sources.gate!!.complete(Unit)
        load.await()

        assertThat(loader.peek(request, 96)).isNull()
    }

    @Test
    fun aCancelledLoadStoresNothing() = runTest {
        sources.pictures[embedded] = "E"
        sources.gate = CompletableDeferred()
        val loader = loader()
        val load = async { loader.load(request, 96) }
        advanceUntilIdle()

        load.cancel()
        sources.gate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(loader.peek(request, 96)).isNull()
        assertThat(disk.read(own.key + "@96")).isNull()
        assertThat(disk.read(embedded.key + "@96")).isNull()
    }

    @Test
    fun nowPlayingsSizeIsNotCached() = runTest {
        sources.pictures[embedded] = "E"
        val loader = loader()
        assertThat(loader.load(request, 1_080)).isEqualTo("E@1536")
        sources.reads.clear()

        assertThat(loader.load(request, 1_080)).isEqualTo("E@1536")
        assertThat(sources.reads).containsExactly(own, embedded)
        assertThat(disk.size).isZero()
    }
}
