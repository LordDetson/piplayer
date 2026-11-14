package io.github.lorddetson.piplayer.ui.details

import io.github.lorddetson.piplayer.library.details.TrackDetails
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How the details screen reads (docs/library.md §18.1, §18.5). */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsStatesTest {

    private fun details(title: String) = TrackDetails(fileId = 1, title = title, artist = null, album = null, rows = emptyList())

    /** Watches [state] as the screen does while it is shown, until the returned job is cancelled. */
    private fun TestScope.watch(state: StateFlow<DetailsState>) = backgroundScope.launch { state.collect {} }

    @Test
    fun anOpenedFileIsReadOncePerOpeningEvenWhenTheAppComesBackFromTheBackground() = runTest {
        var reads = 0
        val state = DetailsStates.of(backgroundScope, flow { emit(Unit) }, once = true) {
            reads++
            DetailsState.Found(details("Opened"))
        }

        val shown = watch(state)
        runCurrent()
        shown.cancel()
        // Home, and back after the stop timeout.
        advanceTimeBy(DetailsStates.STOP_TIMEOUT_MS * 2)
        watch(state)
        runCurrent()

        assertThat(reads).isEqualTo(1)
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Opened")))
    }

    @Test
    fun aSongOfTheLibraryIsReadAgainWhenTheScreenIsShownAgain() = runTest {
        var reads = 0
        val state = DetailsStates.of(backgroundScope, flow { emit(Unit) }, once = false) {
            reads++
            DetailsState.Found(details("Song $reads"))
        }

        val shown = watch(state)
        runCurrent()
        shown.cancel()
        advanceTimeBy(DetailsStates.STOP_TIMEOUT_MS * 2)
        watch(state)
        runCurrent()

        assertThat(reads).isEqualTo(2)
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Song 2")))
    }

    @Test
    fun aFailedReadKeepsTheDetailsShownAndTheNextChangeReadsAgain() = runTest {
        val changes = MutableSharedFlow<Unit>()
        var reads = 0
        val state = DetailsStates.of(backgroundScope, changes, once = false) {
            reads++
            if (reads == 2) throw IOException("user.db is busy")
            DetailsState.Found(details("Read $reads"))
        }
        watch(state)
        runCurrent()

        changes.emit(Unit)
        runCurrent()
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Read 1")))
        changes.emit(Unit)
        runCurrent()
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Read 1")))
        changes.emit(Unit)
        runCurrent()

        assertThat(state.value).isEqualTo(DetailsState.Found(details("Read 3")))
    }

    @Test
    fun aFirstReadThatFailsSaysSoRatherThanThatTheSongIsGoneAndTheNextChangeReadsAgain() = runTest {
        val changes = MutableSharedFlow<Unit>()
        var reads = 0
        val errors = mutableListOf<Exception>()
        val state = DetailsStates.of(backgroundScope, changes, once = false, onError = { errors += it }) {
            reads++
            if (reads == 1) throw IOException("user.db is busy")
            DetailsState.Found(details("Read $reads"))
        }
        watch(state)
        runCurrent()

        changes.emit(Unit)
        runCurrent()
        assertThat(state.value).isEqualTo(DetailsState.Failed)
        assertThat(errors).hasSize(1)
        changes.emit(Unit)
        runCurrent()

        assertThat(state.value).isEqualTo(DetailsState.Found(details("Read 2")))
    }

    @Test
    fun aSongThatLeftTheLibraryIsGone() = runTest {
        val changes = MutableSharedFlow<Unit>()
        var reads = 0
        val state = DetailsStates.of(backgroundScope, changes, once = false) {
            reads++
            if (reads == 1) DetailsState.Found(details("Here")) else null
        }
        watch(state)
        runCurrent()

        changes.emit(Unit)
        runCurrent()
        changes.emit(Unit)
        runCurrent()

        assertThat(state.value).isEqualTo(DetailsState.Gone)
    }
}
