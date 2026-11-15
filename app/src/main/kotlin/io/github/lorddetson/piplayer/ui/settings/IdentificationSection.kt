package io.github.lorddetson.piplayer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.identification.DisclosureDialog
import io.github.lorddetson.piplayer.ui.identification.PolicyLinks
import io.github.lorddetson.piplayer.ui.identification.ServicesDisclosed

/** Whether a provider can be asked in this session, as Settings shows it (TI §9). */
sealed interface ProviderState {
    data object Ready : ProviderState

    /** Stopped until the process ends (TI §8). */
    data class Stopped(val reason: StopReason) : ProviderState

    /** Not in this build: it has no AcoustID key (TI §9). */
    data object NoKey : ProviderState
}

/** What Settings, "Identification and privacy", shows. */
data class IdentificationPrivacyState(
    /** Online lookups are on for this build: the setting is on and the current disclosure accepted. */
    val lookupsOn: Boolean = false,
    /** The current disclosure has not been answered yet. */
    val needsDisclosure: Boolean = true,
    val protectedFields: Set<ProtectedField> = emptySet(),
    val acoustId: ProviderState = ProviderState.Ready,
    val musicBrainz: ProviderState = ProviderState.Ready,
    val coverArtArchive: ProviderState = ProviderState.Ready,
    /**
     * Grows each time the answers to the setting have all ended, stored or not; the switch then shows [lookupsOn]
     * again, also when a failed answer left it unchanged.
     */
    val answersSettled: Int = 0,
)

/**
 * Settings, "Identification and privacy" (TI §9): the online-lookup setting, which opens the disclosure first while
 * its current version is unanswered; what each service receives, with links to the terms; the protected fields; and
 * each provider's status for this session.
 */
@Composable
internal fun IdentificationSection(
    state: IdentificationPrivacyState,
    onEnable: () -> Unit,
    onNotNow: () -> Unit,
    onTurnOff: () -> Unit,
    onProtect: (ProtectedField, Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.identification_settings_section),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
    var disclosing by rememberSaveable { mutableStateOf(false) }
    // The last answer shows until the gate changes or the answers have ended, so that a second tap before the first is
    // stored reverses it instead of repeating it, and an answer that was not stored does not stay on the switch.
    var shown by remember(state.lookupsOn, state.answersSettled) { mutableStateOf(state.lookupsOn) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = shown, role = Role.Switch) { on ->
                when {
                    !on -> {
                        shown = false
                        onTurnOff()
                    }
                    state.needsDisclosure -> disclosing = true
                    else -> {
                        shown = true
                        onEnable()
                    }
                }
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.identification_online_lookups), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.identification_online_lookups_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = shown, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
    if (disclosing) {
        DisclosureDialog(
            onEnable = {
                disclosing = false
                shown = true
                onEnable()
            },
            onNotNow = {
                disclosing = false
                onNotNow()
            },
            onDismiss = { disclosing = false },
        )
    }
    Text(
        stringResource(R.string.identification_what_is_sent),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
    )
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        ServicesDisclosed()
        PolicyLinks()
    }
    ProtectedFieldsChoice(state.protectedFields, onProtect)
    Text(
        stringResource(R.string.identification_status_section),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
    ProviderStatusLine(stringResource(R.string.identification_name_acoustid), state.acoustId)
    ProviderStatusLine(stringResource(R.string.identification_name_musicbrainz), state.musicBrainz)
    ProviderStatusLine(stringResource(R.string.identification_name_caa), state.coverArtArchive)
}

@Composable
private fun ProviderStatusLine(name: String, state: ProviderState) {
    val text = when (state) {
        ProviderState.Ready -> stringResource(R.string.identification_status_ready, name)
        ProviderState.NoKey -> stringResource(R.string.identification_status_no_key, name)
        is ProviderState.Stopped -> when (state.reason) {
            StopReason.USER_AGENT_REJECTED -> stringResource(R.string.identification_status_stopped_user_agent, name)
            StopReason.INVALID_API_KEY -> stringResource(R.string.identification_status_stopped_key, name)
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state == ProviderState.Ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

/** The protected fields: a row with the current list, and a dialog of checkboxes that changes it at a tap. */
@Composable
private fun ProtectedFieldsChoice(protected: Set<ProtectedField>, onProtect: (ProtectedField, Boolean) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val summary = if (protected.isEmpty()) {
        stringResource(R.string.identification_protected_fields_none)
    } else {
        ProtectedField.entries.filter { it in protected }.map { stringResource(protectedFieldLabel(it)) }.joinToString(", ")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { choosing = true }
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.identification_protected_fields), style = MaterialTheme.typography.bodyLarge)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!choosing) return
    // As with the switch: the last taps show until the stored list changes.
    var shown by remember(protected) { mutableStateOf(protected) }
    AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.identification_protected_fields)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.identification_protected_fields_explained),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (field in ProtectedField.entries) {
                    val checked = field in shown
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) { protect ->
                                shown = if (protect) shown + field else shown - field
                                onProtect(field, protect)
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(
                            stringResource(protectedFieldLabel(field)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.identification_done)) }
        },
    )
}

/** The label of [field] in the settings. */
@StringRes
internal fun protectedFieldLabel(field: ProtectedField): Int = when (field) {
    ProtectedField.TITLE -> R.string.identification_field_title
    ProtectedField.ARTIST -> R.string.identification_field_artist
    ProtectedField.ALBUM -> R.string.identification_field_album
    ProtectedField.ALBUM_ARTIST -> R.string.identification_field_album_artist
    ProtectedField.DATE -> R.string.identification_field_date
    ProtectedField.ORIGINAL_DATE -> R.string.identification_field_original_date
    ProtectedField.TRACK_NUMBER -> R.string.identification_field_track_number
    ProtectedField.DISC_NUMBER -> R.string.identification_field_disc_number
    ProtectedField.DISC_SUBTITLE -> R.string.identification_field_disc_subtitle
    ProtectedField.COMPILATION -> R.string.identification_field_compilation
    ProtectedField.LABEL -> R.string.identification_field_label
    ProtectedField.CATALOG_NUMBER -> R.string.identification_field_catalog_number
    ProtectedField.BARCODE -> R.string.identification_field_barcode
    ProtectedField.ISRC -> R.string.identification_field_isrc
    ProtectedField.MUSICBRAINZ_IDS -> R.string.identification_field_musicbrainz_ids
    ProtectedField.COVER -> R.string.identification_field_cover
}
