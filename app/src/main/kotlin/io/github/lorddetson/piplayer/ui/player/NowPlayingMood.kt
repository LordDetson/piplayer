package io.github.lorddetson.piplayer.ui.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.QueueIds
import io.github.lorddetson.piplayer.playback.mood.TrackMood
import io.github.lorddetson.piplayer.playback.mood.TrackMoodAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val moodCache by lazy { LruCache<Uri, TrackMood>(32) }
private const val MOOD_TRANSITION_MS = 650

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun rememberMoodBackdrop(item: MediaItem?, enabled: Boolean): Modifier {
    val colors = MaterialTheme.colorScheme
    val surface = colors.surface
    if (!enabled || item == null) return Modifier.background(surface)

    val context = LocalContext.current.applicationContext
    val mediaId = item.mediaId
    val configuredUri = item.localConfiguration?.uri
    var mood by remember(context, mediaId, configuredUri) { mutableStateOf<TrackMood?>(null) }
    LaunchedEffect(context, mediaId, configuredUri) {
        // Rapid navigation should decode only the track left on screen.
        delay(240)
        val uri = moodSource(context, mediaId, configuredUri) ?: return@LaunchedEffect
        mood = moodCache.get(uri) ?: TrackMoodAnalyzer.analyze(context, uri)?.also { moodCache.put(uri, it) }
    }

    val tint by animateColorAsState(
        targetValue = mood?.let { moodTint(surface, colors.onSurface, colors.onSurfaceVariant, it.energy, it.intensity) } ?: surface,
        animationSpec = tween(MOOD_TRANSITION_MS),
        label = "Track mood color",
    )
    val centerFraction by animateFloatAsState(
        targetValue = mood?.energy ?: 0.5f,
        animationSpec = tween(MOOD_TRANSITION_MS),
        label = "Track mood background position",
    )
    return Modifier.drawBehind {
        drawRect(surface)
        if (tint != surface && size.width > 0f && size.height > 0f) {
            drawRect(Brush.radialGradient(
                colors = listOf(tint, surface),
                center = Offset(size.width * (0.3f + 0.4f * centerFraction), size.height * 0.24f),
                radius = max(size.width, size.height) * 0.8f,
            ))
        }
    }
}

private suspend fun moodSource(context: Context, mediaId: String, configuredUri: Uri?): Uri? {
    if (configuredUri != null) return configuredUri.takeIf(Uri::isLocalAudio)
    val idUri = mediaId.toUri()
    if (idUri.scheme != null) return idUri.takeIf(Uri::isLocalAudio)
    // The controller can receive a MediaItem without its local configuration.
    val id = QueueIds.fileId(mediaId) ?: return null
    return withContext(Dispatchers.IO) {
        try {
            SongList.songsById(context, listOf(id))[id]?.uri?.toUri()?.takeIf(Uri::isLocalAudio)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

private fun Uri.isLocalAudio(): Boolean =
    scheme == ContentResolver.SCHEME_FILE || scheme == ContentResolver.SCHEME_CONTENT

internal fun moodTint(
    surface: Color,
    text: Color,
    secondaryText: Color,
    energy: Float,
    intensity: Float,
): Color {
    val accent = lerp(Color(0xFF537B9D), Color(0xFFBD674C), energy.coerceIn(0f, 1f))
    val textMinimum = min(4.5f, contrast(surface, text))
    val secondaryMinimum = min(4.5f, contrast(surface, secondaryText))
    var blend = 0.15f + intensity.coerceIn(0f, 1f) * 0.12f
    repeat(5) {
        val candidate = lerp(surface, accent, blend)
        if (contrast(candidate, text) >= textMinimum && contrast(candidate, secondaryText) >= secondaryMinimum) return candidate
        blend /= 2f
    }
    return surface
}

private fun contrast(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
}
