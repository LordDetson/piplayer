package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the review shows (TI §6, review screen A). */
sealed interface ReviewState {

    /** The run goes; [progress] is the step it reported last. */
    data class Running(val progress: PipelineProgress? = null) : ReviewState

    /**
     * A pre-check or a blocked call ended the run before anything was found (TI §3): online lookups off, no network,
     * sign-in needed or a provider stopped. "Online lookups off" shows the disclosure.
     */
    data class Blocked(val outcome: IdentificationOutcome) : ReviewState

    /**
     * The run ended with [outcome]: found in its band, or no key, unreadable, unavailable. [retryAt] is the retry time the
     * run reported (TI §3.2), null for none. [candidates] are the recordings of at least 0.50, best first, and [card] the
     * chosen one's; null without a candidate. [keepOrSkip] says whether Keep as-is and Skip are offered (a library file
     * with a content key), [last] the decision Undo undoes, [applied] whether an Apply stands that was not undone, [busy]
     * whether an action runs, [loading] the candidate whose edition loads, and [note] what the last action said.
     */
    data class Ended(
        val outcome: IdentificationOutcome,
        val retryAt: Instant? = null,
        val candidates: List<RecordingMatch> = emptyList(),
        val card: Card? = null,
        val keepOrSkip: Boolean = false,
        val last: LastAction? = null,
        val applied: Boolean = false,
        val busy: Boolean = false,
        val loading: Int? = null,
        val note: ReviewNote? = null,
    ) : ReviewState {

        /** Apply can be tapped: a card with its edition, a ticked line, a cover fetch that ended, no Apply standing. */
        val canApply: Boolean
            get() = false
    }

    /** The run failed in a way no outcome names. */
    data object Failed : ReviewState

    /** The screen closes: Cancel, or Not now in the disclosure. */
    data object Closed : ReviewState
}

/**
 * The card of the candidate [index] of the run's candidates: [match], its [band], its [edition], the diff [lines], the
 * [cover] and the [protectedFields] the note names.
 */
data class Card(
    val index: Int,
    val match: RecordingMatch,
    val band: Band,
    val edition: SuggestedEdition?,
    val lines: List<DiffLine> = emptyList(),
    val cover: CoverState = CoverState.None,
    val protectedFields: Set<ProtectedField> = emptySet(),
)

/** The proposed cover of a card. */
sealed interface CoverState {

    /** It is being fetched; Apply waits. */
    data object Loading : CoverState

    /** No proposed image: no source, the image missing, or the cover protected. */
    data object None : CoverState

    /** The fetch failed (busy, unavailable, blocked). */
    data object Failed : CoverState

    data class Ready(val line: CoverLine) : CoverState
}

/** What Apply, Keep as-is and Skip did, which Undo undoes (TI §6). */
enum class ActionKind { APPLY, KEEP_AS_IS, SKIP }

data class LastAction(val kind: ActionKind, val decisionId: Long)

/** What an action said, shown on the card until the next one. */
enum class ReviewNote { APPLY_FAILED, KEEP_FAILED, SKIP_FAILED, UNDO_FAILED, UNDONE, EDITION_FAILED }

/**
 * The review of one file (TI §6, review screen A) as a state machine over [backend], in [scope]: the run, its outcome,
 * the card and the actions.
 */
class ReviewModel(private val file: FileRef, private val backend: ReviewBackend, private val scope: CoroutineScope) {

    private val mutable = MutableStateFlow<ReviewState>(ReviewState.Running())

    val state: StateFlow<ReviewState> = mutable.asStateFlow()

    /** Starts the run; called once. */
    fun start() = Unit

    /** Cancel during the run: ends it and closes the screen. */
    fun cancel() = Unit

    /** Try again: a new run. */
    fun retry() = Unit

    /** The disclosure's Enable: stores the answer, then runs again. */
    fun enable() = Unit

    /** The disclosure's Not now: stores the answer and closes the screen. */
    fun notNow() = Unit

    /** Ticks or unticks the line of [field] ([ProtectedField.COVER] for the cover line). */
    fun toggle(field: ProtectedField) = Unit

    /** Makes the candidate [index] the card. */
    fun choose(index: Int) = Unit

    fun apply() = Unit

    fun keepAsIs() = Unit

    fun skip() = Unit

    fun undo() = Unit
}
