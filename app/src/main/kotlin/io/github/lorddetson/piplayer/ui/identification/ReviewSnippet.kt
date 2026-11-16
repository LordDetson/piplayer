package io.github.lorddetson.piplayer.ui.identification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.playback.snippet.SnippetEnd
import io.github.lorddetson.piplayer.playback.snippet.SnippetPlayer
import io.github.lorddetson.piplayer.playback.snippet.SnippetState
import kotlinx.coroutines.delay

/** How often the snippet's bar follows the player's position. */
private const val PROGRESS_MS = 200L

/**
 * The card's snippet button (TI §6, review screen B): plays the snippet of [source] through [player] (step 17), and while
 * one plays stops it and shows its progress over its span; a snippet that could not play, or was refused the focus, says
 * so.
 */
@Composable
internal fun SnippetButton(player: SnippetPlayer, source: SnippetSource) {
    val state by player.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().testTag(ReviewTags.SNIPPET), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val shown = state) {
            is SnippetState.Playing -> {
                OutlinedButton(onClick = player::stop) { Text(stringResource(R.string.review_stop_snippet)) }
                var progress by remember(shown) { mutableFloatStateOf(0f) }
                LaunchedEffect(shown) {
                    val span = (shown.endMs - shown.startMs).coerceAtLeast(1)
                    while (true) {
                        progress = ((player.positionMs - shown.startMs).toFloat() / span).coerceIn(0f, 1f)
                        delay(PROGRESS_MS)
                    }
                }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag(ReviewTags.SNIPPET_PROGRESS))
            }
            else -> {
                OutlinedButton(onClick = { player.play(source.uri.toUri(), source.durationMs) }) { Text(stringResource(R.string.review_play_snippet)) }
                val reason = (shown as? SnippetState.Ended)?.reason
                val note = when (reason) {
                    SnippetEnd.FAILED -> R.string.review_snippet_failed
                    SnippetEnd.FOCUS_REFUSED -> R.string.review_snippet_refused
                    else -> null
                }
                note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
