package io.github.lorddetson.piplayer.ui.songs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R

/** The library's first sync is under way (docs/library.md §14.1). */
@Composable
internal fun Scanning(modifier: Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize().padding(24.dp).testTag(SongsTags.SCANNING),
    ) {
        Text(stringResource(R.string.library_scanning), style = MaterialTheme.typography.bodyLarge)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** The library is ready and has no music: where PiPlayer looks, and the way to its settings (docs/library.md §14.1). */
@Composable
internal fun NoMusic(onOpenSettings: () -> Unit, modifier: Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.library_no_music),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.library_no_music_explanation), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.library_no_music_settings)) }
    }
}
