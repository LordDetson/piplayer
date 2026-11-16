package io.github.lorddetson.piplayer.covers

import io.github.lorddetson.piplayer.identification.caa.CaaException
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.identification.caa.ImageFormat
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.InMemoryOnlineGate
import io.github.lorddetson.piplayer.net.OnlineConsent
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The lazy Cover Art Archive step of the cover order (docs/library.md §13.1 step 6, §13.3), over a fake client. */
@OptIn(ExperimentalCoroutinesApi::class)
class CaaCoverSourceTest {

    private val gate = InMemoryOnlineGate(OnlineConsent(enabled = true, disclosureVersion = OnlineConsent.CURRENT_DISCLOSURE_VERSION))

    /** What the fake client was asked, and what it answers. */
    private val asked = mutableListOf<Pair<List<CoverSource>, CoverSize>>()
    private val originals = mutableListOf<Boolean>()
    private var answer: suspend () -> CaaResult<CoverImage> = { CaaResult.NotFound }

    private val fronts = CaaFronts { sources, size, orOriginal ->
        asked += sources to size
        originals += orOriginal
        answer()
    }

    /** Pictures are text here: the bytes and the step decoded for. */
    private val source = CaaCoverSource(fronts, gate, gate.consent.map { it.allows(OnlineConsent.CURRENT_DISCLOSURE_VERSION) }) { bytes, size ->
        "${String(bytes)}@$size"
    }

    private fun image(text: String) = CoverImage(
        bytes = text.toByteArray(),
        format = ImageFormat.JPEG,
        size = null,
        requested = "https://caa.test/release/x/front-250".toHttpUrl(),
        url = "https://images.test/x.jpg".toHttpUrl(),
        original = false,
        source = CoverSource(CoverEntity.RELEASE, RELEASE),
    )

    private val step = CoverStep.Caa(CoverRelease(RELEASE, GROUP))

    @Test
    fun listsLoadFront250AndLargerViewsLargerImages() {
        assertThat(listOf(96, 128, 192, 256).map { CaaCoverSource.sizeFor(it) }).containsOnly(CoverSize.LIST)
        assertThat(listOf(384, 512).map { CaaCoverSource.sizeFor(it) }).containsOnly(CoverSize.DISPLAY)
        assertThat(listOf(768, 1024, 2048).map { CaaCoverSource.sizeFor(it) }).containsOnly(CoverSize.EMBED)
    }

    @Test
    fun aFoundFrontIsDecodedForTheStepAndTheReleaseIsAskedBeforeItsGroup() = runTest {
        answer = { CaaResult.Found(image("front")) }

        assertThat(source.load(step, 96)).isEqualTo("front@96")
        assertThat(asked).containsExactly(
            listOf(CoverSource(CoverEntity.RELEASE, RELEASE), CoverSource(CoverEntity.RELEASE_GROUP, GROUP)) to CoverSize.LIST,
        )
    }

    @Test
    fun aReleaseWithoutAGroupAsksTheReleaseAlone() = runTest {
        source.load(CoverStep.Caa(CoverRelease(RELEASE)), 512)

        assertThat(asked).containsExactly(listOf(CoverSource(CoverEntity.RELEASE, RELEASE)) to CoverSize.DISPLAY)
    }

    @Test
    fun noFrontIsNoPicture() = runTest {
        answer = { CaaResult.NotFound }

        assertThat(source.load(step, 96)).isNull()
    }

    @Test
    fun aBusyAnswerIsNotKnownNow() = runTest {
        answer = { CaaResult.Busy(Instant.EPOCH) }

        assertThat(runCatching { source.load(step, 96) }.exceptionOrNull()).isInstanceOf(CaaBusyException::class.java)
    }

    @Test
    fun aFailureOfTheClientIsNotKnownNow() = runTest {
        answer = { throw CaaException.Unavailable(4, null) }

        assertThat(runCatching { source.load(step, 96) }.exceptionOrNull()).isInstanceOf(CaaException.Unavailable::class.java)
    }

    @Test
    fun aClosedGateAsksNothing() = runTest {
        gate.set(OnlineConsent.NONE)

        assertThat(runCatching { source.load(step, 96) }.exceptionOrNull()).isInstanceOf(GateClosedException::class.java)
        assertThat(asked).isEmpty()
    }

    @Test
    fun theGateClosingDuringALoadCancelsIt() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        answer = {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val load = async { runCatching { source.load(step, 96) } }
        started.await()

        gate.set(OnlineConsent.NONE)
        advanceUntilIdle()

        assertThat(load.isCompleted).isTrue()
        assertThat(load.await().exceptionOrNull()).isInstanceOf(GateClosedException::class.java)
        assertThat(cancelled).isTrue()
    }

    @Test
    fun rowsAndHeadersTakeNoOriginalWhileLargerViewsDo() = runTest {
        // An original can be tens of MB: a row or a header without its thumbnail shows none (docs/library.md §13.3).
        listOf(96, 256, 384, 512, 768, 1536).forEach { source.load(step, it) }

        assertThat(originals).containsExactly(false, false, false, false, true, true)
    }

    @Test
    fun loadsOfOneImageAtTheSameTimeShareOneRequestAndEachDecodesItForItsStep() = runTest {
        val release = CompletableDeferred<Unit>()
        answer = {
            release.await()
            CaaResult.Found(image("front"))
        }

        val rows = listOf(96, 128, 256).map { async { source.load(step, it) } }
        val header = async { source.load(step, 384) }
        val otherRelease = async { source.load(CoverStep.Caa(CoverRelease(GROUP)), 96) }
        advanceUntilIdle()
        release.complete(Unit)

        assertThat(rows.map { it.await() }).containsExactly("front@96", "front@128", "front@256")
        assertThat(header.await()).isEqualTo("front@384")
        assertThat(otherRelease.await()).isEqualTo("front@96")
        // One `front-250` for the rows, one `front-500` for the header, one for the other release.
        assertThat(asked.map { it.second }).containsExactlyInAnyOrder(CoverSize.LIST, CoverSize.DISPLAY, CoverSize.LIST)

        // A load just after the request ended, before the disk cache has the picture, takes the image it found.
        assertThat(source.load(step, 192)).isEqualTo("front@192")
        assertThat(asked).hasSize(3)
    }

    @Test
    fun aCancelledLoadLeavesTheSharedRequestToTheOthers() = runTest {
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        answer = {
            try {
                release.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            CaaResult.Found(image("front"))
        }
        val leaving = async { source.load(step, 96) }
        val staying = async { source.load(step, 96) }
        advanceUntilIdle()

        leaving.cancel()
        advanceUntilIdle()
        release.complete(Unit)

        assertThat(staying.await()).isEqualTo("front@96")
        assertThat(cancelled).isFalse()
        assertThat(asked).hasSize(1)
    }

    @Test
    fun theSharedRequestIsCancelledWhenNoLoadWaitsForItAnyMore() = runTest {
        var cancelled = false
        answer = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val loads = (1..3).map { async { source.load(step, 96) } }
        advanceUntilIdle()

        loads.forEach { it.cancel() }
        advanceUntilIdle()

        assertThat(cancelled).isTrue()
        assertThat(asked).hasSize(1)
    }

    @Test
    fun theGateClosingEndsALoadAtOnceEvenWhileItsRequestCannotStopAtOnce() = runTest {
        // A body read in a blocking call does not see the cancellation until it returns.
        val started = CompletableDeferred<Unit>()
        val bodyRead = CompletableDeferred<Unit>()
        answer = {
            started.complete(Unit)
            withContext(NonCancellable) { bodyRead.await() }
            CaaResult.Found(image("front"))
        }
        val load = async { runCatching { source.load(step, 96) } }
        started.await()

        gate.set(OnlineConsent.NONE)
        advanceUntilIdle()

        try {
            assertThat(load.isCompleted).isTrue()
            assertThat(load.await().exceptionOrNull()).isInstanceOf(GateClosedException::class.java)
        } finally {
            bodyRead.complete(Unit)
        }
    }

    private companion object {
        const val RELEASE = "11111111-1111-4111-8111-111111111111"
        const val GROUP = "22222222-2222-4222-8222-222222222222"
    }
}
