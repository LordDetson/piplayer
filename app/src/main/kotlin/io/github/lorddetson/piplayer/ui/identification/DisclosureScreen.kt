package io.github.lorddetson.piplayer.ui.identification

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lorddetson.piplayer.R

/** Where the disclosure and the settings send the reader (TI §9; docs/THIRD_PARTY_SERVICES.md lists the same terms). */
internal object PrivacyLinks {
    private const val DOCS = "https://github.com/LordDetson/piplayer/blob/main/docs"
    const val PRIVACY_POLICY = "$DOCS/PRIVACY.md"
    const val THIRD_PARTY_SERVICES = "$DOCS/THIRD_PARTY_SERVICES.md"
    const val ACOUSTID_TERMS = "https://acoustid.org/webservice"
    const val MUSICBRAINZ_TERMS = "https://musicbrainz.org/doc/MusicBrainz_API"
    const val CAA_TERMS = "https://musicbrainz.org/doc/Cover_Art_Archive"
}

/**
 * The disclosure before the first online lookup, and again whenever its version rises (TI §9): every service with what
 * it receives, the background cover loads included, links to the policy and the terms, and the two answers. Enable
 * turns online lookups on; Not now keeps identification on the device.
 */
@Composable
fun DisclosureScreen(onEnable: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.identification_disclosure_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Paragraph(R.string.identification_disclosure_intro)
            ServicesDisclosed()
            Paragraph(R.string.identification_disclosure_local)
            PolicyLinks()
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onNotNow) { Text(stringResource(R.string.identification_not_now)) }
            Button(onClick = onEnable) { Text(stringResource(R.string.identification_enable)) }
        }
    }
}

/** [DisclosureScreen] over the whole window; leaving it without an answer ([onDismiss]) records nothing. */
@Composable
fun DisclosureDialog(onEnable: () -> Unit, onNotNow: () -> Unit, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            DisclosureScreen(onEnable, onNotNow, Modifier.safeDrawingPadding())
        }
    }
}

/** What each online service receives, with a link to its terms; the settings section shows the same list. */
@Composable
internal fun ServicesDisclosed() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Paragraph(R.string.identification_sends_acoustid)
        Link(R.string.identification_terms_acoustid, PrivacyLinks.ACOUSTID_TERMS)
        Paragraph(R.string.identification_sends_musicbrainz)
        Link(R.string.identification_terms_musicbrainz, PrivacyLinks.MUSICBRAINZ_TERMS)
        Paragraph(R.string.identification_sends_caa)
        Link(R.string.identification_terms_caa, PrivacyLinks.CAA_TERMS)
        Paragraph(R.string.identification_sends_all)
    }
}

/** The privacy policy and the list of third-party services. */
@Composable
internal fun PolicyLinks() {
    Column {
        Link(R.string.identification_privacy_policy, PrivacyLinks.PRIVACY_POLICY)
        Link(R.string.identification_third_party_services, PrivacyLinks.THIRD_PARTY_SERVICES)
    }
}

@Composable
private fun Paragraph(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
}

/** A button that opens [url] in the browser; its text is its label for TalkBack. */
@Composable
private fun Link(@StringRes label: Int, url: String) {
    val uriHandler = LocalUriHandler.current
    // A device without a browser has nothing to open the link with; the button then does nothing.
    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text(stringResource(label)) }
}
