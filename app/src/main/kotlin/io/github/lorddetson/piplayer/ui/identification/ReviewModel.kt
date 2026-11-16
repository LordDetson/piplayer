package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.caa.CaaImage
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.contract.Provenance
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.musicbrainz.RefLookup
import io.github.lorddetson.piplayer.identification.release.EditionList
import io.github.lorddetson.piplayer.identification.release.EditionSource
import io.github.lorddetson.piplayer.identification.release.ReleaseDetailsExtractor
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Bands
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.ManualRequest
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
     * sign-in needed or a provider stopped. "Online lookups off" shows the disclosure; [answering] says that its Not now
     * is being stored, so that the other answer does nothing. [retryAt] is the retry time the run reported (TI §3.2),
     * null for none.
     */
    data class Blocked(val outcome: IdentificationOutcome, val retryAt: Instant? = null, val answering: Boolean = false) : ReviewState

    /**
     * The run ended with [outcome]: found in its band, or no key, unreadable, unavailable. [retryAt] is the retry time the
     * run reported (TI §3.2), null for none. [candidates] are the recordings of at least 0.50, best first, and [card] the
     * chosen one's; null without a candidate. [keepOrSkip] says whether Keep as-is and Skip are offered (a library file
     * whose content key an identity holds), [last] the decision Undo undoes, [applied] whether an Apply stands that was not undone, [busy]
     * whether an action runs, [loading] the candidate whose edition loads, and [note] what the last action said. Review
     * screen B (step 20): [panel] is the panel shown in place of the card, [canEdit] says whether an identity holds the
     * key (an edit decision needs one), and [snippet] is the file the snippet button plays, null when the run did not
     * read it.
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
        val panel: Panel? = null,
        val canEdit: Boolean = false,
        val snippet: SnippetSource? = null,
    ) : ReviewState {

        /**
         * Apply can be tapped: a card with its edition, a ticked line, a cover fetch that ended, no Apply standing; with only
         * the user's values ticked, an identity of the key for their edit decision.
         */
        val canApply: Boolean
            get() {
                val card = card ?: return false
                if (busy || applied || loading != null || card.edition == null || card.cover == CoverState.Loading) return false
                val parts = ReviewDiff.split(card.lines, (card.cover as? CoverState.Ready)?.line)
                // An edit alone is stored by the identity of the key, which only Apply of a release value makes.
                if (parts.identification.isEmpty()) return parts.hasEdit && canEdit
                return true
            }

        /** A panel can open: no action runs and no edition loads. */
        val canOpenPanel: Boolean get() = !busy && loading == null
    }

    /** The run failed in a way no outcome names. */
    data object Failed : ReviewState

    /** The screen closes: Cancel, or Not now in the disclosure. */
    data object Closed : ReviewState
}

/**
 * The card of the candidate [index] of the run's candidates, or of manual search's results when [manual]: [match], its
 * [band], its [edition], the diff [lines], the [cover] and the [protectedFields] the note names. [found] is the candidate
 * as the run or the search found it; [match] differs from it only by the recording ID of an edition that holds the song
 * as another recording (TI §4.2 step 6).
 */
data class Card(
    val index: Int,
    val match: RecordingMatch,
    val band: Band,
    val edition: SuggestedEdition?,
    val lines: List<DiffLine> = emptyList(),
    val cover: CoverState = CoverState.None,
    val protectedFields: Set<ProtectedField> = emptySet(),
    val manual: Boolean = false,
    val found: RecordingMatch = match,
)

/** The file the snippet button plays (TI §6): stage 0's address of it and its exact duration, null when unknown. */
data class SnippetSource(val uri: String, val durationMs: Long?)

/** A panel of review screen B (TI §6, step 20), shown in place of the card. */
sealed interface Panel {

    /**
     * Choose edition (TI §4.2 step 6): the group's [list], null while it loads or when it [failed]; [opening] is the row
     * whose tracklist loads, [withoutSong] the release IDs that do not contain the song, [openFailed] whether the last
     * tracklist could not be loaded, and [chosen] the release ID of the card's edition.
     */
    data class Editions(
        val list: EditionList? = null,
        val failed: Boolean = false,
        val opening: Int? = null,
        val withoutSong: Set<String> = emptySet(),
        val openFailed: Boolean = false,
        val chosen: String? = null,
    ) : Panel

    /**
     * Search manually (TI §3.3): the [text], whether a request runs ([searching]), the [results] of the last one, what it
     * said ([note]) and the result whose edition loads ([choosing]).
     */
    data class Search(
        val text: String = "",
        val searching: Boolean = false,
        val results: List<ManualResult>? = null,
        val note: SearchNote? = null,
        val choosing: Int? = null,
    ) : Panel

    /**
     * Choose cover (TI §5.2): the release index's [images], null while they load, what the index said ([indexNote]), the
     * file's [embedded] pictures, null while they are read, the release image being fetched ([choosing]) and whether the
     * last one [failed].
     */
    data class Covers(
        val images: List<CaaImage>? = null,
        val indexNote: CoversNote? = null,
        val embedded: List<EmbeddedImage>? = null,
        val choosing: Int? = null,
        val failed: Boolean = false,
    ) : Panel

    /** Edit fields: the [entries] as the user edits them, from [start], and the fields whose text is not a number ([invalid]). */
    data class Editor(
        val entries: List<EditorEntry>,
        val start: List<EditorEntry> = entries,
        val invalid: Set<ProtectedField> = emptySet(),
    ) : Panel
}

/** What manual search said. */
enum class SearchNote { REFUSED, NOTHING_FOUND, NOT_FOUND, FAILED, EDITION_FAILED }

/** What the cover picker's release index said. */
enum class CoversNote { NO_IMAGES, FAILED }

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

/** The decision Undo undoes, and for an Apply with the user's values the edit decision stored after it ([editId]). */
data class LastAction(val kind: ActionKind, val decisionId: Long, val editId: Long? = null)

/** What an action said, shown on the card until the next one. */
enum class ReviewNote { APPLY_FAILED, KEEP_FAILED, SKIP_FAILED, UNDO_FAILED, UNDONE, EDITION_FAILED, EDIT_FAILED }

/**
 * The review of one file (TI §6, review screen A) as a state machine over [backend], in [scope]: the run, its outcome,
 * the card and the actions. Its functions are called on the scope's thread; every request goes through [backend].
 */
class ReviewModel(private val file: FileRef, private val backend: ReviewBackend, private val scope: CoroutineScope) {

    private val mutable = MutableStateFlow<ReviewState>(ReviewState.Running())

    val state: StateFlow<ReviewState> = mutable.asStateFlow()

    private var started = false
    private var run: Job? = null

    /** Counts the runs, so that progress of a run that was replaced never shows. */
    private var runs = 0

    /** The decision Undo undoes and whether an Apply stands, kept from one run of the screen to the next (Try again). */
    private var kept: LastAction? = null
    private var appliedBefore = false
    private var result: PipelineResult? = null

    /** The run's winner's band and the AcoustID track ID of each recording the fingerprint found (TI §3.2). */
    private var found: IdentificationOutcome.Found? = null

    /** The old values and the cover shown now, read once a run found candidates. */
    private var current: Map<ProtectedField, FieldValue> = emptyMap()
    private var shown: ShownCover = ShownCover.None
    private var protected: Set<ProtectedField> = emptySet()

    /** Whether [current], [shown] and [protected] were read for this run's results. */
    private var currentRead = false

    /**
     * The editions known so far by card key ([keyOf]): the edition chosen last, null for a candidate no release
     * contains.
     */
    private val editions = HashMap<String, SuggestedEdition?>()

    /** When each edition reached the review, by card key: the provenance's fetch time (TI §6, §7.1). */
    private val fetchedAt = HashMap<String, Instant>()

    /** Review screen B: the edition the run or release choice suggested by card key, which the picker lists the group of. */
    private val suggested = HashMap<String, SuggestedEdition>()

    /** The group lists by suggested release ID, and the editions without the song by card key. */
    private val groups = HashMap<String, EditionList>()
    private val withoutSong = HashMap<String, Set<String>>()

    /** The manual results the manual card's index refers to, and the search panel as it was left. */
    private var manualResults: List<ManualResult> = emptyList()
    private var lastSearch: Panel.Search? = null

    /** The cover picker's release indexes by release ID, the file's embedded pictures, and the thumbnails by address. */
    private val indexes = HashMap<String, Index>()
    private var embedded: List<EmbeddedImage>? = null
    private val thumbnails = HashMap<String, CoverImage?>()

    /** The user's values of Edit fields, which every card shows over its release's values. */
    private var edits: Map<ProtectedField, FieldValue?> = emptyMap()

    /** Counts the panels opened, so that the answer of a panel's request never reaches a later one. */
    private var panels = 0

    private class Index(val images: List<CaaImage>, val note: CoversNote?)

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
        val ended = now as? ReviewState.Ended
        if (now is ReviewState.Running || now == ReviewState.Closed || ended?.busy == true || ended?.loading != null) return
        launchRun()
    }

    /** The disclosure's Enable: stores the answer, then runs again; nothing once Not now was tapped. */
    fun enable() {
        val blocked = mutable.value as? ReviewState.Blocked
        if (blocked?.outcome != IdentificationOutcome.Disabled || blocked.answering) return
        mutable.value = ReviewState.Running()
        // The run's job from the start, so that Cancel while the answer is stored sends nothing afterwards.
        run = scope.launch {
            quietly { backend.enable() }
            launchRun()
        }
    }

    /** The disclosure's Not now: stores the answer and closes the screen; nothing once an answer was tapped. */
    fun notNow() {
        val blocked = mutable.value as? ReviewState.Blocked
        if (blocked?.outcome != IdentificationOutcome.Disabled || blocked.answering) return
        val answering = blocked.copy(answering = true)
        mutable.value = answering
        scope.launch {
            quietly { backend.notNow() }
            // Unless something else (Cancel) changed the state meanwhile.
            mutable.compareAndSet(answering, ReviewState.Closed)
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
        val shownCard = ended.card
        if (ended.busy || ended.loading != null || ended.panel != null || index !in ended.candidates.indices) return
        if (shownCard != null && !shownCard.manual && shownCard.index == index) return
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
            fetchedAt[match.recordingId] = backend.now()
            edition?.let { suggested.putIfAbsent(match.recordingId, it) }
            update { copy(loading = null) }
            show(index, edition)
        }
    }

    /**
     * Apply (TI §6, §7.1): the ticked fields of the card's edition, with the cover when its line is ticked; with the user's
     * values (review screen B) followed by their edit decision.
     */
    fun apply() {
        val ended = mutable.value as? ReviewState.Ended ?: return
        if (!ended.canApply) return
        val card = ended.card!!
        val details = card.edition!!.details
        val target = result?.file?.target ?: return
        val line = (card.cover as? CoverState.Ready)?.line
        val parts = ReviewDiff.split(card.lines, line)
        val recording = card.match.recordingId
        val provenance = Provenance.of(card.match, details, found?.acoustIdTrackIds?.get(recording), fetchedAt[keyOf(card)] ?: backend.now())
        val front = (line?.new as? ProposedCover.Front)?.image?.takeIf { ProtectedField.COVER in parts.identification }
        if (!parts.hasEdit) {
            if (parts.identification.isEmpty()) return
            val request = ApplyRequest(target, details, parts.identification, front?.source, provenance)
            act(ActionKind.APPLY, ReviewNote.APPLY_FAILED) { backend.apply(request, front) }
            return
        }
        val request = parts.identification.takeIf { it.isNotEmpty() }?.let { ApplyRequest(target, details, it, front?.source, provenance) }
        val edit = ReviewEdit(target, FieldEdits.parts(parts.edits), parts.chosen, details.releaseId)
        update { copy(busy = true, note = null) }
        scope.launch {
            val applied = quietly { backend.applyWithEdit(request, front, edit) }
            update {
                if (applied == null) {
                    copy(busy = false, note = ReviewNote.APPLY_FAILED)
                } else {
                    copy(
                        busy = false,
                        last = LastAction(ActionKind.APPLY, applied.decisionId, applied.editId),
                        applied = true,
                        note = if (applied.editFailed) ReviewNote.EDIT_FAILED else null,
                    )
                }
            }
            if (applied != null) refreshCanEdit()
        }
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
            // The edit stored after an identification first, so the newest decision goes first (TI §7.1).
            var editUndone = last.editId == null
            val undone = quietly {
                if (!editUndone) editUndone = backend.undo(last.editId!!)
                editUndone && backend.undo(last.decisionId)
            } == true
            update {
                if (undone) {
                    copy(busy = false, last = null, applied = applied && last.kind != ActionKind.APPLY, note = ReviewNote.UNDONE)
                } else {
                    // An edit undone already is not undone again by the next Undo.
                    copy(busy = false, note = ReviewNote.UNDO_FAILED, last = if (editUndone) last.copy(editId = null) else last)
                }
            }
        }
    }

    /** Choose edition: opens the picker of the card's candidate, browsing its group the first time (TI §4.2 step 6). */
    fun openEditions() {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val card = ended.card ?: return
        val edition = card.edition ?: return
        val result = result ?: return
        if (!ended.canOpenPanel || ended.panel != null) return
        val key = keyOf(card)
        val base = suggested[key] ?: edition
        val generation = ++panels
        val group = groups[base.release.id]
        update { copy(panel = Panel.Editions(list = group, withoutSong = withoutSong[key].orEmpty(), chosen = edition.release.id)) }
        if (group != null) return
        scope.launch {
            val list = quietly { backend.editions(base, result) }
            if (list != null) groups[base.release.id] = list
            updatePanel<Panel.Editions>(generation) { copy(list = list, failed = list == null) }
        }
    }

    /** Chooses the edition [index] of the picker: maps the file onto it, or marks it as without the song. */
    fun chooseEdition(index: Int) {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val panel = ended.panel as? Panel.Editions ?: return
        val edition = panel.list?.editions?.getOrNull(index) ?: return
        val card = ended.card ?: return
        val shown = card.edition ?: return
        val result = result ?: return
        if (panel.opening != null || edition.release.id in panel.withoutSong) return
        if (edition.release.id == shown.release.id) {
            closePanel()
            return
        }
        val key = keyOf(card)
        val base = suggested[key] ?: shown
        val generation = panels
        update { copy(panel = panel.copy(opening = index, openFailed = false)) }
        scope.launch {
            val opened: SuggestedEdition?
            try {
                opened = backend.openEdition(edition, base, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updatePanel<Panel.Editions>(generation) { copy(opening = null, openFailed = true) }
                return@launch
            }
            if (opened == null) {
                val without = withoutSong[key].orEmpty() + edition.release.id
                withoutSong[key] = without
                updatePanel<Panel.Editions>(generation) { copy(opening = null, withoutSong = without) }
                return@launch
            }
            editions[key] = opened
            fetchedAt[key] = backend.now()
            // Onto the card it was chosen for, while its picker is still open.
            val now = mutable.value as? ReviewState.Ended ?: return@launch
            val nowCard = now.card
            if (now.panel !is Panel.Editions || generation != panels || nowCard == null || keyOf(nowCard) != key) return@launch
            update { copy(panel = null) }
            show(nowCard.index, opened, nowCard.manual)
        }
    }

    /** Search manually: opens its panel with the last search. */
    fun openSearch() {
        val ended = mutable.value as? ReviewState.Ended ?: return
        if (!ended.canOpenPanel || ended.panel != null || result?.file == null) return
        ++panels
        update { copy(panel = lastSearch?.copy(searching = false, choosing = null) ?: Panel.Search()) }
    }

    /** The search panel's text as the user types it. */
    fun searchText(text: String) = updatePanel<Panel.Search>(panels) { copy(text = text).also { lastSearch = it } }

    /** Sends the search panel's text (TI §3.3): a manual query, a lookup, a refusal, or nothing for blank text. */
    fun search() {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val panel = ended.panel as? Panel.Search ?: return
        if (panel.searching || panel.choosing != null) return
        val request = ManualRequest.of(panel.text) ?: return
        val generation = panels
        if (request is ManualRequest.Refused) {
            updatePanel<Panel.Search>(generation) { copy(results = null, note = SearchNote.REFUSED).also { lastSearch = it } }
            return
        }
        val hint = result?.hint ?: TrackHint(null, null, result?.durationMs)
        updatePanel<Panel.Search>(generation) { copy(searching = true, note = null) }
        scope.launch {
            val (results, note) = try {
                when (request) {
                    is ManualRequest.Search -> ManualResults.ofSearch(hint, backend.search(request.query)).let { it to nothingFound(it) }
                    is ManualRequest.Lookup -> when (val found = backend.lookup(request.ref)) {
                        is RefLookup.Recording -> ManualResults.ofRecording(hint, found.recording).let { it to nothingFound(it) }
                        is RefLookup.Release -> ManualResults.ofRelease(hint, found.release).let { it to nothingFound(it) }
                        RefLookup.NotFound -> null to SearchNote.NOT_FOUND
                    }
                    is ManualRequest.Refused -> null to SearchNote.REFUSED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to SearchNote.FAILED
            }
            updatePanel<Panel.Search>(generation) { copy(searching = false, results = results, note = note).also { lastSearch = it } }
        }
    }

    private fun nothingFound(results: List<ManualResult>): SearchNote? = if (results.isEmpty()) SearchNote.NOTHING_FOUND else null

    /** Makes the manual result [index] the card. */
    fun chooseResult(index: Int) {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val panel = ended.panel as? Panel.Search ?: return
        val results = panel.results ?: return
        val chosen = results.getOrNull(index) ?: return
        val result = result ?: return
        if (panel.searching || panel.choosing != null) return
        val key = keyOf(chosen)
        val generation = panels
        updatePanel<Panel.Search>(generation) { copy(choosing = index, note = null) }
        scope.launch {
            readCurrent()
            if (!editions.containsKey(key)) {
                val release = chosen.release
                val edition: SuggestedEdition?
                try {
                    edition = if (release != null) {
                        // A track of a looked-up release takes that release, without release choice.
                        ReleaseDetailsExtractor.extract(release, chosen.match.recordingId, chosen.at?.position, result.durationMs)
                            ?.let { SuggestedEdition(release, it, EditionSource.PICKED) }
                    } else {
                        backend.edition(chosen.match, result)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updatePanel<Panel.Search>(generation) { copy(choosing = null, note = SearchNote.EDITION_FAILED).also { lastSearch = it } }
                    return@launch
                }
                editions[key] = edition
                fetchedAt[key] = backend.now()
                edition?.let { suggested.putIfAbsent(key, it) }
            }
            val now = mutable.value as? ReviewState.Ended ?: return@launch
            if (now.panel !is Panel.Search || generation != panels) return@launch
            lastSearch = (now.panel as Panel.Search).copy(choosing = null)
            manualResults = results
            update { copy(panel = null) }
            show(index, editions[key], manual = true)
        }
    }

    /** Choose cover: opens the picker with the card's release index and the file's embedded pictures (TI §5.2). */
    fun openCovers() {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val releaseId = ended.card?.edition?.details?.releaseId ?: return
        if (!ended.canOpenPanel || ended.panel != null || ProtectedField.COVER in protected) return
        val generation = ++panels
        val index = indexes[releaseId]
        update { copy(panel = Panel.Covers(images = index?.images, indexNote = index?.note, embedded = embedded)) }
        if (index == null) {
            scope.launch {
                val loaded = when (val answer = quietly { backend.releaseImages(releaseId) }) {
                    is CaaResult.Found -> Index(answer.value.images, null)
                    CaaResult.NotFound -> Index(emptyList(), CoversNote.NO_IMAGES)
                    is CaaResult.Busy, null -> Index(emptyList(), CoversNote.FAILED)
                }
                // A failure is asked again the next time the picker opens.
                if (loaded.note != CoversNote.FAILED) indexes[releaseId] = loaded
                updatePanel<Panel.Covers>(generation) { copy(images = loaded.images, indexNote = loaded.note) }
            }
        }
        val uri = result?.file?.uri
        if (embedded == null && uri != null) {
            scope.launch {
                val read = quietly { backend.embeddedImages(uri) }.orEmpty()
                embedded = read
                updatePanel<Panel.Covers>(generation) { copy(embedded = read) }
            }
        }
    }

    /** The 250 px thumbnail of a release image for the picker, fetched once; null without one. */
    suspend fun thumbnail(image: CaaImage): CoverImage? {
        val key = image.original.toString()
        if (thumbnails.containsKey(key)) return thumbnails[key]
        val answer = quietly { backend.image(image, THUMBNAIL_PX) }
        val thumbnail = (answer as? CaaResult.Found)?.value
        // A busy or failed fetch is tried again when the row shows again.
        if (thumbnail != null || answer == CaaResult.NotFound) thumbnails[key] = thumbnail
        return thumbnail
    }

    /** Chooses the release image [index] of the picker: fetched at the embedding size, it becomes the cover line's. */
    fun chooseImage(index: Int) {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val panel = ended.panel as? Panel.Covers ?: return
        val image = panel.images?.getOrNull(index) ?: return
        if (panel.choosing != null) return
        val generation = panels
        update { copy(panel = panel.copy(choosing = index, failed = false)) }
        scope.launch {
            val fetched = (quietly { backend.image(image, EMBED_PX) } as? CaaResult.Found)?.value
            if (fetched == null) {
                updatePanel<Panel.Covers>(generation) { copy(choosing = null, failed = true) }
                return@launch
            }
            chooseCover(generation, ProposedCover.Chosen(fetched.bytes, fetched.size, ProposedCover.Origin.RELEASE_INDEX))
        }
    }

    /** Chooses the embedded picture [index] of the picker as the cover line's. */
    fun chooseEmbedded(index: Int) {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val panel = ended.panel as? Panel.Covers ?: return
        val picture = panel.embedded?.getOrNull(index) ?: return
        if (panel.choosing != null) return
        val generation = panels
        scope.launch { chooseCover(generation, ProposedCover.Chosen(picture.bytes, picture.size, ProposedCover.Origin.EMBEDDED)) }
    }

    /** Puts [chosen] on the cover line of the card, its checkbox by the size rule, and closes the picker. */
    private suspend fun chooseCover(generation: Int, chosen: ProposedCover.Chosen) {
        val line = ReviewDiff.chosenLine(shown, chosen, protected) ?: return
        update {
            val card = card
            if (panel !is Panel.Covers || generation != panels || card?.edition == null) return@update this
            copy(panel = null, card = card.copy(cover = CoverState.Ready(line)))
        }
    }

    /** Edit fields: opens the editor with the values Apply would leave. */
    fun openEditor() {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val card = ended.card ?: return
        if (card.edition == null || !ended.canOpenPanel || ended.panel != null) return
        ++panels
        val entries = FieldEdits.EDITABLE.filter { it !in protected }.map { field ->
            val line = card.lines.firstOrNull { it.field == field }
            FieldEdits.entry(field, if (line == null) current[field] else if (line.ticked) line.new else line.old)
        }
        update { copy(panel = Panel.Editor(entries)) }
    }

    /** An entry of the editor as the user edits it. */
    fun editEntry(entry: EditorEntry) = updatePanel<Panel.Editor>(panels) {
        val invalid = if (FieldEdits.parse(entry) == ParsedEntry.Invalid) invalid + entry.field else invalid - entry.field
        copy(entries = entries.map { if (it.field == entry.field) entry else it }, invalid = invalid)
    }

    /** Save in the editor: every changed field becomes the user's value on the card; nothing while one is not a number. */
    fun saveEdits() {
        val ended = mutable.value as? ReviewState.Ended ?: return
        val panel = ended.panel as? Panel.Editor ?: return
        val card = ended.card ?: return
        val edition = card.edition ?: return
        val changed = panel.entries.filter { entry -> entry != panel.start.firstOrNull { it.field == entry.field } }
        val parsed = changed.associate { it.field to FieldEdits.parse(it) }
        val invalid = parsed.filterValues { it == ParsedEntry.Invalid }.keys
        if (invalid.isNotEmpty()) {
            update { copy(panel = panel.copy(invalid = invalid)) }
            return
        }
        edits = edits + parsed.mapValues { (it.value as ParsedEntry.Value).value }
        val proposed = ReviewDiff.proposed(edition.details)
        val rebuilt = ReviewDiff.withEdits(ReviewDiff.lines(current, proposed, protected), current, proposed, edits)
        // The release's lines keep the ticks the user gave them.
        val lines = rebuilt.map { line ->
            if (line.edited) line else card.lines.firstOrNull { it.field == line.field && !it.edited }?.let { line.copy(ticked = it.ticked) } ?: line
        }
        update { copy(panel = null, card = card.copy(lines = lines)) }
    }

    /** Closes the panel shown; whether there was one. */
    fun closePanel(): Boolean {
        val ended = mutable.value as? ReviewState.Ended ?: return false
        val panel = ended.panel ?: return false
        if (panel is Panel.Search) lastSearch = panel.copy(searching = false, choosing = null)
        update { copy(panel = null) }
        return true
    }

    /** Changes the panel [P] shown, unless a later panel replaced the one of [generation]. */
    private inline fun <reified P : Panel> updatePanel(generation: Int, change: P.() -> Panel) = update {
        val shown = panel as? P
        if (shown == null || generation != panels) this else copy(panel = shown.change())
    }

    private fun launchRun() {
        val before = run
        (mutable.value as? ReviewState.Ended)?.let {
            kept = it.last
            appliedBefore = it.applied
        }
        val generation = ++runs
        mutable.value = ReviewState.Running()
        run = scope.launch {
            val result = try {
                // Progress may come from another thread; it never replaces the state once this run is cancelled or replaced.
                backend.identify(file) { progress ->
                    mutable.update { if (it is ReviewState.Running && generation == runs) ReviewState.Running(progress) else it }
                }
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
        val endedAt = backend.now()
        // Keep as-is and Skip are stored by the identity of the key, which they never make (TI §6).
        val target = result.file?.target
        val keepOrSkip = target is IdentificationTarget.LibraryFile && quietly { backend.canKeepOrSkip(target) } == true
        val canEdit = target != null && quietly { backend.canEdit(target) } == true
        val snippet = result.file?.let { SnippetSource(it.uri, result.durationMs) }
        val found = outcome as? IdentificationOutcome.Found
        this.found = found
        editions.clear()
        fetchedAt.clear()
        covers.clear()
        suggested.clear()
        groups.clear()
        withoutSong.clear()
        manualResults = emptyList()
        lastSearch = null
        indexes.clear()
        embedded = null
        thumbnails.clear()
        edits = emptyMap()
        currentRead = false
        val candidates = found?.ranking?.matches.orEmpty().filter { it.score >= Bands.MEDIUM - SCORE_TOLERANCE }
        if (found == null || candidates.isEmpty()) {
            mutable.value = ReviewState.Ended(
                outcome, result.retryAt, keepOrSkip = keepOrSkip, last = kept, applied = appliedBefore, canEdit = canEdit, snippet = snippet,
            )
            return
        }
        readCurrent()
        val winner = candidates.first()
        val edition = found.choice.suggested?.takeIf { it.details.recordingId == winner.recordingId }
        editions[winner.recordingId] = edition
        fetchedAt[winner.recordingId] = endedAt
        edition?.let { suggested[winner.recordingId] = it }
        mutable.value = ReviewState.Ended(
            outcome, result.retryAt, candidates, cardOf(0, winner, edition), keepOrSkip, last = kept, applied = appliedBefore,
            canEdit = canEdit, snippet = snippet,
        )
        fetchCover()
    }

    /** Reads the values and the cover shown now, and the protected fields, once per run's results. */
    private suspend fun readCurrent() {
        if (currentRead) return
        val details = quietly { backend.current(file) }
        current = ReviewDiff.current(details)
        shown = ReviewDiff.shownCover(details)
        protected = quietly { backend.protectedFields() }.orEmpty()
        currentRead = true
    }

    /** Makes the candidate [index] (of the manual results when [manual]) with [edition] the card. */
    private fun show(index: Int, edition: SuggestedEdition?, manual: Boolean = false) {
        update {
            val match = if (manual) manualResults[index].match else candidates[index]
            copy(card = cardOf(index, match, edition, manual), note = null)
        }
        fetchCover()
    }

    private fun cardOf(index: Int, match: RecordingMatch, edition: SuggestedEdition?, manual: Boolean = false): Card {
        val lines = edition?.let {
            val proposed = ReviewDiff.proposed(it.details)
            ReviewDiff.withEdits(ReviewDiff.lines(current, proposed, protected), current, proposed, edits)
        }.orEmpty()
        val cover = if (edition == null || ProtectedField.COVER in protected) CoverState.None else CoverState.Loading
        val band = when {
            manual -> ManualResults.band(match.score)
            // Only the winner can be high: another candidate is never the best by the band gap (TI §4.1).
            index == 0 -> found?.band ?: Band.MEDIUM
            else -> Band.MEDIUM
        }
        // An edition that holds the song as another recording applies that recording (TI §4.2 step 6).
        val shownMatch = if (edition != null && edition.details.recordingId != match.recordingId) {
            match.copy(candidate = match.candidate.copy(recordingId = edition.details.recordingId))
        } else {
            match
        }
        return Card(index, shownMatch, band, edition, lines, cover, protected, manual, found = match)
    }

    /** The key a card's editions are kept by: its recording, and for a track of a looked-up release that place too. */
    private fun keyOf(card: Card): String =
        if (card.manual) manualResults.getOrNull(card.index)?.let(::keyOf) ?: card.found.recordingId else card.found.recordingId

    private fun keyOf(result: ManualResult): String =
        result.release?.let { "${result.match.recordingId}@${it.id}#${result.at?.disc}/${result.at?.position}" } ?: result.match.recordingId

    /** Whether an edit decision can be stored now (an identity holds the key), after an Apply that may have made one. */
    private suspend fun refreshCanEdit() {
        val target = result?.file?.target ?: return
        val can = quietly { backend.canEdit(target) } == true
        update { copy(canEdit = can) }
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
            if (id != null && kind == ActionKind.APPLY) refreshCanEdit()
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

        /** The cover picker's thumbnails, and the size a chosen release image is fetched at (TI §5.2: the embedding size). */
        const val THUMBNAIL_PX = 250
        const val EMBED_PX = 1200
    }
}
