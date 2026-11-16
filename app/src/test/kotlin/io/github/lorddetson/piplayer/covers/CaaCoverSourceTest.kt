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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
    private var answer: suspend () -> CaaResult<CoverImage> = { CaaResult.NotFound }

    private val fronts = CaaFronts { sources, size ->
        asked += sources to size
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

    private companion object {
        const val RELEASE = "11111111-1111-4111-8111-111111111111"
        const val GROUP = "22222222-2222-4222-8222-222222222222"
    }
}
