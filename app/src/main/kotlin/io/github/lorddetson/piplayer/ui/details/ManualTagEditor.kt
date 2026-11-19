package io.github.lorddetson.piplayer.ui.details

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.tagging.write.BatchTagWriter
import io.github.lorddetson.piplayer.tagging.write.ManualTagPlan
import io.github.lorddetson.piplayer.tagging.write.WriteBackResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class EditorState(val plan: ManualTagPlan? = null, val busy: Boolean = true, val message: Int? = null)

private data class EditorSubmission(
    val plan: ManualTagPlan,
    val changes: Map<String, List<String>>,
    val fillEmptyOnly: Set<String>,
)

private class ManualTagEditorModel(private val fileId: Long, private val writer: BatchTagWriter) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorState())
    val state = mutableState.asStateFlow()
    private var awaitingConsent: EditorSubmission? = null

    init {
        viewModelScope.launch { reload() }
    }

    fun submit(
        changes: Map<String, List<String>>,
        fillEmptyOnly: Set<String>,
        launch: (IntentSenderRequest) -> Unit,
    ) {
        val current = mutableState.value
        val plan = current.plan ?: return
        if (current.busy || changes.isEmpty()) return
        mutableState.value = current.copy(busy = true, message = null)
        val submission = EditorSubmission(plan, changes, fillEmptyOnly)
        viewModelScope.launch {
            try {
                val sender = withContext(Dispatchers.IO) { writer.request(plan) }
                if (sender == null) {
                    apply(submission)
                } else {
                    awaitingConsent = submission
                    launch(IntentSenderRequest.Builder(sender).build())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                awaitingConsent = null
                Log.w(TAG, "Could not request permission to edit file tags", error)
                finish(R.string.manual_tag_failed)
            }
        }
    }

    fun consentResult(granted: Boolean) {
        val submission = awaitingConsent ?: return
        awaitingConsent = null
        if (granted) viewModelScope.launch { apply(submission) } else finish(R.string.manual_tag_cancelled)
    }

    private suspend fun apply(submission: EditorSubmission) {
        try {
            when (writer.applyManual(submission.plan, submission.changes, submission.fillEmptyOnly)) {
                WriteBackResult.VERIFIED -> reload(R.string.manual_tag_written)
                else -> finish(R.string.manual_tag_failed)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not write manually edited file tags", error)
            finish(R.string.manual_tag_failed)
        }
    }

    private suspend fun reload(message: Int? = null) {
        val plan = try {
            writer.manualPlan(fileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not read current file tags", error)
            null
        }
        mutableState.value = EditorState(plan, busy = false, message = message ?: R.string.manual_tag_unavailable.takeIf { plan == null })
    }

    private fun finish(message: Int) {
        mutableState.value = mutableState.value.copy(busy = false, message = message)
    }

    private companion object {
        const val TAG = "ManualTagEditor"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualTagEditorScreen(fileId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PiPlayerApplication
    val model = viewModel(key = "manual-tag-editor-$fileId") { ManualTagEditorModel(fileId, app.batchTagWriter) }
    val state by model.state.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.consentResult(result.resultCode == Activity.RESULT_OK)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.manual_tag_open)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val shown = state
            if (shown.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            shown.message?.let { Text(stringResource(it), Modifier.padding(16.dp)) }
            shown.plan?.let { plan ->
                ManualTagForm(plan.properties, modifier = Modifier.weight(1f), busy = shown.busy) { changes, fillEmptyOnly ->
                    model.submit(changes, fillEmptyOnly) { consent.launch(it) }
                }
            }
        }
    }
}

private data class ManualField(val key: String, val label: Int? = null, val multiple: Boolean = false)

private val mainFields = listOf(
    ManualField("TITLE", R.string.details_field_title),
    ManualField("ARTIST", R.string.details_field_artist, multiple = true),
    ManualField("ALBUM", R.string.details_field_album),
    ManualField("ALBUMARTIST", R.string.details_field_album_artist, multiple = true),
    ManualField("DATE", R.string.details_field_date),
    ManualField("TRACKNUMBER", R.string.details_field_track),
    ManualField("DISCNUMBER", R.string.details_field_disc),
    ManualField("COMPOSER", R.string.details_field_composer, multiple = true),
    ManualField("GENRE", R.string.details_field_genre, multiple = true),
    ManualField("COMMENT", R.string.manual_tag_comment),
    ManualField("LYRICS", R.string.manual_tag_lyrics),
)
private val keepExisting = setOf("GENRE", "COMMENT")

@Composable
internal fun ManualTagForm(
    initial: Map<String, List<String>>,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onSubmit: (Map<String, List<String>>, Set<String>) -> Unit,
) {
    val fields = remember(initial) {
        val known = mainFields.mapTo(HashSet()) { it.key }
        mainFields + initial.keys.filter { it !in known }.sorted().map { key -> ManualField(key, multiple = initial.getValue(key).size > 1) }
    }
    val edits = remember(initial) { mutableStateMapOf<String, String>() }
    var fillOnly by remember(initial) { mutableStateOf(keepExisting) }
    val canWrite = edits.any { (key, value) ->
        value != initial[key].orEmpty().joinToString("\n") && (key !in fillOnly || initial[key].orEmpty().all(String::isEmpty))
    }
    Column(modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(fields, key = { it.key }) { field ->
                val key = field.key
                val label = field.label?.let { stringResource(it) } ?: key
                val before = initial[key].orEmpty().joinToString("\n")
                Column {
                    OutlinedTextField(
                        value = edits[key] ?: before,
                        onValueChange = { edits[key] = it },
                        label = { Text(label) },
                        enabled = !busy,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().testTag("manual-tag-$key"),
                    )
                    if (key in keepExisting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in fillOnly, onCheckedChange = { checked ->
                                fillOnly = if (checked) fillOnly + key else fillOnly - key
                            }, enabled = !busy)
                            Text(stringResource(R.string.manual_tag_fill_empty, label))
                        }
                    }
                }
            }
        }
        Button(enabled = canWrite && !busy, onClick = {
            val changes = buildMap {
                for (field in fields) {
                    val text = edits[field.key] ?: continue
                    if (text == initial[field.key].orEmpty().joinToString("\n")) continue
                    val values = if (field.multiple) text.lines().filter(String::isNotEmpty) else listOfNotNull(text.takeIf(String::isNotEmpty))
                    put(field.key, values)
                }
            }
            onSubmit(changes, fillOnly)
        }) { Text(stringResource(R.string.manual_tag_write)) }
    }
}
