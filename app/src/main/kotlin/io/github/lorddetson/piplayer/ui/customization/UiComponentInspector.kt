package io.github.lorddetson.piplayer.ui.customization

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.UI_CANVAS_MAX_INSET
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasPadding
import io.github.lorddetson.piplayer.settings.UiControlMotion
import io.github.lorddetson.piplayer.settings.UiControlStyle
import io.github.lorddetson.piplayer.settings.UiTextAlignment
import kotlinx.coroutines.launch

@Composable
internal fun UiComponentInspector(
    item: UiCanvasItem,
    component: UiCanvasComponent,
    onDismiss: () -> Unit,
    onUpdate: (UiCanvasPadding, UiControlStyle) -> Unit,
) {
    var start by remember { mutableStateOf(item.padding.start.toString()) }
    var top by remember { mutableStateOf(item.padding.top.toString()) }
    var end by remember { mutableStateOf(item.padding.end.toString()) }
    var bottom by remember { mutableStateOf(item.padding.bottom.toString()) }
    var foreground by remember { mutableStateOf(item.style.foreground.hex()) }
    var background by remember { mutableStateOf(item.style.background.hex()) }
    var icon by remember { mutableStateOf(item.style.icon) }
    var motion by remember { mutableStateOf(item.style.motion) }
    var textAlignment by remember { mutableStateOf(item.style.textAlignment) }
    var importing by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            importFailed = false
            val imported = readUiIcon(context, uri)
            if (imported == null) importFailed = true else icon = imported
            importing = false
        }
    }
    val values = listOf(start, top, end, bottom).map(String::toIntOrNull)
    val validPadding = values.all { it != null && it in 0..UI_CANVAS_MAX_INSET }
    val validColors = foreground.validColor() && background.validColor()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(component.label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ui_edit_padding), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaddingInput(start, { start = it }, R.string.ui_edit_padding_start, Modifier.weight(1f))
                    PaddingInput(end, { end = it }, R.string.ui_edit_padding_end, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaddingInput(top, { top = it }, R.string.ui_edit_padding_top, Modifier.weight(1f))
                    PaddingInput(bottom, { bottom = it }, R.string.ui_edit_padding_bottom, Modifier.weight(1f))
                }
                ColorInput(foreground, { foreground = it }, R.string.ui_edit_foreground)
                ColorInput(background, { background = it }, R.string.ui_edit_background)
                if (component.alignableText) {
                    Text(stringResource(R.string.ui_edit_text_alignment), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        UiTextAlignment.entries.forEach { alignment ->
                            FilterChip(
                                selected = textAlignment == alignment,
                                onClick = { textAlignment = alignment },
                                label = { Text(stringResource(alignment.label)) },
                                modifier = Modifier.testTag("ui_edit_align_" + alignment.name.lowercase()),
                            )
                        }
                    }
                }
                if (component.customizableIcon) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { imagePicker.launch(arrayOf("image/png", "image/webp", "image/svg+xml")) },
                            enabled = !importing,
                            modifier = Modifier.testTag("ui_edit_choose_icon"),
                        ) { Text(stringResource(R.string.ui_edit_choose_icon)) }
                        if (icon != null) {
                            TextButton(onClick = { icon = null; importFailed = false }) {
                                Text(stringResource(R.string.ui_edit_reset_icon))
                            }
                        }
                    }
                    if (importFailed) Text(stringResource(R.string.ui_edit_invalid_icon), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.ui_edit_motion), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        UiControlMotion.entries.forEach { effect ->
                            FilterChip(
                                selected = motion == effect,
                                onClick = { motion = effect },
                                label = { Text(stringResource(effect.label)) },
                            )
                        }
                    }
                    val previewStyle = UiControlStyle(foreground.parseColor(), background.parseColor(), icon, motion)
                    CompositionLocalProvider(LocalUiControlStyle provides previewStyle) {
                        val tint = previewStyle.background?.let { Color(it or 0xff000000.toInt()) } ?: Color.Transparent
                        Box(Modifier.background(tint, CircleShape)) { UiControlMotionPreview(motion) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validPadding && validColors && !importing,
                onClick = {
                    onUpdate(
                        UiCanvasPadding(start.toInt(), top.toInt(), end.toInt(), bottom.toInt()),
                        UiControlStyle(foreground.parseColor(), background.parseColor(), icon, motion, textAlignment),
                    )
                },
                modifier = Modifier.testTag("ui_edit_apply_properties"),
            ) { Text(stringResource(R.string.ui_edit_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_edit_cancel)) } },
    )
}

@Composable
private fun PaddingInput(value: String, onValue: (String) -> Unit, label: Int, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) onValue(it) },
        label = { Text(stringResource(label)) },
        isError = value.toIntOrNull()?.let { it !in 0..UI_CANVAS_MAX_INSET } ?: true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ColorInput(value: String, onValue: (String) -> Unit, label: Int) {
    val scheme = MaterialTheme.colorScheme
    val presets = listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.surface, scheme.onSurface).distinct()
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 7) onValue(it) },
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(R.string.ui_edit_theme_color_hint)) },
        isError = !value.validColor(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onValue("") }) { Text(stringResource(R.string.ui_edit_theme_color)) }
        presets.forEach { color ->
            val hex = (color.toArgb() and 0xffffff).hex()
            Box(Modifier.size(46.dp).clickable { onValue(hex) }.semantics { contentDescription = hex },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(24.dp).background(color, CircleShape).border(1.dp, scheme.outline, CircleShape))
            }
        }
    }
}

private fun Int?.hex(): String = this?.toString(16)?.padStart(6, '0')?.let { "#" + it }.orEmpty()

private fun String.validColor() = isBlank() || parseColor() != null

private fun String.parseColor(): Int? = removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.takeIf { it >= 0 }

private val UiControlMotion.label: Int get() = when (this) {
    UiControlMotion.NONE -> R.string.ui_edit_motion_none
    UiControlMotion.PRESS -> R.string.ui_edit_motion_press
    UiControlMotion.BOUNCE -> R.string.ui_edit_motion_bounce
    UiControlMotion.TILT -> R.string.ui_edit_motion_tilt
}

private val UiTextAlignment.label: Int get() = when (this) {
    UiTextAlignment.DEFAULT -> R.string.ui_edit_align_default
    UiTextAlignment.START -> R.string.ui_edit_align_start
    UiTextAlignment.CENTER -> R.string.ui_edit_align_center
    UiTextAlignment.END -> R.string.ui_edit_align_end
    UiTextAlignment.JUSTIFY -> R.string.ui_edit_align_justify
}
