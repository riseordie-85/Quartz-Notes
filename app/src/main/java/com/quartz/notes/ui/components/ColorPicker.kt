package com.quartz.notes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FormatColorReset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.quartz.notes.ui.theme.parseHexColor
import com.quartz.notes.ui.theme.toHex

/** A round color swatch. */
@Composable
fun Swatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val ring = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, ring, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/** A swatch representing "no color / automatic". */
@Composable
fun DefaultSwatch(selected: Boolean, onClick: () -> Unit, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(if (selected) 2.5.dp else 1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 0.9f else 0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.FormatColorReset, contentDescription = "Default", modifier = Modifier.size(size * 0.5f))
    }
}

/** Saturation/value square plus hue bar. */
@Composable
fun HsvColorPicker(initial: Color, onColorChange: (Color) -> Unit, modifier: Modifier = Modifier) {
    val start = remember {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toArgb(), hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val emit by rememberUpdatedState(onColorChange)

    fun publish() = emit(Color.hsv(hue, sat, value))

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                .pointerInputSv(
                    onChange = { s, v ->
                        sat = s
                        value = v
                        publish()
                    },
                ),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val c = Offset(sat * size.width, (1f - value) * size.height)
                drawCircle(Color.White, radius = 11.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = 13.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
            }
        }
        Spacer(Modifier.height(14.dp))
        val hues = remember { (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) } }
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(hues))
                .pointerInputHue { h ->
                    hue = h
                    publish()
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val x = hue / 360f * size.width
                drawCircle(Color.White, radius = size.height / 2f - 2.dp.toPx(), center = Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2), style = Stroke(3.dp.toPx()))
            }
        }
    }
}

private fun Modifier.pointerInputSv(onChange: (Float, Float) -> Unit): Modifier =
    pointerTracking { area, offset ->
        val s = (offset.x / area.width).coerceIn(0f, 1f)
        val v = 1f - (offset.y / area.height).coerceIn(0f, 1f)
        onChange(s, v)
    }

private fun Modifier.pointerInputHue(onChange: (Float) -> Unit): Modifier =
    pointerTracking { area, offset -> onChange((offset.x / area.width).coerceIn(0f, 1f) * 359.9f) }

/** Reports taps and drags on this element with its size. */
private fun Modifier.pointerTracking(onPoint: (Size, Offset) -> Unit): Modifier = pointerInput(Unit) {
    val area = Size(size.width.toFloat(), size.height.toFloat())
    coroutineScope {
        launch { detectTapGestures { onPoint(area, it) } }
        launch {
            detectDragGestures(onDragStart = { onPoint(area, it) }) { change, _ ->
                change.consume()
                onPoint(area, change.position)
            }
        }
    }
}

/**
 * Dialog offering preset swatches, a full HSV picker and a hex field.
 * [onPick] receives null when the "default" swatch is chosen (only shown if [allowDefault]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color?,
    presets: List<Color>,
    onDismiss: () -> Unit,
    onPick: (Color?) -> Unit,
    allowDefault: Boolean = false,
) {
    var color by remember { mutableStateOf(initial ?: presets.firstOrNull() ?: Color.Black) }
    var useDefault by remember { mutableStateOf(initial == null && allowDefault) }
    var hex by remember { mutableStateOf(color.toHex().takeLast(6)) }
    var pickerKey by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (allowDefault) DefaultSwatch(selected = useDefault, onClick = { useDefault = true })
                    presets.forEach { p ->
                        Swatch(p, selected = !useDefault && p.toArgb() == color.toArgb(), onClick = {
                            color = p
                            useDefault = false
                            hex = p.toHex().takeLast(6)
                            pickerKey++
                        })
                    }
                }
                androidx.compose.runtime.key(pickerKey) {
                    HsvColorPicker(initial = color, onColorChange = {
                        color = it
                        useDefault = false
                        hex = it.toHex().takeLast(6)
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (useDefault) Color.Transparent else color)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { input ->
                            val clean = input.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                            hex = clean
                            parseHexColor(clean)?.let {
                                color = it
                                useDefault = false
                                if (clean.length == 6) pickerKey++
                            }
                        },
                        prefix = { Text("#") },
                        singleLine = true,
                        label = { Text("Hex") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(if (useDefault) null else color) }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
