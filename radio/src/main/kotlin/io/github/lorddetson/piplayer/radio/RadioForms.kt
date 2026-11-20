package io.github.lorddetson.piplayer.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun RadioSearchForm(searching: Boolean, onSearch: (RadioStationQuery) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var invalidCountry by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun search() {
        if (searching) return
        val query = RadioSearchInput(name, country, language, genre).query()
        invalidCountry = query == null
        if (query == null) {
            showFilters = true
        } else {
            focus.clearFocus()
            onSearch(query)
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.radio_directory_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(120) },
            label = { Text(stringResource(R.string.radio_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier.fillMaxWidth().testTag("radio-query-name"),
        )
        TextButton(onClick = { showFilters = !showFilters }, modifier = Modifier.testTag("radio-filters")) {
            Text(stringResource(if (showFilters) R.string.radio_hide_filters else R.string.radio_filters))
        }
        if (showFilters) {
            OutlinedTextField(
                value = country,
                onValueChange = {
                    country = it.take(8)
                    invalidCountry = false
                },
                label = { Text(stringResource(R.string.radio_country)) },
                isError = invalidCountry,
                supportingText = { Text(stringResource(if (invalidCountry) R.string.radio_invalid_country else R.string.radio_country_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("radio-query-country"),
            )
            OutlinedTextField(
                value = language,
                onValueChange = { language = it.take(60) },
                label = { Text(stringResource(R.string.radio_language)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("radio-query-language"),
            )
            OutlinedTextField(
                value = genre,
                onValueChange = { genre = it.take(60) },
                label = { Text(stringResource(R.string.radio_genre)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().testTag("radio-query-genre"),
            )
        }
        Button(onClick = ::search, enabled = !searching, modifier = Modifier.testTag("radio-search")) {
            Text(stringResource(R.string.radio_search))
        }
    }
}

@Composable
internal fun RadioAddStationDialog(
    writing: Boolean,
    error: SavedStationWrite?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!writing) onDismiss() },
        title = { Text(stringResource(R.string.radio_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.take(4096) },
                    enabled = !writing,
                    label = { Text(stringResource(R.string.radio_stream_url)) },
                    isError = error == SavedStationWrite.INVALID_URL,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("radio-custom-url"),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    enabled = !writing,
                    label = { Text(stringResource(R.string.radio_optional_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("radio-custom-name"),
                )
                if (error != null) Text(stringResource(error.message), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, url) },
                enabled = !writing && url.isNotBlank(),
                modifier = Modifier.testTag("radio-save-custom"),
            ) { Text(stringResource(R.string.radio_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !writing) { Text(stringResource(R.string.radio_cancel)) }
        },
    )
}
