package io.github.lorddetson.piplayer.ui.player

import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.lorddetson.piplayer.R

/** A favorite as Now Playing and the album page show it (docs/library.md §15.2): [checked] and what a tap does. */
class FavoriteState(val checked: Boolean, val onToggle: () -> Unit)

/** The favorite toggle: a heart labelled "Favorite" that states on or off (docs/playback.md §3.8). */
@Composable
fun FavoriteButton(state: FavoriteState, modifier: Modifier = Modifier) {
    IconToggleButton(checked = state.checked, onCheckedChange = { state.onToggle() }, modifier = modifier) {
        Icon(
            painterResource(if (state.checked) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
            stringResource(R.string.favorite),
            tint = if (state.checked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}
