package com.quartz.notes.ui.notebook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.quartz.notes.ui.components.QuartzSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.model.ArrowData
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.LineRoute
import com.quartz.notes.data.model.ShapeData
import com.quartz.notes.data.model.ShapeType
import com.quartz.notes.ui.components.ColorPickerDialog
import com.quartz.notes.ui.components.DefaultSwatch
import com.quartz.notes.ui.components.Swatch
import com.quartz.notes.ui.theme.InkPresets
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.NotebookColorPresets

/** Fill colors offered for shapes (the mockup's blue and pink first). */
val ShapeFillPresets = listOf(
    Color(0xFF00A8D6), Color(0xFFD6004C), Color(0xFFFFD600), Color(0xFF2E9E5B),
    Color(0xFFE68A1E), Color(0xFF6A3FD0), Color(0xFFFFFFFF), Color(0xFF212121),
)

/** Shape type, fill, outline and outline thickness. Changes apply live. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShapeStyleSheet(data: ShapeData, ink: Color, onChange: (ShapeData) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf<String?>(null) }
    QuartzSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Shape", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            SheetLabel("Type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShapeType.entries.forEach { t ->
                    val selected = t == data.shape
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(if (selected) 1.5.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { onChange(data.copy(shape = t)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val outline = MaterialTheme.colorScheme.onSurface
                        Canvas(Modifier.size(34.dp)) {
                            val path = shapePath(t, size)
                            drawPath(path, outline, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
            SheetLabel("Fill")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DefaultSwatch(selected = data.fill == null, onClick = { onChange(data.copy(fill = null)) })
                ShapeFillPresets.forEach { c ->
                    Swatch(c, selected = data.fill == c.toArgb(), onClick = { onChange(data.copy(fill = c.toArgb())) })
                }
                CustomColorSwatch { custom = "fill" }
            }
            Text("The crossed-out swatch means no fill.", fontSize = 12.sp, color = LocalQuartzColors.current.textSecondary, modifier = Modifier.padding(top = 6.dp))
            SheetLabel("Outline")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DefaultSwatch(selected = data.outline == 0, onClick = { onChange(data.copy(outline = 0)) })
                InkPresets.forEach { c ->
                    Swatch(c, selected = data.outline == c.toArgb(), onClick = { onChange(data.copy(outline = c.toArgb())) })
                }
                CustomColorSwatch { custom = "outline" }
            }
            SheetLabel("Outline thickness")
            ThicknessRow(level = data.outlineLevel, allowNone = true, color = inkOr(data.outline, ink)) { onChange(data.copy(outlineLevel = it)) }
        }
    }
    custom?.let { which ->
        ColorPickerDialog(
            title = if (which == "fill") "Fill color" else "Outline color",
            initial = (if (which == "fill") data.fill else data.outline.takeIf { it != 0 })?.let { Color(it) },
            presets = if (which == "fill") ShapeFillPresets else InkPresets,
            onDismiss = { custom = null },
            onPick = { c ->
                custom = null
                if (c != null) onChange(if (which == "fill") data.copy(fill = c.toArgb()) else data.copy(outline = c.toArgb()))
            },
        )
    }
}

/** Color, thickness and arrowheads for an arrow or a connector. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LineStyleSheet(
    arrow: ArrowData?,
    connector: ConnectorData?,
    ink: Color,
    onArrow: (ArrowData) -> Unit,
    onConnector: (ConnectorData) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    val color = arrow?.color ?: connector?.color ?: 0
    val level = arrow?.level ?: connector?.level ?: 3
    fun setColor(c: Int) {
        arrow?.let { onArrow(it.copy(color = c)) }
        connector?.let { onConnector(it.copy(color = c)) }
    }
    QuartzSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(if (arrow != null) "Arrow" else "Connector", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            if (connector != null) {
                SheetLabel("Route")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineRoute.entries.forEach { r ->
                        FilterChip(selected = connector.route == r, onClick = { onConnector(connector.copy(route = r)) }, label = { Text(r.label) })
                    }
                }
            }
            SheetLabel("Color")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DefaultSwatch(selected = color == 0, onClick = { setColor(0) })
                (InkPresets + NotebookColorPresets.take(4)).distinctBy { it.toArgb() }.forEach { c ->
                    Swatch(c, selected = color == c.toArgb(), onClick = { setColor(c.toArgb()) })
                }
                CustomColorSwatch { custom = true }
            }
            SheetLabel("Thickness")
            ThicknessRow(level = level, allowNone = false, color = inkOr(color, ink)) { l ->
                arrow?.let { onArrow(it.copy(level = l)) }
                connector?.let { onConnector(it.copy(level = l)) }
            }
            SheetLabel("Arrowheads")
            if (arrow != null) {
                ToggleRow("At the end", arrow.endHead) { onArrow(arrow.copy(endHead = it)) }
                ToggleRow("At the start", arrow.startHead) { onArrow(arrow.copy(startHead = it)) }
            }
            if (connector != null) {
                ToggleRow("Point at the second item", connector.endHead) { onConnector(connector.copy(endHead = it)) }
            }
        }
    }
    if (custom) {
        ColorPickerDialog(
            title = "Line color",
            initial = color.takeIf { it != 0 }?.let { Color(it) },
            presets = InkPresets,
            onDismiss = { custom = false },
            onPick = { c ->
                custom = false
                if (c != null) setColor(c.toArgb())
            },
        )
    }
}

/** Five (or six, with "none") line thickness choices drawn as lines. */
@Composable
fun ThicknessRow(level: Int, allowNone: Boolean, color: Color, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val levels = if (allowNone) (0..5) else (1..5)
        for (l in levels) {
            val selected = l == level
            Box(
                Modifier
                    .size(width = 48.dp, height = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(if (selected) 1.5.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onPick(l) },
                contentAlignment = Alignment.Center,
            ) {
                if (l == 0) {
                    Text("None", fontSize = 11.sp)
                } else {
                    Canvas(Modifier.size(width = 30.dp, height = 20.dp)) {
                        val w = lineWidthPx(l, 360.dp.toPx())
                        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomColorSwatch(onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalQuartzColors.current.textSecondary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** Brush options shown above the toolbar while drawing: five sizes, eraser, done. */
@Composable
fun BrushBar(
    level: Int,
    eraser: Boolean,
    color: Color,
    onLevel: (Int) -> Unit,
    onEraser: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = CircleShape,
        color = LocalQuartzColors.current.pill,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in 1..5) {
                val selected = !eraser && l == level
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onLevel(l) },
                    contentAlignment = Alignment.Center,
                ) {
                    val dot = listOf(4, 7, 11, 16, 22)[l - 1].dp
                    Box(Modifier.size(dot).background(color, CircleShape))
                }
            }
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (eraser) accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(onClick = onEraser),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Rounded.AutoFixNormal,
                    contentDescription = "Eraser",
                )
            }
            androidx.compose.material3.TextButton(onClick = onDone) { Text("Done") }
            Text(
                "Two fingers to scroll",
                fontSize = 12.sp,
                color = LocalQuartzColors.current.textSecondary,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}
