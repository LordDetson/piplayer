package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.LicenseList
import io.github.lorddetson.piplayer.settings.OpenSourceLicenses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LicensesTags {
    const val LIST = "licenses_list"
    const val TEXT = "license_text"
}

/** What the licences screen shows: nothing yet, the list, or that it cannot be read. */
private sealed interface Licenses {
    data object Reading : Licenses
    data class Read(val list: LicenseList) : Licenses
    data object Unreadable : Licenses
}

/**
 * The open-source licences (docs/library.md §14.3): the runtime modules, then the native components; a row opens its
 * licence through [onOpen] with a title, a copyright notice if any, and the asset paths of the texts.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpen: (title: String, notice: String?, texts: List<String>) -> Unit) {
    val context = LocalContext.current
    var licenses by remember { mutableStateOf<Licenses>(Licenses.Reading) }
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { OpenSourceLicenses.read(context) }
        licenses = if (list == null) Licenses.Unreadable else Licenses.Read(list)
    }
    SettingsScaffold(stringResource(R.string.settings_licenses), onBack) { padding ->
        when (val shown = licenses) {
            Licenses.Reading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            Licenses.Unreadable -> Text(
                text = stringResource(R.string.licenses_unreadable),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            is Licenses.Read -> {
                val kinds = remember(shown.list) { shown.list.kinds.associateBy { it.id } }
                val openLabel = stringResource(R.string.licenses_open)
                LazyColumn(Modifier.padding(padding).fillMaxSize().testTag(LicensesTags.LIST)) {
                    items(shown.list.libraries, key = { "${it.group}:${it.name}" }) { library ->
                        val name = "${library.group}:${library.name}"
                        val titles = library.licenses.map { kinds[it]?.title ?: it }
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text("${library.version} · ${titles.joinToString()}") },
                            modifier = Modifier.clickable(onClickLabel = openLabel, role = Role.Button) {
                                onOpen(name, library.notice, library.licenses.mapNotNull { kinds[it]?.text })
                            },
                        )
                    }
                    item(key = "native") {
                        HorizontalDivider()
                        SectionTitle(stringResource(R.string.licenses_native))
                    }
                    items(shown.list.native, key = { "native " + it.name }) { native ->
                        ListItem(
                            headlineContent = { Text(native.name) },
                            supportingContent = { Text("${native.version} · ${native.license}") },
                            modifier = Modifier.clickable(onClickLabel = openLabel, role = Role.Button) {
                                onOpen(native.name, null, native.texts)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A licence (docs/library.md §14.3): the [notice] when there is one, then each text of [texts] (asset paths). */
@Composable
fun LicenseTextScreen(title: String, notice: String?, texts: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    var read by remember(texts) { mutableStateOf<List<String?>?>(null) }
    LaunchedEffect(texts) {
        read = withContext(Dispatchers.IO) { texts.map { OpenSourceLicenses.text(context, it) } }
    }
    SettingsScaffold(title, onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(LicensesTags.TEXT),
        ) {
            notice?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 16.dp)) }
            read?.forEach { text ->
                Text(
                    text = text ?: stringResource(R.string.licenses_unreadable),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}
