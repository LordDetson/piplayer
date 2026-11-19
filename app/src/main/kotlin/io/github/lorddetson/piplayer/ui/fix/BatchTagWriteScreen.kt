package io.github.lorddetson.piplayer.ui.fix

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import io.github.lorddetson.piplayer.tagging.write.TagApplyGroup
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface BatchWriteState {
    data object Loading : BatchWriteState
    data object Error : BatchWriteState

    data class Ready(
        val group: TagApplyGroup,
        val results: Map<String, Int>,
        val busy: Boolean = false,
        val message: Int? = null,
    ) : BatchWriteState
}

private class BatchTagWriteModel(private val batch: String, private val writer: BatchTagWriter) : ViewModel() {
    private val mutableState = MutableStateFlow<BatchWriteState>(BatchWriteState.Loading)
    val state = mutableState.asStateFlow()
    private var awaitingConsent: BatchWriteState.Ready? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { read() }
    }

    fun requestConsent(launch: (IntentSenderRequest) -> Unit) {
        val shown = mutableState.value as? BatchWriteState.Ready ?: return
        if (shown.busy || shown.group.files.isEmpty()) return
        mutableState.value = shown.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                val sender = withContext(Dispatchers.IO) { writer.request(shown.group) }
                if (sender == null) {
                    apply(shown)
                } else {
                    awaitingConsent = shown
                    launch(IntentSenderRequest.Builder(sender).build())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                awaitingConsent = null
                Log.w(TAG, "Could not request permission to write tags", error)
                mutableState.value = shown.copy(message = R.string.batch_tag_write_error)
            }
        }
    }

    fun consentResult(granted: Boolean) {
        val shown = awaitingConsent ?: return
        awaitingConsent = null
        if (granted) {
            viewModelScope.launch { apply(shown) }
        } else {
            mutableState.value = shown.copy(message = R.string.batch_tag_write_cancelled)
        }
    }

    private suspend fun apply(shown: BatchWriteState.Ready) {
        try {
            writer.apply(shown.group)
            read()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not write tags", error)
            read(R.string.batch_tag_write_error)
        }
    }

    private suspend fun read(message: Int? = null) {
        mutableState.value = try {
            BatchWriteState.Ready(writer.nextGroup(batch), writer.results(batch), message = message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the files to write", error)
            BatchWriteState.Error
        }
    }

    private companion object {
        const val TAG = "BatchTagWrite"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchTagWriteScreen(batchUuid: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PiPlayerApplication
    val model = viewModel(key = "batch-tag-write-$batchUuid") { BatchTagWriteModel(batchUuid, app.batchTagWriter) }
    val state by model.state.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.consentResult(result.resultCode == Activity.RESULT_OK)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.batch_tag_write_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
            },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val shown = state) {
                BatchWriteState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                BatchWriteState.Error -> {
                    Text(stringResource(R.string.batch_tag_write_error))
                    Button(onClick = model::refresh) { Text(stringResource(R.string.review_try_again)) }
                }
                is BatchWriteState.Ready -> {
                    if (shown.results.isEmpty()) Text(stringResource(R.string.batch_tag_write_empty))
                    else Text(stringResource(
                        R.string.batch_tag_write_counts, shown.results["written"] ?: 0, shown.results["queued"] ?: 0,
                        shown.results["skipped"] ?: 0, shown.results["failed"] ?: 0,
                    ))
                    shown.message?.let { Text(stringResource(it)) }
                    if (shown.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (shown.group.files.isNotEmpty()) {
                        Text(stringResource(R.string.batch_tag_write_explanation, shown.group.files.size))
                        Button(onClick = { model.requestConsent { consent.launch(it) } }) {
                            Text(stringResource(R.string.batch_tag_write_start))
                        }
                    } else if ((shown.results["queued"] ?: 0) > 0) {
                        Text(stringResource(R.string.batch_tag_write_empty))
                        Button(onClick = model::refresh) { Text(stringResource(R.string.review_try_again)) }
                    }
                }
            }
        }
    }
}
