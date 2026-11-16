package io.github.lorddetson.piplayer.ui.identification

import android.app.Application
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.PipelineProgress
import io.github.lorddetson.piplayer.identification.pipeline.PipelineStage
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.release.ReleaseDetailsExtractor
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.HintField
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.covers.LibraryCover
import io.github.lorddetson.piplayer.ui.settings.protectedFieldLabel
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Test tags of the review (TI §6, review screen A). */
object ReviewTags {
    const val PROGRESS = "review_progress"
    const val CARD = "review_card"
    const val CONFIDENCE = "review_confidence"
    const val REASON = "review_reason"
    const val COVER_LINE = "review_cover_line"

    fun line(field: ProtectedField) = "review_line_${field.stored}"
}

/** The review of one file, which runs its identification once when the screen first opens (TI §6). */
class ReviewViewModel(application: Application, file: FileRef) : AndroidViewModel(application) {
    val review = ReviewModel(file, AppReviewBackend(application), viewModelScope).also { it.start() }
}

/**
 * The review screen (TI §6, review screen A) of the song [fileId] or of the file opened from another app at [uri]: a
 * route of its own beside track details, with a top bar with back and the title "Identify".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(fileId: Long?, uri: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val file = fileId?.let { FileRef.Library(it) } ?: FileRef.Opened(uri.orEmpty())
    val model = viewModel(key = "review ${fileId ?: uri}") { ReviewViewModel(app, file) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identify), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
                },
            )
        },
    ) { padding ->
        ReviewContent(
            model.review,
            onBack,
            Modifier.padding(padding),
            // The cover the loader resolves now (docs/library.md §13.1); an opened file shows only its line's text.
            oldCover = fileId?.let { id -> { LibraryCover(CoverRequest.Track(id), null, size = COVER_DP.dp, shape = RoundedCornerShape(4.dp)) } },
        )
    }
}

/**
 * The review of [model] (TI §6, review screen A); [onBack] closes it. [oldCover] draws the cover shown now beside the
 * cover line, and [snippet] is the place of the snippet button on the card (step 20).
 */
@Composable
fun ReviewContent(
    model: ReviewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    oldCover: (@Composable () -> Unit)? = null,
    snippet: (@Composable () -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == ReviewState.Closed) onBack() }
    val scrolling = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    when (val shown = state) {
        is ReviewState.Running -> Progress(shown.progress, model::cancel, scrolling)
        is ReviewState.Blocked -> when (val outcome = shown.outcome) {
            IdentificationOutcome.Disabled -> DisclosureScreen(onEnable = model::enable, onNotNow = model::notNow, modifier = modifier.fillMaxSize())
            else -> Column(scrolling) { OutcomeMessage(outcome, model::retry) }
        }
        ReviewState.Failed -> Column(scrolling) { Message(stringResource(R.string.review_failed), model::retry) }
        is ReviewState.Ended -> Ended(shown, model, scrolling, oldCover, snippet)
        ReviewState.Closed -> Unit
    }
}

private const val COVER_DP = 56
private const val CARD_COVER_DP = 120

@Composable
private fun Progress(progress: PipelineProgress?, onCancel: () -> Unit, modifier: Modifier) {
    Column(modifier.testTag(ReviewTags.PROGRESS), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(progressText(progress), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.review_cancel)) }
    }
}

@Composable
private fun progressText(progress: PipelineProgress?): String = when (progress) {
    null -> stringResource(R.string.review_progress_local)
    PipelineProgress.WalkingLength -> stringResource(R.string.review_progress_walk)
    PipelineProgress.Decoding -> stringResource(R.string.review_progress_decoding)
    is PipelineProgress.ReleasePages -> pluralStringResource(R.plurals.review_progress_release_pages, progress.pages, progress.pages)
    is PipelineProgress.Started -> stringResource(
        when (progress.stage) {
            PipelineStage.LOCAL_READ -> R.string.review_progress_local
            PipelineStage.KNOWN_IDS -> R.string.review_progress_known_ids
            PipelineStage.ISRC -> R.string.review_progress_isrc
            PipelineStage.FINGERPRINT -> R.string.review_progress_fingerprint
            PipelineStage.TEXT_SEARCH -> R.string.review_progress_text
            PipelineStage.RELEASE_CHOICE -> R.string.review_progress_release
        },
    )
}

/** The text of an outcome without a card, with Try again where another run may go otherwise. */
@Composable
private fun OutcomeMessage(outcome: IdentificationOutcome, onRetry: () -> Unit) {
    when (outcome) {
        IdentificationOutcome.NoNetwork -> Message(stringResource(R.string.review_no_network), onRetry)
        IdentificationOutcome.CaptivePortal -> Message(stringResource(R.string.review_captive_portal), onRetry)
        is IdentificationOutcome.ProviderStopped -> Message(
            stringResource(
                when (outcome.reason) {
                    StopReason.USER_AGENT_REJECTED -> R.string.review_stopped_user_agent
                    StopReason.INVALID_API_KEY -> R.string.review_stopped_key
                },
                providerName(outcome.provider),
            ),
            onRetry = null,
        )
        IdentificationOutcome.NoKey -> Message(stringResource(R.string.review_no_key), onRetry = null)
        IdentificationOutcome.Unreadable -> Message(stringResource(R.string.review_unreadable), onRetry)
        is IdentificationOutcome.Unavailable -> Message(stringResource(R.string.review_unavailable, providerName(outcome.provider)), onRetry)
        is IdentificationOutcome.Found -> Message(stringResource(R.string.review_no_match), onRetry = null)
        // Shown as the disclosure, never here.
        IdentificationOutcome.Disabled -> Unit
    }
}

@Composable
private fun providerName(provider: ProviderId): String = stringResource(
    when (provider) {
        ProviderId.ACOUSTID -> R.string.identification_name_acoustid
        ProviderId.MUSICBRAINZ -> R.string.identification_name_musicbrainz
        ProviderId.COVER_ART_ARCHIVE -> R.string.identification_name_caa
    },
)

@Composable
private fun Message(text: String, onRetry: (() -> Unit)?) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
    if (onRetry != null) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.review_try_again)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ended(
    state: ReviewState.Ended,
    model: ReviewModel,
    modifier: Modifier,
    oldCover: (@Composable () -> Unit)?,
    snippet: (@Composable () -> Unit)?,
) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val card = state.card
        if (card == null) OutcomeMessage(state.outcome, model::retry)
        state.retryAt?.let { at ->
            Text(
                stringResource(R.string.review_retry_at, DateFormat.getTimeFormat(context).format(Date.from(at))),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (card != null) {
            CandidateCard(card)
            snippet?.invoke()
            if (card.edition == null) Text(stringResource(R.string.review_no_edition), style = MaterialTheme.typography.bodyMedium)
            if (state.candidates.size > 1) Candidates(state, model::choose)
        }
        Status(state, model::undo)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card != null) Button(onClick = model::apply, enabled = state.canApply) { Text(stringResource(R.string.review_apply)) }
            if (state.keepOrSkip) {
                OutlinedButton(onClick = model::keepAsIs, enabled = !state.busy) { Text(stringResource(R.string.review_keep_as_is)) }
                OutlinedButton(onClick = model::skip, enabled = !state.busy) { Text(stringResource(R.string.review_skip)) }
            }
        }
        if (card != null && card.edition != null) Diff(card, model::toggle, oldCover)
    }
}

/** What the last action did, with Undo until the next one, or what it said when it failed. */
@Composable
private fun Status(state: ReviewState.Ended, onUndo: () -> Unit) {
    val last = state.last
    val note = state.note
    val text = when {
        last != null -> stringResource(
            when (last.kind) {
                ActionKind.APPLY -> R.string.review_applied
                ActionKind.KEEP_AS_IS -> R.string.review_kept
                ActionKind.SKIP -> R.string.review_skipped
            },
        )
        else -> null
    }
    val noteText = note?.let {
        stringResource(
            when (it) {
                ReviewNote.APPLY_FAILED -> R.string.review_apply_failed
                ReviewNote.KEEP_FAILED -> R.string.review_keep_failed
                ReviewNote.SKIP_FAILED -> R.string.review_skip_failed
                ReviewNote.UNDO_FAILED -> R.string.review_undo_failed
                ReviewNote.UNDONE -> R.string.review_undone
                ReviewNote.EDITION_FAILED -> R.string.review_edition_failed
            },
        )
    }
    if (text == null && noteText == null) return
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        noteText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (note == ReviewNote.UNDONE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        if (text != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onUndo, enabled = !state.busy) { Text(stringResource(R.string.review_undo)) }
            }
        }
    }
}

@Composable
private fun CandidateCard(card: Card) {
    val details = card.edition?.details
    val release = card.edition?.release
    Column(Modifier.fillMaxWidth().testTag(ReviewTags.CARD), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CoverPicture(card.cover)
            Column(Modifier.weight(1f)) {
                Text(details?.title ?: card.match.candidate.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                val artist = details?.trackArtist ?: card.match.candidate.artistCredit
                if (artist.isNotEmpty()) Text(artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                details?.releaseTitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                (ReleaseDetailsExtractor.year(details?.date) ?: details?.originalYear)?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium) }
                val kind = listOfNotNull(
                    release?.releaseGroup?.let { group -> (listOfNotNull(group.primaryType) + group.secondaryTypes).joinToString(" + ").ifEmpty { null } },
                    release?.media?.firstOrNull()?.format,
                    details?.country,
                )
                if (kind.isNotEmpty()) Text(kind.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
        }
        CoverFacts(card.cover)
        Confidence(card.match.score, card.band)
        Text(
            reasonText(card),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ReviewTags.REASON),
        )
    }
}

@Composable
private fun CoverPicture(cover: CoverState) {
    val image = (cover as? CoverState.Ready)?.line?.new ?: return
    val bitmap = remember(image) { BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)?.asImageBitmap() } ?: return
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(CARD_COVER_DP.dp).clip(RoundedCornerShape(8.dp)),
    )
}

/** The proposed cover's size in pixels and bytes, from another edition when so, or that it loads or failed. */
@Composable
private fun CoverFacts(cover: CoverState) {
    val text = when (cover) {
        CoverState.Loading -> stringResource(R.string.review_cover_loading)
        CoverState.Failed -> stringResource(R.string.review_cover_failed)
        CoverState.None -> return
        is CoverState.Ready -> imageFacts(cover.line)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
    if ((cover as? CoverState.Ready)?.line?.new?.anotherEdition == true) {
        Text(stringResource(R.string.review_another_edition), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun imageFacts(line: CoverLine): String {
    val context = LocalContext.current
    val size = line.new.size
    val bytes = Formatter.formatShortFileSize(context, line.new.bytes.size.toLong())
    return if (size == null) bytes else stringResource(R.string.review_pixels, size.width, size.height) + " · " + bytes
}

/** The confidence as a percentage and the band's name, beside the band's color, which is never alone. */
@Composable
private fun Confidence(score: Double, band: Band) {
    val percent = percentFormat().format(score)
    val bandName = stringResource(if (band == Band.HIGH) R.string.review_band_high else R.string.review_band_medium)
    val description = stringResource(R.string.review_confidence_description, percent, bandName)
    val color = if (band == Band.HIGH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(ReviewTags.CONFIDENCE).semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Text(percent, style = MaterialTheme.typography.titleMedium)
        Text(bandName, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun percentFormat(): NumberFormat = NumberFormat.getPercentInstance(currentLocale())

@Composable
private fun currentLocale(): Locale = LocalResources.current.configuration.locales[0] ?: Locale.getDefault()

/** The reason's parts joined by " · " (TI §6), numbers formatted for the locale. */
@Composable
private fun reasonText(card: Card): String {
    val locale = currentLocale()
    val percent = percentFormat()
    val score = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    val parts = ReasonParts.of(card.match.reason).map { part ->
        when (part) {
            is ReasonPart.Stage -> {
                val name = stageName(part.source).let { if (part.byText) stringResource(R.string.review_stage_by_text, it) else it }
                part.fingerprintScore?.let { stringResource(R.string.review_stage_with_score, name, score.format(it)) } ?: name
            }
            is ReasonPart.Duration -> stringResource(
                R.string.review_reason_duration,
                if (part.seconds == 0L) "±0" else String.format(locale, "%+d", part.seconds),
            )
            is ReasonPart.TitleMatch -> stringResource(R.string.review_reason_title, percent.format(part.percent / 100.0))
            is ReasonPart.ArtistMatch -> stringResource(R.string.review_reason_artist, percent.format(part.percent / 100.0))
            is ReasonPart.Differs -> stringResource(
                when (part.field) {
                    HintField.TITLE -> R.string.review_reason_title_differs
                    HintField.ARTIST -> R.string.review_reason_artist_differs
                },
            )
            is ReasonPart.AlsoFoundBy -> {
                val name = stageName(part.source)
                stringResource(R.string.review_reason_also, part.fingerprintScore?.let { stringResource(R.string.review_stage_with_score, name, score.format(it)) } ?: name)
            }
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun stageName(source: Source): String = stringResource(
    when (source) {
        Source.TAG_RECORDING_ID -> R.string.review_stage_tag_id
        Source.ISRC -> R.string.review_stage_isrc
        Source.FINGERPRINT -> R.string.review_stage_fingerprint
        Source.TEXT_SEARCH -> R.string.review_stage_text
    },
)

/** The other candidates of at least 0.50, best first; the one shown is selected, and another becomes the card. */
@Composable
private fun Candidates(state: ReviewState.Ended, onChoose: (Int) -> Unit) {
    val percent = percentFormat()
    Text(
        stringResource(R.string.review_other_candidates),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    state.candidates.take(MAX_CANDIDATES).forEachIndexed { index, match ->
        val selected = state.card?.index == index
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = !state.busy && state.loading == null, role = Role.Button) { onChoose(index) }
                .padding(vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(match.candidate.title, style = MaterialTheme.typography.bodyLarge)
                if (match.candidate.artistCredit.isNotEmpty()) Text(match.candidate.artistCredit, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.loading == index) {
                CircularProgressIndicator(Modifier.size(20.dp))
            } else {
                Text(percent.format(match.score), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The winner and at most four others (TI §6). */
private const val MAX_CANDIDATES = 5

/** The diff: a line per field with its checkbox, the cover line and the note of protected fields. */
@Composable
private fun Diff(card: Card, onToggle: (ProtectedField) -> Unit, oldCover: (@Composable () -> Unit)?) {
    Text(stringResource(R.string.review_changes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    for (line in card.lines) DiffRow(line, onToggle)
    (card.cover as? CoverState.Ready)?.line?.let { CoverRow(it, onToggle, oldCover) }
    if (card.protectedFields.isNotEmpty()) {
        val names = ProtectedField.entries.filter { it in card.protectedFields }.map { stringResource(protectedFieldLabel(it)) }
        Text(stringResource(R.string.review_protected_note, names.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DiffRow(line: DiffLine, onToggle: (ProtectedField) -> Unit) {
    val label = stringResource(protectedFieldLabel(line.field))
    val old = valueText(line.old)
    val new = valueText(line.new)
    val noChange = stringResource(R.string.review_no_change)
    val description = if (line.unchanged) {
        stringResource(R.string.review_line_unchanged_description, label, old, noChange)
    } else {
        stringResource(R.string.review_line_description, label, old, new)
    }
    CheckRow(line.ticked, description, Modifier.testTag(ReviewTags.line(line.field)), { onToggle(line.field) }) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (line.unchanged) {
            Text(old, style = MaterialTheme.typography.bodyMedium)
            Text(noChange, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(old, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("→ $new", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CoverRow(line: CoverLine, onToggle: (ProtectedField) -> Unit, oldCover: (@Composable () -> Unit)?) {
    val label = stringResource(protectedFieldLabel(ProtectedField.COVER))
    val old = when (val shown = line.old) {
        ShownCover.None -> stringResource(R.string.review_cover_none)
        ShownCover.Thumbnail -> stringResource(R.string.review_cover_thumbnail)
        is ShownCover.Known -> stringResource(R.string.review_pixels, shown.width, shown.height)
        is ShownCover.Own -> stringResource(if (shown.song) R.string.details_cover_song_own else R.string.details_cover_album_own)
    }
    val new = imageFacts(line)
    CheckRow(line.ticked, stringResource(R.string.review_line_description, label, old, new), Modifier.testTag(ReviewTags.COVER_LINE), {
        onToggle(ProtectedField.COVER)
    }) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            oldCover?.invoke()
            Text(old, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("→ $new", style = MaterialTheme.typography.bodyMedium)
        (line.old as? ShownCover.Own)?.let { own ->
            Text(
                stringResource(if (own.song) R.string.review_cover_own_song else R.string.review_cover_own_album),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A row that is one checkbox for TalkBack, read by [description], with the box first and [content] under its label. */
@Composable
private fun CheckRow(
    ticked: Boolean,
    description: String,
    modifier: Modifier,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = ticked, role = Role.Checkbox) { onToggle() }
            .semantics { contentDescription = description }
            .padding(vertical = 4.dp),
    ) {
        Checkbox(checked = ticked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 12.dp)) { content() }
    }
}

@Composable
private fun valueText(value: FieldValue?): String = when (value) {
    null -> stringResource(R.string.review_no_value)
    is FieldValue.Text -> value.text
    is FieldValue.OfTotal -> value.total?.let { stringResource(R.string.details_of_total, value.number, it) } ?: value.number.toString()
    is FieldValue.Flag -> stringResource(if (value.on) R.string.details_yes else R.string.details_no)
    is FieldValue.Ids -> pluralStringResource(R.plurals.review_ids, value.ids.size, value.ids.size)
}
