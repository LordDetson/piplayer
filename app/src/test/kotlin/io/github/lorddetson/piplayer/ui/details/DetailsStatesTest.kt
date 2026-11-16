package io.github.lorddetson.piplayer.ui.details

import io.github.lorddetson.piplayer.library.details.DecisionTarget
import io.github.lorddetson.piplayer.library.details.ListDecision
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.userdata.Decisions
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
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
    fun aFailedReadWhenTheScreenIsShownAgainKeepsTheDetailsShownBeforeAndTheNextChangeReadsAgain() = runTest {
        val changes = MutableSharedFlow<Unit>()
        var reads = 0
        val errors = mutableListOf<Exception>()
        // Each watch reads once when it starts, then on each change.
        val firstAndChanges = flow {
            emit(Unit)
            changes.collect { emit(it) }
        }
        val state = DetailsStates.of(backgroundScope, firstAndChanges, once = false, onError = { errors += it }) {
            reads++
            if (reads == 2) throw IOException("user.db is busy")
            DetailsState.Found(details("Read $reads"))
        }

        val shown = watch(state)
        runCurrent()
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Read 1")))
        shown.cancel()
        // Home, and back after the stop timeout: the first read of the new watch fails.
        advanceTimeBy(DetailsStates.STOP_TIMEOUT_MS * 2)
        watch(state)
        runCurrent()
        assertThat(errors).hasSize(1)
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

    // M3 step 22: the screen's decisions (docs/library.md §18.7).

    private val target = DecisionTarget(3, "%064x".format(3), Decisions.LocationValues("external_primary", "Music/", "a.flac", emptyMap()))

    /** Decisions over a song whose title is decided until a revert, and whose title is flagged until kept. */
    private class FakeActions : DetailsActions {
        var title = "Decided"
        var flagged = true
        var revertId: Long? = 41
        var failWith: Exception? = null
        var hold: CompletableDeferred<Unit>? = null
        var keepHold: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()

        override suspend fun revert(target: DecisionTarget, field: ProtectedField): Long? {
            calls += "revert $field"
            hold?.await()
            failWith?.let { throw it }
            if (revertId != null) title = "Tag"
            return revertId
        }

        override suspend fun undoIdentification(target: DecisionTarget): Long? {
            calls += "undo identification"
            return 7
        }

        override suspend fun keepPiPlayersValue(target: DecisionTarget, field: ProtectedField) {
            calls += "keep $field"
            keepHold?.await()
            flagged = false
        }

        override suspend fun undo(decisionId: Long): Boolean {
            calls += "undo $decisionId"
            if (decisionId == 41L) title = "Decided"
            return decisionId != 99L
        }
    }

    /** The details read from [actions], again on each read the runner asks for. */
    private fun TestScope.statesOf(actions: FakeActions, runner: DetailsActionRunner): StateFlow<DetailsState> =
        DetailsStates.of(backgroundScope, runner.reads.map { }, once = false) {
            DetailsState.Found(details(actions.title + if (actions.flagged) " (changed)" else ""))
        }

    private fun TestScope.outcomesOf(runner: DetailsActionRunner): List<DetailsOutcome> {
        val outcomes = mutableListOf<DetailsOutcome>()
        backgroundScope.launch { runner.outcomes.toList(outcomes) }
        return outcomes
    }

    @Test
    fun aRevertReadsTheDetailsAgainOnceItIsCommittedAndItsMessageOffersItsUndo() = runTest {
        val actions = FakeActions()
        val runner = DetailsActionRunner(backgroundScope)
        val commands = DetailsCommands(actions, runner)
        val state = statesOf(actions, runner)
        val outcomes = outcomesOf(runner)
        watch(state)
        runCurrent()
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Decided (changed)")))

        assertThat(commands.revert(target, ProtectedField.TITLE)).isTrue()
        runCurrent()

        assertThat(actions.calls).containsExactly("revert TITLE")
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Tag (changed)")))
        assertThat(outcomes).containsExactly(DetailsOutcome.Done(DetailsAction.REVERT, undo = 41))

        // The message's Undo undoes the revert, and the details follow.
        assertThat(commands.undo(41)).isTrue()
        runCurrent()
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Decided (changed)")))
        assertThat(outcomes.last()).isEqualTo(DetailsOutcome.Done(DetailsAction.UNDO))
    }

    @Test
    fun keepPiPlayersValueRaisesNoVersionButTheDetailsReadAgain() = runTest {
        val actions = FakeActions()
        val runner = DetailsActionRunner(backgroundScope)
        val commands = DetailsCommands(actions, runner)
        val state = statesOf(actions, runner)
        val outcomes = outcomesOf(runner)
        watch(state)
        runCurrent()

        assertThat(commands.keepPiPlayersValue(target, ProtectedField.TITLE)).isTrue()
        runCurrent()

        assertThat(actions.calls).containsExactly("keep TITLE")
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Decided")))
        assertThat(outcomes).containsExactly(DetailsOutcome.Done(DetailsAction.KEEP_VALUE))
    }

    @Test
    fun useTheFilesValueIsARevertAndUndoIdentificationAndResetHaveNoUndo() = runTest {
        val actions = FakeActions()
        val runner = DetailsActionRunner(backgroundScope)
        val commands = DetailsCommands(actions, runner)
        val outcomes = outcomesOf(runner)
        runCurrent()

        commands.useFilesValue(target, ProtectedField.ALBUM)
        runCurrent()
        commands.undoIdentification(target)
        runCurrent()
        commands.reset(ListDecision(12, skipped = true))
        runCurrent()

        assertThat(actions.calls).containsExactly("revert ALBUM", "undo identification", "undo 12")
        assertThat(outcomes).containsExactly(
            DetailsOutcome.Done(DetailsAction.USE_FILE_VALUE, undo = 41),
            DetailsOutcome.Done(DetailsAction.UNDO_IDENTIFICATION),
            DetailsOutcome.Done(DetailsAction.RESET),
        )
    }

    @Test
    fun aTapWhileAnActionRunsDoesNothing() = runTest {
        val actions = FakeActions().apply { hold = CompletableDeferred() }
        val runner = DetailsActionRunner(backgroundScope)
        val commands = DetailsCommands(actions, runner)
        runCurrent()

        assertThat(commands.revert(target, ProtectedField.TITLE)).isTrue()
        runCurrent()
        assertThat(runner.busy.value).isTrue()
        assertThat(commands.revert(target, ProtectedField.TITLE)).isFalse()
        assertThat(commands.keepPiPlayersValue(target, ProtectedField.TITLE)).isFalse()
        actions.hold!!.complete(Unit)
        runCurrent()

        assertThat(actions.calls).containsExactly("revert TITLE")
        assertThat(runner.busy.value).isFalse()
        // Once it ended, the next tap runs.
        assertThat(commands.keepPiPlayersValue(target, ProtectedField.TITLE)).isTrue()
    }

    @Test
    fun aMessagesUndoTappedWhileAnotherActionRunsWaitsForItAndIsNotLost() = runTest {
        val actions = FakeActions()
        val runner = DetailsActionRunner(backgroundScope)
        val commands = DetailsCommands(actions, runner)
        val state = statesOf(actions, runner)
        val outcomes = outcomesOf(runner)
        watch(state)
        runCurrent()
        commands.revert(target, ProtectedField.TITLE)
        runCurrent()
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Tag (changed)")))

        // While "Keep PiPlayer's value" runs, the user taps Undo on the revert's message; Keep's own message then
        // replaces it, and the job that showed it ends.
        actions.keepHold = CompletableDeferred()
        assertThat(commands.keepPiPlayersValue(target, ProtectedField.TITLE)).isTrue()
        runCurrent()
        assertThat(runner.busy.value).isTrue()
        val message = launch { commands.undoWhenFree(41) }
        runCurrent()
        message.cancel()
        actions.keepHold!!.complete(Unit)
        runCurrent()

        assertThat(actions.calls).containsExactly("revert TITLE", "keep TITLE", "undo 41")
        assertThat(outcomes).containsExactly(
            DetailsOutcome.Done(DetailsAction.REVERT, undo = 41),
            DetailsOutcome.Done(DetailsAction.KEEP_VALUE),
            DetailsOutcome.Done(DetailsAction.UNDO),
        )
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Decided")))
        assertThat(runner.busy.value).isFalse()
    }

    @Test
    fun undosWaitingForTheirTurnEachRunOnceOneAfterAnother() = runTest {
        val actions = FakeActions().apply { hold = CompletableDeferred() }
        val runner = DetailsActionRunner(backgroundScope)
        val commands = DetailsCommands(actions, runner)
        runCurrent()

        assertThat(commands.revert(target, ProtectedField.TITLE)).isTrue()
        runCurrent()
        commands.undoWhenFree(5)
        commands.undoWhenFree(6)
        runCurrent()
        // Taps while the revert runs still do nothing.
        assertThat(commands.keepPiPlayersValue(target, ProtectedField.TITLE)).isFalse()
        actions.hold!!.complete(Unit)
        runCurrent()

        // Each waiting Undo ran once, one after another.
        assertThat(actions.calls).containsExactly("revert TITLE", "undo 5", "undo 6")
        assertThat(runner.busy.value).isFalse()
    }

    @Test
    fun aFailedActionChangesNothingSaysSoAndTheDetailsKeepWhatTheyShow() = runTest {
        val actions = FakeActions().apply { revertId = null }
        val errors = mutableListOf<Exception>()
        val runner = DetailsActionRunner(backgroundScope, onError = { errors += it })
        val commands = DetailsCommands(actions, runner)
        val state = statesOf(actions, runner)
        val outcomes = outcomesOf(runner)
        watch(state)
        runCurrent()

        // Nothing written: the identity is gone.
        commands.revert(target, ProtectedField.TITLE)
        runCurrent()
        // A database error.
        actions.failWith = IOException("user.db is busy")
        actions.revertId = 41
        commands.revert(target, ProtectedField.TITLE)
        runCurrent()
        // An undo that undid nothing.
        commands.undo(99)
        runCurrent()

        assertThat(outcomes).containsExactly(
            DetailsOutcome.Failed(DetailsAction.REVERT),
            DetailsOutcome.Failed(DetailsAction.REVERT),
            DetailsOutcome.Failed(DetailsAction.UNDO),
        )
        assertThat(errors).hasSize(1)
        assertThat(runner.reads.value).isEqualTo(0)
        assertThat(state.value).isEqualTo(DetailsState.Found(details("Decided (changed)")))
        assertThat(runner.busy.value).isFalse()
    }
}
