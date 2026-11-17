package io.github.lorddetson.piplayer.ui.fix

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.BatchEntity
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Test tags of the Fix tab (TI §8). */
object FixTags {
    /** The "Needs attention" entry of the review queue; the per-album cards arrive in the next step. */
    const val NEEDS_ATTENTION = "fix_needs_attention"

    /** The counts of the batches by status. */
    const val COUNTERS = "fix_counters"

    /** The queue of the batches. */
    const val LIST = "fix_batches"

    /** The ETA placeholder of a processing batch. */
    const val ETA = "fix_eta"
}

/**
 * The batches of the Fix tab (TI §8), read again on every start: nothing writes `batch` yet, and a one-shot read through
 * `Library.read` can never read a database that a rebuild closed.
 */
class FixViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableBatches = MutableStateFlow<List<BatchEntity>?>(null)

    /** The batches, oldest first; null until the first read. */
    val batches: StateFlow<List<BatchEntity>?> = mutableBatches.asStateFlow()

    fun onStart() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { Library.read(app) { database -> runBlocking { database.batches().all() } } }
            mutableBatches.value = read
        }
    }
}

/**
 * The Fix tab (TI §8, PIM-18): "Needs attention" (the review queue; the per-album review cards arrive in the next step),
 * the counts of the batches by status, then the batches oldest first, each with its source, its start and its status. A
 * processing batch also shows the placeholder of the ETA, which M4a does not measure yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val model = viewModel { FixViewModel(app) }
    LifecycleStartEffect(model) {
        model.onStart()
        onStopOrDispose { }
    }
    val batches by model.batches.collectAsStateWithLifecycle()
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
                else -> BatchQueue(batches.orEmpty())
            }
        }
    }
}

/** The review queue's entry (TI §8): the number of batches waiting for the cards of the next step. */
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
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

/** The batches oldest first, as the workers pick them up (TI §8). */
@Composable
private fun BatchQueue(batches: List<BatchEntity>) {
    if (batches.isEmpty()) {
        Text(
            stringResource(R.string.fix_batches_none),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp).testTag(FixTags.LIST),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag(FixTags.LIST)) {
        items(batches, key = { it.uuid }) { batch -> BatchRow(batch) }
    }
}

/** One batch: a dot of its status, its source, its start (and the ETA placeholder while processing), and its status. */
@Composable
private fun BatchRow(batch: BatchEntity) {
    val started = stringResource(R.string.fix_started, startedText(batch.createdAt))
    val eta = if (batch.status == BatchEntity.STATUS_PROCESSING) stringResource(R.string.fix_eta_not_measured) else null
    ListItem(
        headlineContent = { Text(stringResource(sourceLabel(batch.source)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(started, eta).joinToString(" · "), modifier = Modifier.testTag(FixTags.ETA))
        },
        leadingContent = { Box(Modifier.size(12.dp).background(statusColor(batch.status), CircleShape)) },
        trailingContent = {
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
        },
    )
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
    BatchEntity.STATUS_REVIEW,
    BatchEntity.STATUS_DONE,
    BatchEntity.STATUS_CANCELLED,
)

private fun statusLabel(status: String): Int = when (status) {
    BatchEntity.STATUS_QUEUED -> R.string.batch_status_queued
    BatchEntity.STATUS_PROCESSING -> R.string.batch_status_processing
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
