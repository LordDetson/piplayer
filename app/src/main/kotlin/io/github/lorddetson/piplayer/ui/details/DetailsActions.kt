package io.github.lorddetson.piplayer.ui.details

import android.content.Context
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.details.DecisionTarget
import io.github.lorddetson.piplayer.library.details.ListDecision
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The decisions track details make (docs/library.md §18.7), each for the song's [DecisionTarget]. */
interface DetailsActions {

    /** A per-field revert of [field]: the revert's decision ID, null when nothing was written (the identity is gone). */
    suspend fun revert(target: DecisionTarget, field: ProtectedField): Long?

    /** "Undo identification": the decision it undid, null when nothing was undone. */
    suspend fun undoIdentification(target: DecisionTarget): Long?

    /** "Keep PiPlayer's value" for [field] at the target's place (TI §7.1): no value changes, nothing is mirrored. */
    suspend fun keepPiPlayersValue(target: DecisionTarget, field: ProtectedField)

    /** Undoes [decisionId]; false when nothing was undone. */
    suspend fun undo(decisionId: Long): Boolean
}

/** What the screen did, for its message (docs/library.md §18.7). */
enum class DetailsAction { REVERT, USE_FILE_VALUE, KEEP_VALUE, UNDO_IDENTIFICATION, RESET, UNDO }

/** How an action of details ended. */
sealed interface DetailsOutcome {
    val action: DetailsAction

    /** Done; [undo] is the decision an Undo of the message undoes, when it can be undone. */
    data class Done(override val action: DetailsAction, val undo: Long? = null) : DetailsOutcome

    /** It could not be done, and nothing changed. */
    data class Failed(override val action: DetailsAction) : DetailsOutcome
}

/**
 * Runs the actions of details one at a time in [scope] (docs/library.md §18.7): a tap while one runs does nothing. After
 * one that is done, [reads] rises, so that the details read again also when the action raised no version; a failed one
 * changes nothing and only says so. Each outcome goes to [outcomes]; an exception goes to [onError] as a failure.
 */
class DetailsActionRunner(private val scope: CoroutineScope, private val onError: (Exception) -> Unit = {}) {

    private val raised = MutableStateFlow(0L)

    /** Rises after each action that is done. */
    val reads: StateFlow<Long> = raised.asStateFlow()

    private val ended = MutableSharedFlow<DetailsOutcome>(extraBufferCapacity = 8)

    /** The outcome of each action, for the screen's message. */
    val outcomes: SharedFlow<DetailsOutcome> = ended.asSharedFlow()

    private val running = MutableStateFlow(false)

    /** Whether an action runs. */
    val busy: StateFlow<Boolean> = running.asStateFlow()

    /** Starts [block] for [action] unless an action runs; false when it was not started. */
    fun run(action: DetailsAction, block: suspend () -> DetailsOutcome): Boolean {
        if (!running.compareAndSet(expect = false, update = true)) return false
        scope.launch { finish(action, block) }
        return true
    }

    /**
     * Starts [block] for [action] once no action runs, in turn after the actions that run or wait (docs/library.md
     * §18.7): the Undo of a message, which a tap while an action runs must not lose. It waits in the runner's [scope],
     * so the end of its caller, a message that a newer one replaced, does not drop it.
     */
    fun queue(action: DetailsAction, block: suspend () -> DetailsOutcome) {
        scope.launch {
            waiting.withLock {
                while (!running.compareAndSet(expect = false, update = true)) running.first { !it }
            }
            finish(action, block)
        }
    }

    /** Waiting actions take their turns one at a time, in the order they came. */
    private val waiting = Mutex()

    /** Runs [block] while [running] is held for it, then frees it and reports the outcome. */
    private suspend fun finish(action: DetailsAction, block: suspend () -> DetailsOutcome) {
        val outcome = try {
            block()
        } catch (e: CancellationException) {
            running.value = false
            throw e
        } catch (e: Exception) {
            onError(e)
            DetailsOutcome.Failed(action)
        }
        if (outcome is DetailsOutcome.Done) raised.update { it + 1 }
        running.value = false
        ended.tryEmit(outcome)
    }
}

/** The screen's actions over [actions], run by [runner] (docs/library.md §18.7). */
class DetailsCommands(private val actions: DetailsActions, val runner: DetailsActionRunner) {

    /** "Revert to the file's value" of [field]; its message offers Undo of the revert. */
    fun revert(target: DecisionTarget, field: ProtectedField): Boolean = reverting(DetailsAction.REVERT, target, field)

    /** "Use the file's value" of a field changed in the file: a revert. */
    fun useFilesValue(target: DecisionTarget, field: ProtectedField): Boolean = reverting(DetailsAction.USE_FILE_VALUE, target, field)

    /** "Keep PiPlayer's value" of a field changed in the file. */
    fun keepPiPlayersValue(target: DecisionTarget, field: ProtectedField): Boolean = runner.run(DetailsAction.KEEP_VALUE) {
        actions.keepPiPlayersValue(target, field)
        DetailsOutcome.Done(DetailsAction.KEEP_VALUE)
    }

    /** "Undo identification"; there is no redo. */
    fun undoIdentification(target: DecisionTarget): Boolean = runner.run(DetailsAction.UNDO_IDENTIFICATION) {
        done(DetailsAction.UNDO_IDENTIFICATION, actions.undoIdentification(target) != null)
    }

    /** "Reset" of the Keep as-is or Skip [decision]. */
    fun reset(decision: ListDecision): Boolean = runner.run(DetailsAction.RESET) {
        done(DetailsAction.RESET, actions.undo(decision.decisionId))
    }

    /** Undoes [decisionId], unless an action runs. */
    fun undo(decisionId: Long): Boolean = runner.run(DetailsAction.UNDO) { done(DetailsAction.UNDO, actions.undo(decisionId)) }

    /** The Undo of a message once tapped: undoes [decisionId] when no action runs, waiting for one that does. */
    fun undoWhenFree(decisionId: Long) = runner.queue(DetailsAction.UNDO) { done(DetailsAction.UNDO, actions.undo(decisionId)) }

    private fun reverting(action: DetailsAction, target: DecisionTarget, field: ProtectedField) = runner.run(action) {
        actions.revert(target, field)?.let { DetailsOutcome.Done(action, undo = it) } ?: DetailsOutcome.Failed(action)
    }

    private fun done(action: DetailsAction, done: Boolean) = if (done) DetailsOutcome.Done(action) else DetailsOutcome.Failed(action)
}

/** The decisions of details in the app (docs/library.md §18.7): through `LibraryDecisions`, so the mirror follows each. */
class LibraryDetailsActions(context: Context) : DetailsActions {

    private val app = context.applicationContext

    override suspend fun revert(target: DecisionTarget, field: ProtectedField): Long? =
        Library.decisions(app).revert(target.identityId, setOf(field), Hex.bytes(target.contentKey))?.decisionId

    override suspend fun undoIdentification(target: DecisionTarget): Long? =
        Library.decisions(app).undoIdentification(target.identityId)?.decisionId

    override suspend fun keepPiPlayersValue(target: DecisionTarget, field: ProtectedField) =
        UserData.decisions(app).keepPiPlayersValue(target.identityId, target.location, DecidedField.partsOf(field).toSet())

    override suspend fun undo(decisionId: Long): Boolean = Library.decisions(app).undo(decisionId) != null
}
