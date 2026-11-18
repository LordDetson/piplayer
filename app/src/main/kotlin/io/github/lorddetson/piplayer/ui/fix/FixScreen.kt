package io.github.lorddetson.piplayer.ui.fix

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.identification.batch.BatchCard
import io.github.lorddetson.piplayer.identification.batch.BatchControl
import io.github.lorddetson.piplayer.identification.batch.BatchReview
import io.github.lorddetson.piplayer.identification.batch.BatchReviewRead
import io.github.lorddetson.piplayer.identification.batch.FileOutcome
import io.github.lorddetson.piplayer.identification.batch.RoomBatchReviewStore
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.userdata.UserData
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Test tags of the Fix tab (TI §8). */
object FixTags {
    /** The "Needs attention" entry of the review queue. */
    const val NEEDS_ATTENTION = "fix_needs_attention"

    /** The counts of the batches by status. */
    const val COUNTERS = "fix_counters"

    /** The queue of the batches, each review batch with its album cards. */
    const val LIST = "fix_batches"

    /** The ETA placeholder of a processing batch. */
    const val ETA = "fix_eta"

    /** The album card [index] of the batch [batchUuid]. */
    fun card(batchUuid: String, index: Int) = "fix_card_${batchUuid}_$index"
}

/**
 * The batches of the Fix tab and the album cards of those in review (TI §8), read again on every start: a one-shot read
 * through `Library.read` and the user database can never read a database that a rebuild closed. A batch whose cards are
 * all settled moves to `done` during that read ([BatchReviewRead]).
 */
class FixViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableBatches = MutableStateFlow<List<BatchEntity>?>(null)
    private val mutableCards = MutableStateFlow<Map<String, List<BatchCard>>>(emptyMap())

    /** The batches, oldest first; null until the first read. */
    val batches: StateFlow<List<BatchEntity>?> = mutableBatches.asStateFlow()

    /** The album cards of each batch in review, by the batch's UUID. */
    val cards: StateFlow<Map<String, List<BatchCard>>> = mutableCards.asStateFlow()

    fun onStart() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val views = withContext(Dispatchers.IO) {
                try {
                    BatchReviewRead(RoomBatchReviewStore(LibraryAccess.app(app)) { UserData.database(app) }).review()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The queue still shows: a review that cannot be read leaves the batches without cards.
                    emptyList()
                }
            }
            val batches = withContext(Dispatchers.IO) {
                Library.read(app) { database -> runBlocking { database.batches().all() } }
            }
            mutableBatches.value = batches
            // The cards of the batches that are still in review: a batch the read finished has none to show.
            val reviewing = batches.filter { it.status == BatchEntity.STATUS_REVIEW }.mapTo(HashSet()) { it.uuid }
            mutableCards.value = views.filter { it.batch.uuid in reviewing }.associate { it.batch.uuid to it.cards }
        }
    }

    /** Pauses a running batch and reads the queue again (TI §8). */
    fun pause(uuid: String) = control { it.pause(uuid) }

    /** Resumes a paused batch at the group it stopped on (TI §8). */
    fun resume(uuid: String) = control { it.resume(uuid) }

    /** Stops a batch for good (TI §8). */
    fun stop(uuid: String) = control { it.stop(uuid) }

    /** Runs one change off the main thread and reads the queue again, whatever it answered. */
    private fun control(change: suspend (BatchControl) -> Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { change(BatchControl.of(app)) }
            }
            onStart()
        }
    }
}

/**
 * The Fix tab (TI §8, PIM-18): "Needs attention" (the review queue), the counts of the batches by status, then the
 * batches oldest first, each with its source, its start and its status. A batch in review shows the cards of its album
 * groups under it: the album, its tracks, the group's confidence, how its runs ended and how many tracks stand saved.
 * A card opens the single-file review for its first track left to decide ([onOpenReview]), which carries the batch's
 * UUID into the decision it makes. A processing batch also shows the placeholder of the ETA, which M4a does not measure
 * yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixScreen(modifier: Modifier = Modifier, onOpenReview: (fileId: Long, batchUuid: String) -> Unit = { _, _ -> }) {
    val app = LocalContext.current.applicationContext as Application
    val model = viewModel { FixViewModel(app) }
    LifecycleStartEffect(model) {
        model.onStart()
        onStopOrDispose { }
    }
    val batches by model.batches.collectAsStateWithLifecycle()
    val cards by model.cards.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.fix_title), modifier = Modifier.semantics { heading() }) })
        },
        modifier = modifier,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            NeedsAttention(batches)
            Counters(batches)
            when (batches) {
                null -> Loading()
                else -> BatchQueue(batches.orEmpty(), cards, onOpenReview, model::pause, model::resume, model::stop)
            }
        }
    }
}

/** The review queue's entry (TI §8): the number of batches waiting for their cards. */
@Composable
private fun NeedsAttention(batches: List<BatchEntity>?) {
    val waiting = batches.orEmpty().count { it.status == BatchEntity.STATUS_REVIEW }
    ListItem(
        headlineContent = { Text(stringResource(R.string.fix_needs_attention)) },
        supportingContent = {
            Text(
                if (waiting > 0) {
                    pluralStringResource(R.plurals.fix_review_waiting, waiting, waiting)
                } else {
                    stringResource(R.string.fix_needs_attention_none)
                },
            )
        },
        modifier = Modifier.testTag(FixTags.NEEDS_ATTENTION),
    )
    HorizontalDivider()
}

/** The batches counted by status, one counter per status in the order the queue runs them (TI §8). */
@Composable
private fun Counters(batches: List<BatchEntity>?) {
    val counts = batches.orEmpty().groupingBy { it.status }.eachCount()
    // Wrapped rather than scrolled: on a narrow screen every status stays visible, so a counter is never cut off
    // (PIM-26, found by the acceptance run).
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(FixTags.COUNTERS),
    ) {
        for (status in STATUSES) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(statusColor(status), CircleShape))
                Text(
                    "${stringResource(statusLabel(status))}: ${counts[status] ?: 0}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** A row of the queue: a batch, or one of the album cards of a batch in review (TI §8). */
private sealed interface FixRow {

    class Batch(val batch: BatchEntity) : FixRow

    class Card(val batchUuid: String, val card: BatchCard) : FixRow
}

/** The batches oldest first, as the workers pick them up, each review batch followed by its cards (TI §8). */
@Composable
private fun BatchQueue(
    batches: List<BatchEntity>,
    cards: Map<String, List<BatchCard>>,
    onOpenReview: (fileId: Long, batchUuid: String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    if (batches.isEmpty()) {
        Text(
            stringResource(R.string.fix_batches_none),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp).testTag(FixTags.LIST),
        )
        return
    }
    val rows = buildList {
        for (batch in batches) {
            add(FixRow.Batch(batch))
            if (batch.status == BatchEntity.STATUS_REVIEW) {
                cards[batch.uuid].orEmpty().forEach { add(FixRow.Card(batch.uuid, it)) }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag(FixTags.LIST)) {
        items(
            rows,
            key = { row ->
                when (row) {
                    is FixRow.Batch -> row.batch.uuid
                    is FixRow.Card -> "${row.batchUuid}#${row.card.index}"
                }
            },
        ) { row ->
            when (row) {
                is FixRow.Batch -> BatchRow(row.batch, onPause, onResume, onStop)
                is FixRow.Card -> BatchCardRow(row.batchUuid, row.card, onOpenReview)
            }
        }
    }
}

/** One batch: a dot of its status, its source, its start (and the ETA placeholder while processing), and its status. */
@Composable
private fun BatchRow(
    batch: BatchEntity,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    val started = stringResource(R.string.fix_started, startedText(batch.createdAt))
    val eta = if (batch.status == BatchEntity.STATUS_PROCESSING) stringResource(R.string.fix_eta_not_measured) else null
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(sourceLabel(batch.source)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(started, eta).joinToString(" · "), modifier = Modifier.testTag(FixTags.ETA))
        },
        leadingContent = { Box(Modifier.size(12.dp).background(statusColor(batch.status), CircleShape)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        stringResource(statusLabel(batch.status)),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (batch.status in RUNNING) {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.fix_batch_actions),
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (batch.status == BatchEntity.STATUS_PAUSED) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.fix_batch_resume)) },
                                    onClick = { menu = false; onResume(batch.uuid) },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.fix_batch_pause)) },
                                    onClick = { menu = false; onPause(batch.uuid) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.fix_batch_stop)) },
                                onClick = { menu = false; onStop(batch.uuid) },
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * One album card (TI §8): the album (or "No album" for a file that stands alone), its tracks and the group's confidence,
 * how the group's runs ended and how many tracks stand saved. A card with a track left to decide opens that track's
 * review; a settled card is a plain row.
 */
@Composable
private fun BatchCardRow(batchUuid: String, card: BatchCard, onOpenReview: (fileId: Long, batchUuid: String) -> Unit) {
    val next = BatchReview.next(card)
    val open = next != null
    ListItem(
        headlineContent = { Text(card.title ?: stringResource(R.string.fix_card_no_album), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    "${pluralStringResource(R.plurals.fix_card_tracks, card.tracks, card.tracks)} · ${stringResource(bandLabel(card.band))}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("${FixTags.card(batchUuid, card.index)}_band"),
                )
                val outcomes = outcomesText(card)
                if (outcomes.isNotEmpty()) Text(outcomes, style = MaterialTheme.typography.bodySmall)
                if (card.automatic > 0) {
                    Text(
                        pluralStringResource(R.plurals.fix_card_automatic, card.automatic, card.automatic),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    pluralStringResource(R.plurals.fix_card_saved, card.tracks, card.decided, card.tracks),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        leadingContent = { Box(Modifier.size(12.dp).background(bandColor(card.band), CircleShape)) },
        trailingContent = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Text(
                    stringResource(if (card.settled) R.string.fix_card_saved_chip else R.string.fix_card_needs_review),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        modifier = Modifier
            .testTag(FixTags.card(batchUuid, card.index))
            .clickable(enabled = open) { next?.let { onOpenReview(it.file.fileId, batchUuid) } },
    )
}

/** How the card's runs ended, one part per outcome that occurred, in the order of [FileOutcome] (TI §8). */
@Composable
private fun outcomesText(card: BatchCard): String = FileOutcome.entries.mapNotNull { outcome ->
    val count = card.count(outcome)
    if (count == 0) return@mapNotNull null
    pluralStringResource(outcomeLabel(outcome), count, count)
}.joinToString(" · ")

private fun outcomeLabel(outcome: FileOutcome): Int = when (outcome) {
    FileOutcome.FOUND -> R.plurals.fix_card_found
    FileOutcome.NO_MATCH -> R.plurals.fix_card_no_match
    FileOutcome.RETRY_LATER -> R.plurals.fix_card_retry_later
    FileOutcome.NO_RUN -> R.plurals.fix_card_no_run
}

/** The confidence band as text, so that the color is never alone (TI §6). */
private fun bandLabel(band: Band): Int = when (band) {
    Band.HIGH -> R.string.fix_band_high
    Band.MEDIUM -> R.string.fix_band_medium
    Band.NO_MATCH -> R.string.fix_band_no_match
}

@Composable
private fun bandColor(band: Band) = when (band) {
    Band.HIGH -> MaterialTheme.colorScheme.secondary
    Band.MEDIUM -> MaterialTheme.colorScheme.tertiary
    Band.NO_MATCH -> MaterialTheme.colorScheme.outline
}

/** "Started" the way the device writes a date and a time. */
@Composable
private fun startedText(createdAt: Long): String = remember(createdAt) {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(createdAt))
}

/** The statuses in the order the queue runs them: active first, then the final ones (TI §8). */
private val STATUSES = listOf(
    BatchEntity.STATUS_QUEUED,
    BatchEntity.STATUS_PROCESSING,
    BatchEntity.STATUS_PAUSED,
    BatchEntity.STATUS_REVIEW,
    BatchEntity.STATUS_DONE,
    BatchEntity.STATUS_CANCELLED,
)

/** The batches whose jobs can be paused or stopped from the queue; a batch in review has no jobs left to stop. */
private val RUNNING = setOf(BatchEntity.STATUS_QUEUED, BatchEntity.STATUS_PROCESSING, BatchEntity.STATUS_PAUSED)

private fun statusLabel(status: String): Int = when (status) {
    BatchEntity.STATUS_QUEUED -> R.string.batch_status_queued
    BatchEntity.STATUS_PROCESSING -> R.string.batch_status_processing
    BatchEntity.STATUS_PAUSED -> R.string.batch_status_paused
    BatchEntity.STATUS_REVIEW -> R.string.batch_status_review
    BatchEntity.STATUS_DONE -> R.string.batch_status_done
    BatchEntity.STATUS_CANCELLED -> R.string.batch_status_cancelled
    else -> error("Unknown batch status: $status")
}

private fun sourceLabel(source: String): Int = when (source) {
    BatchEntity.SOURCE_FOLDER -> R.string.fix_source_folder
    BatchEntity.SOURCE_SELECTION -> R.string.fix_source_selection
    BatchEntity.SOURCE_MISSING_INFO -> R.string.fix_source_missing_info
    else -> error("Unknown batch source: $source")
}

@Composable
private fun statusColor(status: String) = when (status) {
    BatchEntity.STATUS_QUEUED -> MaterialTheme.colorScheme.outline
    BatchEntity.STATUS_PROCESSING -> MaterialTheme.colorScheme.primary
    BatchEntity.STATUS_PAUSED -> MaterialTheme.colorScheme.outlineVariant
    BatchEntity.STATUS_REVIEW -> MaterialTheme.colorScheme.tertiary
    BatchEntity.STATUS_DONE -> MaterialTheme.colorScheme.secondary
    BatchEntity.STATUS_CANCELLED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
