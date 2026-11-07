package io.github.lorddetson.piplayer.ui.covers

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.ui.player.CoverImage

/**
 * The cover of [request] (docs/library.md §13.5), [size] wide, or as wide as it is laid out when [size] is null (a
 * tile). Loads off the main thread at that size and stops when it leaves the screen; until then, and without a cover, it
 * shows the placeholder colored by [placeholderName], or [fallback] when one is given.
 */
@Composable
fun LibraryCover(
    request: CoverRequest,
    placeholderName: String?,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    shape: Shape = RectangleShape,
    noteSize: Dp = 24.dp,
    fallback: (@Composable (Modifier) -> Unit)? = null,
) {
    val sized = if (size != null) modifier.size(size) else modifier
    if (size != null) {
        val px = with(LocalDensity.current) { size.roundToPx() }
        Cover(request, px, placeholderName, sized.clip(shape), noteSize, fallback)
    } else {
        BoxWithConstraints(sized.clip(shape)) {
            Cover(request, constraints.maxWidth, placeholderName, Modifier.fillMaxSize(), noteSize, fallback)
        }
    }
}

@Composable
private fun Cover(
    request: CoverRequest,
    px: Int,
    placeholderName: String?,
    modifier: Modifier,
    noteSize: Dp,
    fallback: (@Composable (Modifier) -> Unit)?,
) {
    val context = LocalContext.current
    val loader = remember(context) { Covers.loader(context) }
    val version by remember(context) { Covers.own(context).version }.collectAsState()
    // Another request in the same place starts from what memory holds for it, never from the one before; a change of own
    // covers keeps the picture on screen until the new one is loaded, so that covers do not blink.
    var image by remember(request, px) { mutableStateOf(loader.peek(request, px)?.asImageBitmap()) }
    LaunchedEffect(request, px, version) {
        image = loader.load(request, px)?.asImageBitmap()
    }
    val shown = image
    if (shown == null && fallback != null) fallback(modifier) else CoverImage(shown, modifier, noteSize, placeholderName)
}

/** What the menus do with own covers (docs/library.md §13.2): choose one from the photo picker, or remove it. */
class CoverActions(
    val chooseForSong: (Song) -> Unit,
    val removeForSong: (Song) -> Unit,
    val songHasOwn: suspend (Song) -> Boolean,
    val chooseForAlbum: (key: String) -> Unit,
    val removeForAlbum: (key: String) -> Unit,
    val albumHasOwn: suspend (key: String) -> Boolean,
)
