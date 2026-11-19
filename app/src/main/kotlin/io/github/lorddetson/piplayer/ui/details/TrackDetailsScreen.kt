package io.github.lorddetson.piplayer.ui.details

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.score.HintField
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.library.details.AndroidPictureProbe
import io.github.lorddetson.piplayer.library.details.Badge
import io.github.lorddetson.piplayer.library.details.BadgeState
import io.github.lorddetson.piplayer.library.details.CoverSource
import io.github.lorddetson.piplayer.library.details.DecisionTarget
import io.github.lorddetson.piplayer.library.details.IdentificationFacts
import io.github.lorddetson.piplayer.library.details.ListDecision
import io.github.lorddetson.piplayer.library.details.TraceRun
import io.github.lorddetson.piplayer.library.details.TraceStep
import io.github.lorddetson.piplayer.library.details.ValueSource
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.ui.identification.IdentifyTarget
import io.github.lorddetson.piplayer.ui.identification.LocalIdentify
import io.github.lorddetson.piplayer.ui.identification.ReasonPart
import io.github.lorddetson.piplayer.ui.identification.ReasonParts
import io.github.lorddetson.piplayer.library.details.DetailField
import io.github.lorddetson.piplayer.library.details.DetailRow
import io.github.lorddetson.piplayer.library.details.DetailSection
import io.github.lorddetson.piplayer.library.details.DetailValue
import io.github.lorddetson.piplayer.library.details.DetailsCover
import io.github.lorddetson.piplayer.library.details.LengthSource
import io.github.lorddetson.piplayer.library.details.TagState
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.library.details.TrackDetailsSources
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.lists.FolderLevels
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.ui.covers.LibraryCover
import io.github.lorddetson.piplayer.ui.player.CoverImage
import io.github.lorddetson.piplayer.ui.player.kilohertz
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The details of the song [fileId] of the library, or of the file opened from another app at [uri] (docs/library.md §18),
 * read off the main thread. A song of the library is read again whenever the library, a batch of the tag pass, the
 * identities, the mirrored favorites and plays, the decided values, the covers on screen (own covers, decisions, online
 * lookups) change, a late Cover Art Archive load finds a picture or an action of the screen ends, so that the screen
 * follows it; an opened file is read once for the life of the screen, since each read costs a `:tagger` call
 * (docs/library.md §18.5, §18.7).
 */
class TrackDetailsViewModel(application: Application, private val fileId: Long?, private val uri: String?) : AndroidViewModel(application) {

    private val app = application

    private val runner = DetailsActionRunner(viewModelScope, onError = { Log.w(TAG, "A decision of the details could not be saved", it) })

    /** The screen's decisions (§18.7); none for a file opened from another app. */
    val commands: DetailsCommands? = fileId?.let { DetailsCommands(LibraryDetailsActions(app), runner) }

    val state: StateFlow<DetailsState> = DetailsStates.of(
        viewModelScope,
        changes(),
        once = fileId == null,
        onError = { Log.w(TAG, "The details could not be read", it) },
    ) { withContext(Dispatchers.IO) { read() } }

    private fun changes(): Flow<Unit> {
        if (fileId == null || !Library.mayRead(app)) return flow { emit(Unit) }
        return combine(
            listOf<Flow<Any?>>(
                SongList.changes(app),
                SongList.batches,
                IdentityAttacher.changes,
                Library.mirrorVersion(app),
                Library.decisionVersion(app),
                Covers.changes(app),
                AndroidPictureProbe.lateLoads,
                runner.reads,
            ),
        ) { }
    }

    private suspend fun read(): DetailsState.Found? = when {
        fileId != null -> if (Library.mayRead(app)) TrackDetailsSources.library(app).read(fileId)?.let { details ->
            DetailsState.Found(details, tagRecoveryMessage = tagRecoveryMessage(details))
        } else null
        uri != null -> {
            val opened = TrackDetailsSources.opened(app)
            val details = opened.read(uri)
            // The header shows the opened file's embedded picture; a song of the library has the loader's cover.
            val embedded = details.value(DetailField.COVER_SOURCE) == DetailValue.Cover(CoverSource.EMBEDDED)
            val px = (DetailsCover.HEADER_DP * app.resources.displayMetrics.density).roundToInt()
            DetailsState.Found(details, if (embedded) opened.picture(uri, px)?.asImageBitmap() else null)
        }
        else -> null
    }

    private suspend fun tagRecoveryMessage(details: TrackDetails): Int? {
        val identity = details.target?.identityId ?: return null
        val writer = (app as? PiPlayerApplication)?.batchTagWriter ?: return null
        return try {
            val pending = writer.recoverableWrites(identity).firstOrNull { it.fileId == details.fileId } ?: return null
            if (pending.failed) R.string.tag_recovery_failed else R.string.tag_recovery_interrupted
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the tag write state", error)
            null
        }
    }

    private companion object {
        const val TAG = "TrackDetails"
    }
}

/**
 * The details screen (docs/library.md §18.1): a header with the cover, the title and the artist, then the sections with
 * their rows. A long press copies a row's value; a MusicBrainz row opens its page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailsScreen(
    fileId: Long?,
    uri: String?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onTagHistory: ((Long) -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as Application
    val model = viewModel(key = "details ${fileId ?: uri}") { TrackDetailsViewModel(app, fileId, uri) }
    val state by model.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.details), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
                },
                actions = {
                    io.github.lorddetson.piplayer.ui.identification.IdentifyAction(fileId, uri)
                    val identityId = (state as? DetailsState.Found)?.details?.target?.identityId
                    if (identityId != null && onTagHistory != null) {
                        TextButton(onClick = { onTagHistory(identityId) }) { Text(stringResource(R.string.tag_history_open)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (val shown = state) {
            DetailsState.Loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            DetailsState.Gone -> Text(
                stringResource(R.string.details_not_found),
                style = MaterialTheme.typography.bodyLarge,
                modifier = modifier.padding(16.dp),
            )
            DetailsState.Failed -> Text(
                stringResource(R.string.details_failed),
                style = MaterialTheme.typography.bodyLarge,
                modifier = modifier.padding(16.dp),
            )
            is DetailsState.Found -> Column(modifier) {
                val recovery = shown.tagRecoveryMessage
                val identity = shown.details.target?.identityId
                if (recovery != null && identity != null && onTagHistory != null) {
                    TextButton(onClick = { onTagHistory(identity) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(recovery))
                    }
                }
                DetailsContent(shown.details, shown.picture, snackbar, model.commands, Modifier.weight(1f))
            }
        }
    }
}

/**
 * The details shown (docs/library.md §18.1, §18.7): the header, the sections with their rows and, from M3, the sources,
 * the decisions [commands] makes for the song (none for a file opened from another app), the Identification section and
 * the trace.
 */
@Composable
fun DetailsContent(details: TrackDetails, picture: ImageBitmap?, snackbar: SnackbarHostState, commands: DetailsCommands?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val say = { id: Int -> scope.launch { snackbar.showSnackbar(resources.getString(id)) }; Unit }
    val copy = { label: String, text: String ->
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        // From Android 13 the system confirms a copy itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) say(R.string.details_copied)
    }
    val open = { url: String ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            say(R.string.details_no_app)
        }
    }
    commands?.let { Messages(it, snackbar) }
    // Decisions need the song's identity (§18.7).
    val decide = commands?.takeIf { details.target != null }
    val identify = LocalIdentify.current
    val list = rememberLazyListState()
    val sections = remember(details) { details.sections }
    val identificationShown = details.badge != null || details.identification != null || details.listDecision != null
    // The header, then a heading and the rows of each section.
    val identificationIndex = 1 + sections.sumOf { 1 + it.second.size }
    val onBadge = { badge: Badge ->
        val id = details.fileId
        if (badge.state == BadgeState.FROM_TAGS) {
            if (id != null) identify?.invoke(IdentifyTarget.Song(id))
        } else {
            scope.launch { list.animateScrollToItem(identificationIndex) }
        }
        Unit
    }
    LazyColumn(modifier.testTag(DetailsTags.LIST), state = list) {
        item(key = "header") { Header(details, picture, onBadge) }
        for ((section, rows) in sections) {
            item(key = section) { SectionHeading(stringResource(sectionLabel(section))) }
            // Only the first row of a field offers its decisions: the IDs are one field.
            val firsts = rows.withIndex().filter { (i, row) -> row.decided && rows.subList(0, i).none { it.field.protected == row.field.protected } }
                .mapTo(HashSet()) { it.index }
            itemsIndexed(rows) { i, row ->
                val target = details.target
                val first = i in firsts
                RowItem(row, copy, open, first, if (decide != null && target != null && first) RowDecisions(decide, target) else null)
            }
        }
        if (identificationShown) {
            item(key = "identification") { SectionHeading(stringResource(R.string.details_section_identification), Modifier.testTag(DetailsTags.IDENTIFICATION)) }
            details.badge?.let { badge -> item(key = "badge") { BadgeRow(badge) } }
            details.identification?.let { facts -> item(key = "identified") { IdentificationRow(facts, decide, details.target) } }
            details.listDecision?.let { state -> item(key = "list") { ListDecisionRow(state, decide) } }
        }
        if (details.trace.isNotEmpty()) {
            item(key = "trace") { SectionHeading(stringResource(R.string.details_section_trace), Modifier.testTag(DetailsTags.TRACE)) }
            items(details.trace) { run -> TraceRunRow(run) }
        }
    }
}

/** What a row can decide: its field's revert, or the choices of a field changed in the file (§18.7). */
private class RowDecisions(val commands: DetailsCommands, val target: DecisionTarget)

/**
 * The message of each action that ends, with Undo for a revert (§18.7). A new message takes the place of the screen's own
 * message, never of another screen's on the shared host; an Undo tapped while an action runs waits for it to end.
 */
@Composable
private fun Messages(commands: DetailsCommands, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(commands) {
        var shown: Job? = null
        commands.runner.outcomes.collect { outcome ->
            shown?.cancel()
            val undo = (outcome as? DetailsOutcome.Done)?.undo
            shown = launch {
                val result = snackbar.showSnackbar(
                    resources.getString(messageOf(outcome)),
                    actionLabel = undo?.let { resources.getString(R.string.review_undo) },
                    duration = SnackbarDuration.Short,
                )
                // Queued in the runner: an action that runs, or a newer message, does not lose it.
                if (result == SnackbarResult.ActionPerformed && undo != null) commands.undoWhenFree(undo)
            }
        }
    }
}

private fun messageOf(outcome: DetailsOutcome): Int = when (outcome) {
    is DetailsOutcome.Failed -> R.string.details_action_failed
    is DetailsOutcome.Done -> when (outcome.action) {
        DetailsAction.REVERT, DetailsAction.USE_FILE_VALUE -> R.string.details_reverted
        DetailsAction.KEEP_VALUE -> R.string.details_value_kept
        DetailsAction.UNDO_IDENTIFICATION -> R.string.details_identification_undone
        DetailsAction.RESET -> R.string.details_reset_done
        DetailsAction.UNDO -> R.string.details_undone
    }
}

/**
 * The header (docs/library.md §18.1): the loader's cover of a song of the library, the embedded [picture] of an opened
 * file, and the metadata badge (§18.7), whose tap does [onBadge].
 */
@Composable
private fun Header(details: TrackDetails, picture: ImageBitmap?, onBadge: (Badge) -> Unit) {
    Row(
        Modifier.testTag(DetailsTags.HEADER).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(8.dp)
        val id = details.fileId
        if (id != null) {
            LibraryCover(CoverRequest.Track(id), details.album, size = DetailsCover.HEADER_DP.dp, shape = shape)
        } else {
            CoverImage(picture, Modifier.size(DetailsCover.HEADER_DP.dp).clip(shape), placeholderName = details.album)
        }
        Column {
            Text(details.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            details.artist?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2)
            }
            details.badge?.let { badge -> MetadataBadge(badge, onClick = { onBadge(badge) }, Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * A row: its label over its value and the value's source, read by TalkBack as one; a long press copies, a link opens on a
 * tap. The [first] row of a decided field says that the file changed it, and with [decisions] offers its revert, or the two
 * choices when the file changed it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun RowItem(row: DetailRow, copy: (String, String) -> Unit, open: (String) -> Unit, first: Boolean, decisions: RowDecisions?) {
    val label = stringResource(fieldLabel(row.field))
    val text = valueText(row)
    val id = (row.value as? DetailValue.MusicBrainz)?.id ?: (row.value as? DetailValue.AcoustId)?.id
    val copied = id ?: text
    val url = (row.value as? DetailValue.MusicBrainz)?.url ?: (row.value as? DetailValue.AcoustId)?.url
    val copyLabel = stringResource(R.string.details_copy)
    val base = Modifier.testTag(DetailsTags.row(row.field))
    val gestures = if (url != null) {
        base.combinedClickable(
            onClickLabel = stringResource(if (row.value is DetailValue.AcoustId) R.string.details_open_acoustid else R.string.details_open_musicbrainz),
            role = Role.Button,
            onLongClickLabel = copyLabel,
            onLongClick = { copy(label, copied) },
            onClick = { open(url) },
        )
    } else {
        base
            .pointerInput(copied) { detectTapGestures(onLongPress = { copy(label, copied) }) }
            .semantics(mergeDescendants = true) {
                onLongClick(copyLabel) {
                    copy(label, copied)
                    true
                }
            }
    }
    val source = row.source?.let { sourceText(it) }
    // A field says it once, on its first row: the IDs are one field.
    val changed = if (row.changedInFile && first) stringResource(R.string.details_changed_in_file) else null
    val notes = listOfNotNull(source, changed)
    Column {
        ListItem(
            overlineContent = { Text(label) },
            headlineContent = { Text(text, color = if (url != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
            supportingContent = if (notes.isEmpty()) null else ({ Column { notes.forEach { Text(it) } } }),
            modifier = gestures,
        )
        val field = row.field.protected
        if (decisions != null && field != null) {
            FlowRow(Modifier.padding(start = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val commands = decisions.commands
                val target = decisions.target
                if (row.changedInFile) {
                    TextButton(onClick = { commands.keepPiPlayersValue(target, field) }, Modifier.testTag(DetailsTags.keep(row.field))) {
                        Text(stringResource(R.string.details_keep_value))
                    }
                    TextButton(onClick = { commands.useFilesValue(target, field) }, Modifier.testTag(DetailsTags.useFile(row.field))) {
                        Text(stringResource(R.string.details_use_file_value))
                    }
                } else {
                    TextButton(onClick = { commands.revert(target, field) }, Modifier.testTag(DetailsTags.revert(row.field))) {
                        Text(stringResource(R.string.details_revert))
                    }
                }
            }
        }
    }
}

/** Where a value comes from (§18.7). */
@Composable
private fun sourceText(source: ValueSource): String = when (source.level) {
    DecisionKind.LEVEL_USER_EDIT -> stringResource(R.string.details_source_edited)
    DecisionKind.LEVEL_IDENTIFICATION -> source.provider?.let { stringResource(R.string.details_source_identified, providerName(it)) }
        ?: stringResource(R.string.details_source_identified_plain)
    DecisionKind.LEVEL_TAGS -> stringResource(R.string.details_source_tags)
    DecisionKind.LEVEL_INDEX -> stringResource(R.string.details_source_index)
    else -> stringResource(R.string.details_source_file_name)
}

/** The "Metadata" row of the Identification section: the badge's state (§18.7). */
@Composable
private fun BadgeRow(badge: Badge) {
    ListItem(
        overlineContent = { Text(stringResource(R.string.details_metadata)) },
        headlineContent = { Text(badgeLabel(badge)) },
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}

/** The newest identification not undone, with "Undo identification" while the song can decide (§18.7). */
@Composable
private fun IdentificationRow(facts: IdentificationFacts, commands: DetailsCommands?, target: DecisionTarget?) {
    val locale = currentLocale()
    val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    val headline = listOfNotNull(
        facts.provider?.let { providerName(it) },
        facts.confidence?.let { NumberFormat.getPercentInstance(locale).format(it) },
    ).joinToString(" · ").ifEmpty { stringResource(R.string.details_identification) }
    val lines = listOfNotNull(
        facts.reason?.let { reasonText(it) },
        stringResource(R.string.details_identification_applied, dates.format(Date(facts.createdAt))),
        facts.fetchedAt?.let { stringResource(R.string.details_identification_fetched, dates.format(Date(it))) },
    )
    Column {
        ListItem(
            overlineContent = { Text(stringResource(R.string.details_identification)) },
            headlineContent = { Text(headline) },
            supportingContent = { Text(lines.joinToString("\n")) },
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
        if (commands != null && target != null) {
            TextButton(
                onClick = { commands.undoIdentification(target) },
                modifier = Modifier.padding(start = 8.dp).testTag(DetailsTags.UNDO_IDENTIFICATION),
            ) { Text(stringResource(R.string.details_undo_identification)) }
        }
    }
}

/** The Keep as-is or Skip that counts, with "Reset" while the song can decide (§18.7). */
@Composable
private fun ListDecisionRow(state: ListDecision, commands: DetailsCommands?) {
    Column {
        ListItem(
            overlineContent = { Text(stringResource(R.string.smart_missing_info)) },
            headlineContent = { Text(stringResource(if (state.skipped) R.string.review_skipped else R.string.review_kept)) },
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
        if (commands != null) {
            TextButton(onClick = { commands.reset(state) }, modifier = Modifier.padding(start = 8.dp).testTag(DetailsTags.RESET)) {
                Text(stringResource(R.string.details_reset))
            }
        }
    }
}

/** One run of the trace: its start, its outcome with the retry time, and its steps (§18.7). */
@Composable
private fun TraceRunRow(run: TraceRun) {
    val locale = currentLocale()
    val start = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale).format(Date(run.startedAt))
    // The service that left the run unavailable or stopped it.
    val outcome = run.outcome?.let { outcomeText(it) }?.let { text -> run.provider?.let { "$text · ${providerCode(it)}" } ?: text }
    // A retry may fall on another day than the run.
    val retry = run.retryAt?.let {
        stringResource(R.string.trace_retry, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(it)))
    }
    val steps = run.steps.map { stepText(it) }
    ListItem(
        overlineContent = { Text(start) },
        headlineContent = { Text(listOfNotNull(outcome, retry).joinToString(" · ")) },
        supportingContent = if (steps.isEmpty()) null else ({ Text(steps.joinToString("\n")) }),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}

/** An outcome code of the trace in words; a code this build does not know as it is. */
@Composable
private fun outcomeText(code: String): String = when {
    code == "found=high" -> stringResource(R.string.trace_outcome_high)
    code == "found=medium" -> stringResource(R.string.trace_outcome_medium)
    code == "found=no_match" -> stringResource(R.string.trace_outcome_no_match)
    code == "no_key" -> stringResource(R.string.trace_outcome_no_key)
    code == "unavailable" -> stringResource(R.string.trace_outcome_unavailable)
    code.startsWith("provider_stopped") -> stringResource(R.string.trace_outcome_provider_stopped)
    code == "unreadable" -> stringResource(R.string.trace_outcome_unreadable)
    code == "disabled" -> stringResource(R.string.trace_outcome_disabled)
    code == "no_network" -> stringResource(R.string.trace_outcome_no_network)
    code == "captive_portal" -> stringResource(R.string.trace_outcome_captive_portal)
    else -> code
}

/** A step of the trace: its stage, provider, HTTP status, results, best score, time and why it was skipped or failed. */
@Composable
private fun stepText(step: TraceStep): String {
    val locale = currentLocale()
    val resources = LocalResources.current
    val seconds = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    return listOfNotNull(
        stageText(step.stage),
        step.provider?.let { providerCode(it) },
        step.httpStatus?.let { stringResource(R.string.trace_http, it) },
        step.resultCount?.let { resources.getQuantityString(R.plurals.trace_results, it, it) },
        step.bestScore?.let { stringResource(R.string.trace_best, NumberFormat.getPercentInstance(locale).format(it)) },
        step.elapsedMs?.let { stringResource(R.string.trace_seconds, seconds.format(it / 1_000.0)) },
        step.skipped?.let { stringResource(R.string.trace_skipped, it) },
        step.failed?.let { stringResource(R.string.trace_failed, it) },
    ).joinToString(" · ")
}

@Composable
private fun stageText(code: String): String = when (code) {
    "local_read" -> stringResource(R.string.trace_stage_local_read)
    "known_ids" -> stringResource(R.string.trace_stage_known_ids)
    "isrc" -> stringResource(R.string.trace_stage_isrc)
    "fingerprint" -> stringResource(R.string.trace_stage_fingerprint)
    "text_search" -> stringResource(R.string.trace_stage_text_search)
    "release_choice" -> stringResource(R.string.trace_stage_release_choice)
    else -> code
}

/** A provider code of the trace by its name. */
@Composable
private fun providerCode(code: String): String = ProviderId.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == code }
    ?.let { providerName(it) } ?: code

/** The reason of an identification (TI §4.1) as the review's card formats it: its parts joined by " · ". */
@Composable
private fun reasonText(reason: Reason): String {
    val locale = currentLocale()
    val percent = NumberFormat.getPercentInstance(locale)
    val score = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return ReasonParts.of(reason).map { part ->
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
    }.joinToString(" · ")
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

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.ROOT

/** A row's value as shown (docs/library.md §18.2). */
@Composable
private fun valueText(row: DetailRow): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    return when (val value = row.value) {
        is DetailValue.Text -> value.text
        is DetailValue.Texts -> value.texts.joinToString("; ")
        is DetailValue.OfTotal -> value.total?.let { stringResource(R.string.details_of_total, value.number, it) } ?: value.number.toString()
        is DetailValue.Flag -> stringResource(if (value.on) R.string.details_yes else R.string.details_no)
        is DetailValue.Count -> NumberFormat.getIntegerInstance(locale).format(value.count)
        is DetailValue.Time -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(value.epochMs))
        is DetailValue.Length -> {
            val length = DetailFormat.length(value.ms)
            // The index's own row is just its length; the length shown says where it comes from.
            if (row.field == DetailField.INDEX_LENGTH) length else stringResource(lengthLabel(value.source), length)
        }
        is DetailValue.Bytes -> bytes(context, value.bytes, locale)
        is DetailValue.Kbps -> stringResource(R.string.format_kbps, value.kbps)
        is DetailValue.Hertz -> stringResource(R.string.format_khz, kilohertz(value.hz, locale))
        is DetailValue.Channels -> when (value.count) {
            1 -> stringResource(R.string.format_mono)
            2 -> stringResource(R.string.details_stereo)
            else -> resources.getQuantityString(R.plurals.format_channels, value.count, value.count)
        }
        is DetailValue.Bits -> stringResource(R.string.format_bits, value.bits)
        is DetailValue.Gain -> {
            val db = decimals(locale, 2).format(value.db)
            value.peak?.let { stringResource(R.string.details_gain_peak, db, decimals(locale, 6).format(it)) } ?: stringResource(R.string.details_gain, db)
        }
        is DetailValue.Folder -> {
            val volume = if (value.volume == FolderLevels.PRIMARY) stringResource(R.string.internal_storage) else value.volume
            "$volume/${value.relativePath}"
        }
        is DetailValue.Tags -> stringResource(tagStateLabel(value.state))
        is DetailValue.Cover -> stringResource(coverLabel(value.source))
        is DetailValue.Pixels -> value.bytes?.let { stringResource(R.string.details_pixels_bytes, value.width, value.height, Formatter.formatFileSize(context, it)) }
            ?: stringResource(R.string.details_pixels, value.width, value.height)
        is DetailValue.MusicBrainz -> value.id
        is DetailValue.AcoustId -> value.id
        DetailValue.Empty -> stringResource(R.string.details_empty)
    }
}

/** A size in the unit that fits, with its exact number of bytes. */
private fun bytes(context: Context, bytes: Long, locale: Locale): String =
    context.getString(R.string.details_bytes, Formatter.formatFileSize(context, bytes), NumberFormat.getIntegerInstance(locale).format(bytes))

private fun decimals(locale: Locale, digits: Int) = NumberFormat.getNumberInstance(locale).apply {
    maximumFractionDigits = digits
    minimumFractionDigits = minOf(digits, 2)
}

private fun lengthLabel(source: LengthSource?) = when (source) {
    LengthSource.HEADER, null -> R.string.details_length_header
    LengthSource.WALK -> R.string.details_length_walk
    LengthSource.HEADER_UNCHECKED -> R.string.details_length_header_unchecked
    LengthSource.INDEX -> R.string.details_length_index
    LengthSource.ESTIMATE -> R.string.details_length_estimate
}

private fun tagStateLabel(state: TagState) = when (state) {
    TagState.NOT_READ -> R.string.details_tags_not_read
    TagState.UNSUPPORTED -> R.string.details_tags_unsupported
    TagState.FAILED -> R.string.details_tags_failed
    TagState.CANNOT_READ -> R.string.details_tags_cannot_read
}

private fun coverLabel(source: CoverSource) = when (source) {
    CoverSource.SONG_OWN -> R.string.details_cover_song_own
    CoverSource.ALBUM_OWN -> R.string.details_cover_album_own
    CoverSource.SAVED -> R.string.details_cover_saved
    CoverSource.EMBEDDED -> R.string.details_cover_embedded
    CoverSource.THUMBNAIL -> R.string.details_cover_thumbnail
    CoverSource.CAA -> R.string.details_cover_caa
    CoverSource.NONE -> R.string.details_cover_none
}

private fun sectionLabel(section: DetailSection) = when (section) {
    DetailSection.SONG -> R.string.details_section_song
    DetailSection.PLAYS -> R.string.details_section_plays
    DetailSection.FILE -> R.string.details_section_file
    DetailSection.COVER -> R.string.details_section_cover
    DetailSection.IDS -> R.string.details_section_ids
}

private fun fieldLabel(field: DetailField) = when (field) {
    DetailField.TITLE -> R.string.details_field_title
    DetailField.ARTIST -> R.string.details_field_artist
    DetailField.ALBUM -> R.string.details_field_album
    DetailField.ALBUM_ARTIST -> R.string.details_field_album_artist
    DetailField.TRACK -> R.string.details_field_track
    DetailField.DISC -> R.string.details_field_disc
    DetailField.DISC_SUBTITLE -> R.string.details_field_disc_subtitle
    DetailField.DATE -> R.string.details_field_date
    DetailField.ORIGINAL_DATE -> R.string.details_field_original_date
    DetailField.GENRE -> R.string.details_field_genre
    DetailField.COMPOSER -> R.string.details_field_composer
    DetailField.COMPILATION -> R.string.details_field_compilation
    DetailField.LABEL -> R.string.details_field_label
    DetailField.CATALOG_NUMBER -> R.string.details_field_catalog_number
    DetailField.BARCODE -> R.string.details_field_barcode
    DetailField.ISRC -> R.string.details_field_isrc
    DetailField.TRACK_GAIN -> R.string.details_field_track_gain
    DetailField.ALBUM_GAIN -> R.string.details_field_album_gain
    DetailField.FAVORITE -> R.string.details_field_favorite
    DetailField.PLAY_COUNT -> R.string.details_field_play_count
    DetailField.LAST_PLAYED -> R.string.details_field_last_played
    DetailField.FORMAT -> R.string.details_field_format
    DetailField.CODEC -> R.string.details_field_codec
    DetailField.BITRATE -> R.string.details_field_bitrate
    DetailField.SAMPLE_RATE -> R.string.details_field_sample_rate
    DetailField.CHANNELS -> R.string.details_field_channels
    DetailField.BIT_DEPTH -> R.string.details_field_bit_depth
    DetailField.LENGTH -> R.string.details_field_length
    DetailField.INDEX_LENGTH -> R.string.details_field_index_length
    DetailField.SIZE -> R.string.details_field_size
    DetailField.FILE_NAME -> R.string.details_field_file_name
    DetailField.FOLDER -> R.string.details_field_folder
    DetailField.MIME_TYPE -> R.string.details_field_mime_type
    DetailField.TAG_ENCODING -> R.string.details_field_tag_encoding
    DetailField.ADDED -> R.string.details_field_added
    DetailField.MODIFIED -> R.string.details_field_modified
    DetailField.TAGS -> R.string.details_field_tags
    DetailField.COVER_SOURCE -> R.string.details_field_cover_source
    DetailField.COVER_SIZE -> R.string.details_field_cover_size
    DetailField.RECORDING_ID -> R.string.details_field_recording_id
    DetailField.RELEASE_ID -> R.string.details_field_release_id
    DetailField.RELEASE_GROUP_ID -> R.string.details_field_release_group_id
    DetailField.TRACK_ID -> R.string.details_field_track_id
    DetailField.ARTIST_ID -> R.string.details_field_artist_id
    DetailField.ALBUM_ARTIST_ID -> R.string.details_field_album_artist_id
    DetailField.ACOUSTID_ID -> R.string.details_field_acoustid_id
}
