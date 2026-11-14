package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyScript

/**
 * The legacy tag encoding: a row with the choice, and a dialog that changes it at a tap; choosing Automatic again undoes
 * a wrong choice (docs/library.md §5.7).
 */
@Composable
internal fun LegacyEncodingChoice(
    selected: LegacyEncoding?,
    available: List<LegacyEncoding>,
    onChoose: (LegacyEncoding?) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { choosing = true }
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.settings_legacy_encoding), style = MaterialTheme.typography.bodyLarge)
        Text(
            legacyEncodingLabel(selected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!choosing) return
    fun choose(encoding: LegacyEncoding?) {
        choosing = false
        onChoose(encoding)
    }
    AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.settings_legacy_encoding)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.settings_legacy_encoding_explained),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Column(Modifier.selectableGroup()) {
                    Option(
                        stringResource(R.string.settings_legacy_encoding_automatic),
                        stringResource(R.string.settings_legacy_encoding_automatic_summary),
                        selected == null,
                    ) { choose(null) }
                    for (encoding in available) {
                        Option(legacyEncodingLabel(encoding), summary = null, encoding.name == selected?.name) { choose(encoding) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.settings_dialog_cancel)) }
        },
    )
}

@Composable
private fun Option(title: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Automatic", or the script with the code page's name, such as "Cyrillic (KOI8-R)". */
@Composable
private fun legacyEncodingLabel(encoding: LegacyEncoding?): String {
    if (encoding == null) return stringResource(R.string.settings_legacy_encoding_automatic)
    val script = when (encoding.script) {
        LegacyScript.CYRILLIC -> R.string.legacy_script_cyrillic
        LegacyScript.CENTRAL_EUROPEAN -> R.string.legacy_script_central_european
        LegacyScript.WESTERN -> R.string.legacy_script_western
        LegacyScript.GREEK -> R.string.legacy_script_greek
        LegacyScript.TURKISH -> R.string.legacy_script_turkish
        LegacyScript.HEBREW -> R.string.legacy_script_hebrew
        LegacyScript.ARABIC -> R.string.legacy_script_arabic
        LegacyScript.BALTIC -> R.string.legacy_script_baltic
        LegacyScript.VIETNAMESE -> R.string.legacy_script_vietnamese
        LegacyScript.THAI -> R.string.legacy_script_thai
        LegacyScript.JAPANESE -> R.string.legacy_script_japanese
        LegacyScript.SIMPLIFIED_CHINESE -> R.string.legacy_script_simplified_chinese
        LegacyScript.TRADITIONAL_CHINESE -> R.string.legacy_script_traditional_chinese
        LegacyScript.KOREAN -> R.string.legacy_script_korean
    }
    return stringResource(script, encoding.label)
}
