package io.github.lorddetson.piplayer.ui.details

import io.github.lorddetson.piplayer.library.details.DecisionTarget
import io.github.lorddetson.piplayer.library.details.ListDecision
import io.github.lorddetson.piplayer.settings.ProtectedField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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
    fun run(action: DetailsAction, block: suspend () -> DetailsOutcome): Boolean = false
}

/** The screen's actions over [actions], run by [runner] (docs/library.md §18.7). */
class DetailsCommands(private val actions: DetailsActions, val runner: DetailsActionRunner) {

    /** "Revert to the file's value" of [field]; its message offers Undo of the revert. */
    fun revert(target: DecisionTarget, field: ProtectedField): Boolean = false

    /** "Use the file's value" of a field changed in the file: a revert. */
    fun useFilesValue(target: DecisionTarget, field: ProtectedField): Boolean = false

    /** "Keep PiPlayer's value" of a field changed in the file. */
    fun keepPiPlayersValue(target: DecisionTarget, field: ProtectedField): Boolean = false

    /** "Undo identification"; there is no redo. */
    fun undoIdentification(target: DecisionTarget): Boolean = false

    /** "Reset" of the Keep as-is or Skip [decision]. */
    fun reset(decision: ListDecision): Boolean = false

    /** The Undo of a message: undoes [decisionId]. */
    fun undo(decisionId: Long): Boolean = false
}
