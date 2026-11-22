package io.github.lorddetson.piplayer.ui.customization

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.UI_ICON_MAX_PAYLOAD
import io.github.lorddetson.piplayer.settings.UiControlMotion
import io.github.lorddetson.piplayer.settings.UiControlStyle
import io.github.lorddetson.piplayer.settings.UiIconAsset
import io.github.lorddetson.piplayer.settings.UiIconFormat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal val LocalUiControlStyle = staticCompositionLocalOf { UiControlStyle() }

@Composable
internal fun UiControlColor(default: Color): Color =
    LocalUiControlStyle.current.foreground?.opaqueColor() ?: default

@Composable
internal fun UiControlIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val style = LocalUiControlStyle.current
    val foreground = style.foreground?.opaqueColor()
    val vectorColor = if (style.icon?.format == UiIconFormat.SVG) foreground ?: tint else Color.Unspecified
    val icon = remember(style.icon, vectorColor) { style.icon?.asPainter(vectorColor) }
    Icon(
        painter = icon ?: painter,
        contentDescription = contentDescription,
        modifier = if (icon == null) modifier else modifier.size(24.dp),
        tint = foreground ?: if (icon == null) tint else Color.Unspecified,
    )
}

private fun Int.opaqueColor(): Color = Color(this or 0xff000000.toInt())

/** Observes a pointer without consuming the tap or changing the control's action. */
@Composable
internal fun Modifier.uiControlMotion(motion: UiControlMotion): Modifier {
    if (motion == UiControlMotion.NONE) return this
    var pressed by remember(motion) { mutableStateOf(false) }
    return pointerInput(motion) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pressed = true
            try {
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
            } finally {
                pressed = false
            }
        }
    }.uiMotionTransform(motion, pressed)
}

@Composable
internal fun UiControlMotionPreview(motion: UiControlMotion) {
    var replay by remember { mutableIntStateOf(0) }
    var pressed by remember(motion) { mutableStateOf(false) }
    LaunchedEffect(motion, replay) {
        if (motion != UiControlMotion.NONE) {
            pressed = true
            delay(150)
            pressed = false
        }
    }
    FilledTonalIconButton(onClick = { replay++ }, modifier = Modifier.uiMotionTransform(motion, pressed)) {
        UiControlIcon(painterResource(R.drawable.ic_player_play), stringResource(R.string.ui_edit_motion_preview))
    }
}

private val motionPress = tween<Float>(durationMillis = 95)
private val motionRelease = tween<Float>(durationMillis = 155)
private val motionBounce = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

@Composable
private fun Modifier.uiMotionTransform(motion: UiControlMotion, pressed: Boolean): Modifier {
    if (motion == UiControlMotion.NONE) return this
    val pressedScale = when (motion) {
        UiControlMotion.NONE -> 1f
        UiControlMotion.PRESS -> 0.94f
        UiControlMotion.BOUNCE -> 0.9f
        UiControlMotion.TILT -> 0.97f
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = when {
            pressed -> motionPress
            motion == UiControlMotion.BOUNCE -> motionBounce
            else -> motionRelease
        },
        label = "Control press scale",
    )
    val angle by animateFloatAsState(
        targetValue = if (pressed && motion == UiControlMotion.TILT) 5f else 0f,
        animationSpec = if (pressed) motionPress else motionRelease,
        label = "Control press tilt",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        rotationZ = angle
    }
}

internal suspend fun readUiIcon(context: Context, uri: Uri): UiIconAsset? = withContext(Dispatchers.IO) {
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded() } ?: return@withContext null
        val rasterFormat = bytes.rasterIconFormat()
        if (rasterFormat == null) readSvgIcon(bytes) else normalizeBitmap(bytes, rasterFormat)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private const val MAX_SOURCE_BYTES = 1024 * 1024
private const val MAX_SOURCE_EDGE = 4096
private const val ICON_EDGE = 128
private const val MIN_REDUCED_EDGE = 24

private fun InputStream.readBounded(): ByteArray? {
    val buffer = ByteArray(MAX_SOURCE_BYTES + 1)
    var length = 0
    while (length < buffer.size) {
        val count = read(buffer, length, buffer.size - length)
        if (count < 0) return buffer.copyOf(length)
        if (count == 0) {
            val value = read()
            if (value < 0) return buffer.copyOf(length)
            buffer[length++] = value.toByte()
        } else length += count
    }
    return null
}

private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

private fun ByteArray.rasterIconFormat(): UiIconFormat? = when {
    size >= pngSignature.size && pngSignature.indices.all { this[it] == pngSignature[it] } -> UiIconFormat.PNG
    size >= 12 && String(this, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(this, 8, 4, Charsets.US_ASCII) == "WEBP" -> UiIconFormat.WEBP
    else -> null
}

private fun readSvgIcon(bytes: ByteArray): UiIconAsset? {
    if (bytes.size > SVG_ICON_MAX_BYTES) return null
    val source = bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF").trim()
    val asset = UiIconAsset(UiIconFormat.SVG, source)
    return asset.takeIf { it.isValid && svgIconPainter(source) != null }
}

private fun normalizeBitmap(bytes: ByteArray, format: UiIconFormat): UiIconAsset? {
    val image = decodeBitmap(bytes, MAX_SOURCE_EDGE, sample = true) ?: return null
    val compression = if (format == UiIconFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.WEBP_LOSSLESS
    var edge = minOf(ICON_EDGE, maxOf(image.width, image.height))
    return try {
        do {
            val scaled = image.scaleToEdge(edge)
            val payload = try {
                val output = ByteArrayOutputStream()
                if (!scaled.compress(compression, 100, output)) return null
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            } finally {
                if (scaled !== image) scaled.recycle()
            }
            if (payload.length <= UI_ICON_MAX_PAYLOAD) return UiIconAsset(format, payload)
            edge = edge * 3 / 4
        } while (edge >= MIN_REDUCED_EDGE)
        null
    } finally {
        image.recycle()
    }
}

private fun Bitmap.scaleToEdge(edge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest == edge) return this
    val factor = edge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this, (width * factor).roundToInt().coerceAtLeast(1), (height * factor).roundToInt().coerceAtLeast(1), true,
    )
}

private fun decodeBitmap(bytes: ByteArray, maximumEdge: Int, sample: Boolean = false): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..maximumEdge || bounds.outHeight !in 1..maximumEdge) return null
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        if (sample) inSampleSize = maxOf(1, Integer.highestOneBit(largest / ICON_EDGE))
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun UiIconAsset.asPainter(vectorColor: Color): Painter? {
    if (!isValid) return null
    if (format == UiIconFormat.SVG) return svgIconPainter(payload, vectorColor.takeIf { it != Color.Unspecified } ?: Color.Black)
    val bytes = try {
        Base64.decode(payload, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bytes.rasterIconFormat() != format) return null
    return decodeBitmap(bytes, ICON_EDGE)?.let { BitmapPainter(it.asImageBitmap()) }
}
