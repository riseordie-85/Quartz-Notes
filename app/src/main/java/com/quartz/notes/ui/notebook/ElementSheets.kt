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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.quartz.notes.data.model.BrushType
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.material.icons.rounded.TouchApp
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

/**
 * Brush options shown with the toolbar while drawing: the kind of brush (pen, pencil, marker,
 * highlighter), its size (quick picks and a slider), the eraser, whether fingers draw, and done.
 */
@Composable
fun BrushBar(
    type: String,
    size: Float,
    eraser: Boolean,
    color: Color,
    highlight: Color,
    onType: (String) -> Unit,
    onSize: (Float) -> Unit,
    onEraser: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Fingers draw (off: only a pen draws; fingers move the page). */
    fingerDraw: Boolean = true,
    onFingerDraw: ((Boolean) -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = LocalQuartzColors.current.textSecondary
    val ink = if (type == BrushType.HIGHLIGHTER) highlight else color
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = LocalQuartzColors.current.pill,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BrushType.ALL.forEach { t ->
                    val on = !eraser && t == type
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) accent.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable(onClickLabel = BrushType.label(t)) { onType(t) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.Icon(brushIcon(t), contentDescription = null, tint = if (on) accent else MaterialTheme.colorScheme.onSurface)
                        Text(BrushType.label(t), fontSize = 11.sp, color = if (on) accent else muted)
                    }
                }
                Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 32.dp).background(muted.copy(alpha = 0.3f)))
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (eraser) accent.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable(onClickLabel = "Eraser", onClick = onEraser)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.material3.Icon(Icons.Rounded.AutoFixNormal, contentDescription = null, tint = if (eraser) accent else MaterialTheme.colorScheme.onSurface)
                    Text("Eraser", fontSize = 11.sp, color = if (eraser) accent else muted)
                }
                if (onFingerDraw != null) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (fingerDraw) accent.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable(onClickLabel = if (fingerDraw) "Draw with a pen only" else "Draw with fingers too") { onFingerDraw(!fingerDraw) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Rounded.TouchApp,
                            contentDescription = null,
                            tint = if (fingerDraw) accent else muted,
                        )
                        Text(if (fingerDraw) "Fingers draw" else "Pen only", fontSize = 11.sp, color = if (fingerDraw) accent else muted)
                    }
                }
                androidx.compose.material3.TextButton(onClick = onDone) { Text("Done") }
            }
            if (!eraser) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    // Quick sizes, each drawn at (a hint of) its width.
                    Row(
                        Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrushType.SIZES.forEach { s ->
                            val on = kotlin.math.abs(s - size) < 0.01f
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (on) accent.copy(alpha = 0.16f) else Color.Transparent)
                                    .clickable(onClickLabel = "Size ${formatSize(s)}") { onSize(s) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size((2f + s * 0.75f).coerceAtMost(24f).dp).background(ink, CircleShape))
                            }
                        }
                    }
                    // Any size in between: the slider runs on a log scale so small sizes get room.
                    val lo = kotlin.math.ln(BrushType.MIN_SIZE)
                    val hi = kotlin.math.ln(BrushType.MAX_SIZE)
                    androidx.compose.material3.Slider(
                        value = ((kotlin.math.ln(size.coerceIn(BrushType.MIN_SIZE, BrushType.MAX_SIZE)) - lo) / (hi - lo)),
                        onValueChange = { v -> onSize((kotlin.math.round(kotlin.math.exp(lo + v * (hi - lo)) * 10f) / 10f).coerceIn(BrushType.MIN_SIZE, BrushType.MAX_SIZE)) },
                        modifier = Modifier.width(150.dp).padding(start = 8.dp).semantics { contentDescription = "Brush size" },
                    )
                    Text(formatSize(size), fontSize = 12.sp, color = muted, modifier = Modifier.width(36.dp).padding(start = 6.dp))
                }
            }
        }
    }
}

private fun formatSize(s: Float): String = if (s < 10f && s != s.toInt().toFloat()) "%.1f".format(s) else s.toInt().toString()

private fun brushIcon(type: String) = when (type) {
    BrushType.PENCIL -> Icons.Rounded.Edit
    BrushType.MARKER -> Icons.Rounded.BorderColor
    BrushType.HIGHLIGHTER -> Icons.Rounded.Highlight
    else -> Icons.Rounded.Draw
}
