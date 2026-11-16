package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.contract.Provenance
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Bands
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the review shows (TI §6, review screen A). */
sealed interface ReviewState {

    /** The run goes; [progress] is the step it reported last. */
    data class Running(val progress: PipelineProgress? = null) : ReviewState

    /**
     * A pre-check or a blocked call ended the run before anything was found (TI §3): online lookups off, no network,
     * sign-in needed or a provider stopped. "Online lookups off" shows the disclosure. [retryAt] is the retry time the
     * run reported (TI §3.2), null for none.
     */
    data class Blocked(val outcome: IdentificationOutcome, val retryAt: Instant? = null) : ReviewState

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
            get() {
                val card = card ?: return false
                if (busy || applied || loading != null || card.edition == null || card.cover == CoverState.Loading) return false
                return ReviewDiff.fields(card.lines, (card.cover as? CoverState.Ready)?.line).isNotEmpty()
            }
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
 * the card and the actions. Its functions are called on the scope's thread; every request goes through [backend].
 */
class ReviewModel(private val file: FileRef, private val backend: ReviewBackend, private val scope: CoroutineScope) {

    private val mutable = MutableStateFlow<ReviewState>(ReviewState.Running())

    val state: StateFlow<ReviewState> = mutable.asStateFlow()

    private var started = false
    private var run: Job? = null
    private var result: PipelineResult? = null

    /** The run's winner's band and its AcoustID track ID (TI §3.2). */
    private var found: IdentificationOutcome.Found? = null

    /** The old values and the cover shown now, read once a run found candidates. */
    private var current: Map<ProtectedField, FieldValue> = emptyMap()
    private var shown: ShownCover = ShownCover.None
    private var protected: Set<ProtectedField> = emptySet()

    /** The editions known so far by recording ID; null for a candidate no release contains. */
    private val editions = HashMap<String, SuggestedEdition?>()

    /** What each release's cover fetch gave, so a card fetches its cover once. */
    private val covers = HashMap<String, Fetched>()

    private sealed interface Fetched {
        data class Image(val image: CoverImage) : Fetched

        data object Missing : Fetched

        data object Failed : Fetched
    }

    /** Starts the run; called once. */
    fun start() {
        if (started) return
        started = true
        launchRun()
    }

    /** Cancel during the run: ends it and closes the screen. */
    fun cancel() {
        run?.cancel()
        mutable.value = ReviewState.Closed
    }

    /** Try again: a new run, once no decision is being written, so that its Undo is not lost. */
    fun retry() {
        val now = mutable.value
        if (now is ReviewState.Running || now == ReviewState.Closed || (now as? ReviewState.Ended)?.busy == true) return
        launchRun()
    }

    /** The disclosure's Enable: stores the answer, then runs again. */
    fun enable() {
        if ((mutable.value as? ReviewState.Blocked)?.outcome != IdentificationOutcome.Disabled) return
        mutable.value = ReviewState.Running()
        // The run's job from the start, so that Cancel while the answer is stored sends nothing afterwards.
        run = scope.launch {
            quietly { backend.enable() }
            launchRun()
        }
    }

    /** The disclosure's Not now: stores the answer and closes the screen. */
    fun notNow() {
        if ((mutable.value as? ReviewState.Blocked)?.outcome != IdentificationOutcome.Disabled) return
        scope.launch {
            quietly { backend.notNow() }
            mutable.value = ReviewState.Closed
        }
    }

    /** Ticks or unticks the line of [field] ([ProtectedField.COVER] for the cover line). */
    fun toggle(field: ProtectedField) = update {
        val card = card ?: return@update this
        if (field == ProtectedField.COVER) {
            val cover = card.cover as? CoverState.Ready ?: return@update this
            copy(card = card.copy(cover = CoverState.Ready(cover.line.copy(ticked = !cover.line.ticked))))
        } else {
            copy(card = card.copy(lines = card.lines.map { if (it.field == field) it.copy(ticked = !it.ticked) else it }))
        }
    }

    /** Makes the candidate [index] the card, loading its edition first when it is not known yet. */
    fun choose(index: Int) {
        val ended = mutable.value as? ReviewState.Ended ?: return
        if (ended.busy || ended.loading != null || ended.card?.index == index || index !in ended.candidates.indices) return
        val match = ended.candidates[index]
        if (editions.containsKey(match.recordingId)) {
            show(index, editions[match.recordingId])
            return
        }
        val result = result ?: return
        update { copy(loading = index, note = null) }
        scope.launch {
            val edition = try {
                backend.edition(match, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { copy(loading = null, note = ReviewNote.EDITION_FAILED) }
                return@launch
            }
            editions[match.recordingId] = edition
            update { copy(loading = null) }
            show(index, edition)
        }
    }

    /** Apply (TI §6, §7.1): the ticked fields of the card's edition, with the cover when its line is ticked. */
    fun apply() {
        val ended = mutable.value as? ReviewState.Ended ?: return
        if (!ended.canApply) return
        val card = ended.card!!
        val details = card.edition!!.details
        val target = result?.file?.target ?: return
        val cover = (card.cover as? CoverState.Ready)?.line?.takeIf { it.ticked }
        val fields = ReviewDiff.fields(card.lines, cover)
        if (fields.isEmpty()) return
        val provenance = Provenance.of(card.match, details, found?.acoustIdTrackId, backend.now())
        val request = ApplyRequest(target, details, fields, cover?.new?.source, provenance)
        act(ActionKind.APPLY, ReviewNote.APPLY_FAILED) { backend.apply(request, cover?.new) }
    }

    /** Keep as-is (TI §1) for a library file. */
    fun keepAsIs() {
        val target = libraryTarget() ?: return
        act(ActionKind.KEEP_AS_IS, ReviewNote.KEEP_FAILED) { backend.keepAsIs(target) }
    }

    /** Skip (TI §1) for a library file. */
    fun skip() {
        val target = libraryTarget() ?: return
        act(ActionKind.SKIP, ReviewNote.SKIP_FAILED) { backend.skip(target) }
    }

    /** Undoes the last of Apply, Keep as-is and Skip (TI §6, §7.1). */
    fun undo() {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val last = ended.last ?: return
        if (ended.busy) return
        update { copy(busy = true, note = null) }
        scope.launch {
            val undone = quietly { backend.undo(last.decisionId) } == true
            update {
                if (undone) {
                    copy(busy = false, last = null, applied = applied && last.kind != ActionKind.APPLY, note = ReviewNote.UNDONE)
                } else {
                    copy(busy = false, note = ReviewNote.UNDO_FAILED)
                }
            }
        }
    }

    private fun launchRun() {
        val before = run
        mutable.value = ReviewState.Running()
        run = scope.launch {
            val result = try {
                // Progress may come from another thread; it never replaces the state once the run is cancelled.
                backend.identify(file) { progress -> mutable.update { if (it is ReviewState.Running) ReviewState.Running(progress) else it } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = ReviewState.Failed
                return@launch
            }
            ended(result)
        }
        // After the new run's launch: the job before may be Enable's own, which calls this as its last step.
        before?.cancel()
    }

    private suspend fun ended(result: PipelineResult) {
        this.result = result
        val outcome = result.outcome
        when (outcome) {
            IdentificationOutcome.Disabled,
            IdentificationOutcome.NoNetwork,
            IdentificationOutcome.CaptivePortal,
            is IdentificationOutcome.ProviderStopped,
            -> {
                mutable.value = ReviewState.Blocked(outcome, result.retryAt)
                return
            }
            else -> Unit
        }
        val keepOrSkip = result.file?.target is IdentificationTarget.LibraryFile
        val found = outcome as? IdentificationOutcome.Found
        this.found = found
        val candidates = found?.ranking?.matches.orEmpty().filter { it.score >= Bands.MEDIUM - SCORE_TOLERANCE }
        if (found == null || candidates.isEmpty()) {
            mutable.value = ReviewState.Ended(outcome, result.retryAt, keepOrSkip = keepOrSkip)
            return
        }
        val details = quietly { backend.current(file) }
        current = ReviewDiff.current(details)
        shown = ReviewDiff.shownCover(details)
        protected = quietly { backend.protectedFields() }.orEmpty()
        editions.clear()
        covers.clear()
        val winner = candidates.first()
        val edition = found.choice.suggested?.takeIf { it.details.recordingId == winner.recordingId }
        editions[winner.recordingId] = edition
        mutable.value = ReviewState.Ended(outcome, result.retryAt, candidates, cardOf(0, winner, edition), keepOrSkip)
        fetchCover()
    }

    /** Makes the candidate [index] with [edition] the card. */
    private fun show(index: Int, edition: SuggestedEdition?) {
        update { copy(card = cardOf(index, candidates[index], edition), note = null) }
        fetchCover()
    }

    private fun cardOf(index: Int, match: RecordingMatch, edition: SuggestedEdition?): Card {
        val lines = edition?.let { ReviewDiff.lines(current, ReviewDiff.proposed(it.details), protected) }.orEmpty()
        val cover = if (edition == null || ProtectedField.COVER in protected) CoverState.None else CoverState.Loading
        // Only the winner can be high: another candidate is never the best by the band gap (TI §4.1).
        val band = if (index == 0) found?.band ?: Band.MEDIUM else Band.MEDIUM
        return Card(index, match, band, edition, lines, cover, protected)
    }

    /** Fetches the card's proposed cover once per release and puts its line on the card that still shows that release. */
    private fun fetchCover() {
        val wanted = (mutable.value as? ReviewState.Ended)?.card ?: return
        if (wanted.cover != CoverState.Loading) return
        val details = wanted.edition?.details ?: return
        val releaseId = details.releaseId
        scope.launch {
            val fetched = covers[releaseId] ?: run {
                val answer = quietly { backend.cover(details) }
                val fetched = when (answer) {
                    is CaaResult.Found -> Fetched.Image(answer.value)
                    CaaResult.NotFound -> Fetched.Missing
                    is CaaResult.Busy, null -> Fetched.Failed
                }
                covers[releaseId] = fetched
                fetched
            }
            val state = when (fetched) {
                is Fetched.Image -> ReviewDiff.coverLine(shown, fetched.image, protected)?.let(CoverState::Ready) ?: CoverState.None
                Fetched.Missing -> CoverState.None
                Fetched.Failed -> CoverState.Failed
            }
            update {
                val shownCard = card
                if (shownCard?.edition?.details?.releaseId == releaseId && shownCard.cover == CoverState.Loading) {
                    copy(card = shownCard.copy(cover = state))
                } else {
                    this
                }
            }
        }
    }

    /** Runs a decision [write]; its ID offers Undo, none says [failed]. */
    private fun act(kind: ActionKind, failed: ReviewNote, write: suspend () -> Long?) {
        val ended = mutable.value as? ReviewState.Ended ?: return
        if (ended.busy) return
        update { copy(busy = true, note = null) }
        scope.launch {
            val id = quietly { write() }
            update {
                if (id == null) {
                    copy(busy = false, note = failed)
                } else {
                    copy(busy = false, last = LastAction(kind, id), applied = applied || kind == ActionKind.APPLY)
                }
            }
        }
    }

    private fun libraryTarget(): IdentificationTarget.LibraryFile? {
        val ended = mutable.value as? ReviewState.Ended ?: return null
        if (!ended.keepOrSkip) return null
        return result?.file?.target as? IdentificationTarget.LibraryFile
    }

    private inline fun update(change: ReviewState.Ended.() -> ReviewState) {
        val ended = mutable.value as? ReviewState.Ended ?: return
        mutable.value = ended.change()
    }

    /** [block]'s value, or null when it failed; a cancellation still cancels. */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        /** As the bands compare scores (TI §4.1). */
        const val SCORE_TOLERANCE = 1e-9
    }
}
