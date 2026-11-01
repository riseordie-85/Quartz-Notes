package com.quartz.notes.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.BorderAll
import androidx.compose.material.icons.rounded.BorderBottom
import androidx.compose.material.icons.rounded.BorderClear
import androidx.compose.material.icons.rounded.BorderLeft
import androidx.compose.material.icons.rounded.BorderOuter
import androidx.compose.material.icons.rounded.BorderRight
import androidx.compose.material.icons.rounded.BorderTop
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.sheet.SheetGrid
import com.quartz.notes.data.sheet.TABLE_STYLES
import com.quartz.notes.data.sheet.colName
import com.quartz.notes.ui.components.DefaultSwatch
import com.quartz.notes.ui.components.Swatch
import com.quartz.notes.ui.editor.ToolButton
import com.quartz.notes.ui.theme.InkPresets
import com.quartz.notes.ui.theme.LocalQuartzColors

/** Cell fills: light tints that keep dark text readable, and a few strong ones. */
private val FillPresets = listOf(
    Color(0xFFFFF3B0), Color(0xFFD9F5C8), Color(0xFFD3E9FF), Color(0xFFFFD9E6), Color(0xFFFFE1C2),
    Color(0xFFE6DAFF), Color(0xFFEDEDF0), Color(0xFFFFE14D), Color(0xFF2F6FDE), Color(0xFF2E9E5B),
    Color(0xFFD6334C), Color(0xFF111114),
)

private val FORMATS = listOf(
    null to "General",
    "number" to "Number  1,234.50",
    "currency" to "Currency  $1,234.50",
    "percent" to "Percent  45%",
    "date" to "Date  10/31/2026",
    "time" to "Time  3:30 PM",
    "datetime" to "Date and time",
    "text" to "Plain text",
)

/** The sheet's tools: text style, colors, borders, alignment, wrapping, formats, sums, sorting, filters, tables. */
@Composable
internal fun SheetToolbar(
    state: SheetState,
    locked: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val s = state.currentStyle
    val clipboard = LocalClipboardManager.current
    fun edit(action: () -> Unit): () -> Unit = { if (!locked) action() }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = LocalQuartzColors.current.pill,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolButton(selected = s.b, onClick = edit { state.style { it.copy(b = !s.b) } }, description = "Bold") {
                Icon(Icons.Rounded.FormatBold, null)
            }
            ToolButton(selected = s.i, onClick = edit { state.style { it.copy(i = !s.i) } }, description = "Italic") {
                Icon(Icons.Rounded.FormatItalic, null)
            }
            ToolButton(selected = s.u, onClick = edit { state.style { it.copy(u = !s.u) } }, description = "Underline") {
                Icon(Icons.Rounded.FormatUnderlined, null)
            }
            ColorMenu(
                icon = { Icon(Icons.Rounded.FormatColorText, null, tint = parseHex(s.color) ?: MaterialTheme.colorScheme.onSurface) },
                title = "Text color",
                description = "Text color",
                presets = InkPresets,
                current = s.color,
                enabled = !locked,
            ) { hex -> state.style { it.copy(color = hex) } }
            ColorMenu(
                icon = { Icon(Icons.Rounded.FormatColorFill, null, tint = parseHex(s.fill) ?: MaterialTheme.colorScheme.onSurface) },
                title = "Fill",
                description = "Fill color",
                presets = FillPresets,
                current = s.fill,
                enabled = !locked,
            ) { hex -> state.style { it.copy(fill = hex) } }
            Menu(icon = { Icon(Icons.Rounded.BorderAll, null) }, description = "Borders", enabled = !locked) { close ->
                listOf(
                    Triple(SheetGrid.Borders.ALL, "All borders", Icons.Rounded.BorderAll),
                    Triple(SheetGrid.Borders.OUTLINE, "Outline", Icons.Rounded.BorderOuter),
                    Triple(SheetGrid.Borders.TOP, "Top", Icons.Rounded.BorderTop),
                    Triple(SheetGrid.Borders.BOTTOM, "Bottom", Icons.Rounded.BorderBottom),
                    Triple(SheetGrid.Borders.LEFT, "Left", Icons.Rounded.BorderLeft),
                    Triple(SheetGrid.Borders.RIGHT, "Right", Icons.Rounded.BorderRight),
                    Triple(SheetGrid.Borders.NONE, "No borders", Icons.Rounded.BorderClear),
                ).forEach { (b, label, icon) ->
                    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = {
                        close()
                        state.borders(b)
                    })
                }
            }
            Menu(
                icon = {
                    Icon(
                        when (s.align) {
                            "center" -> Icons.Rounded.FormatAlignCenter
                            "right" -> Icons.AutoMirrored.Rounded.FormatAlignRight
                            else -> Icons.AutoMirrored.Rounded.FormatAlignLeft
                        },
                        null,
                    )
                },
                description = "Alignment",
                enabled = !locked,
            ) { close ->
                listOf(
                    Triple(null, "Automatic (numbers right)", Icons.AutoMirrored.Rounded.FormatAlignLeft),
                    Triple("left", "Left", Icons.AutoMirrored.Rounded.FormatAlignLeft),
                    Triple("center", "Center", Icons.Rounded.FormatAlignCenter),
                    Triple("right", "Right", Icons.AutoMirrored.Rounded.FormatAlignRight),
                ).forEach { (a, label, icon) ->
                    DropdownMenuItem(
                        text = { Text(label, fontWeight = if (s.align == a) FontWeight.Bold else null) },
                        leadingIcon = { Icon(icon, null) },
                        onClick = {
                            close()
                            state.style { it.copy(align = a) }
                        },
                    )
                }
            }
            ToolButton(selected = s.wrap, onClick = edit { state.style { it.copy(wrap = !s.wrap) } }, description = "Wrap text") {
                Icon(Icons.AutoMirrored.Rounded.WrapText, null)
            }
            Menu(
                icon = { Text("123", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
                description = "Number format",
                enabled = !locked,
            ) { close ->
                FORMATS.forEach { (fmt, label) ->
                    DropdownMenuItem(
                        text = { Text(label, fontWeight = if (s.fmt == fmt || (fmt == null && s.fmt == "general")) FontWeight.Bold else null) },
                        onClick = {
                            close()
                            state.style { it.copy(fmt = fmt, dp = null) }
                        },
                    )
                }
                HorizontalDivider()
                val places = s.dp ?: SheetState.defaultPlaces(s.fmt)
                DropdownMenuItem(text = { Text("More decimal places") }, onClick = {
                    state.style { it.copy(fmt = it.fmt ?: "number", dp = ((it.dp ?: SheetState.defaultPlaces(it.fmt ?: "number")) + 1).coerceAtMost(10)) }
                })
                DropdownMenuItem(text = { Text("Fewer decimal places") }, enabled = places > 0 || s.fmt == null, onClick = {
                    state.style { it.copy(fmt = it.fmt ?: "number", dp = ((it.dp ?: SheetState.defaultPlaces(it.fmt ?: "number")) - 1).coerceAtLeast(0)) }
                })
            }
            ToolButton(onClick = edit { state.autoSum() }, description = "Sum") { Icon(Icons.Rounded.Functions, null) }
            Menu(icon = { Icon(Icons.AutoMirrored.Rounded.Sort, null) }, description = "Sort", enabled = !locked) { close ->
                val col = colName(state.area.c1)
                DropdownMenuItem(text = { Text("Sort by column $col, A → Z (1 → 9)") }, onClick = {
                    close()
                    state.sort(desc = false)
                })
                DropdownMenuItem(text = { Text("Sort by column $col, Z → A (9 → 1)") }, onClick = {
                    close()
                    state.sort(desc = true)
                })
            }
            var filtering by remember { mutableStateOf<Int?>(null) }
            ToolButton(
                selected = state.data.hide.isNotEmpty(),
                onClick = edit { filtering = state.area.c1 },
                description = "Filter",
            ) { Icon(Icons.Rounded.FilterAlt, null) }
            filtering?.let { col -> FilterDialog(state, col) { filtering = null } }
            Menu(icon = { Icon(Icons.Rounded.TableChart, null) }, description = "Table look", enabled = !locked) { close ->
                Text(
                    "Table look",
                    fontSize = 13.sp,
                    color = LocalQuartzColors.current.textSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                TABLE_STYLES.forEach { (name, t) ->
                    DropdownMenuItem(
                        text = { Text(name.replaceFirstChar { it.uppercase() }, fontWeight = if (state.data.table == name) FontWeight.Bold else null) },
                        leadingIcon = { TableSample(Color(t.header), Color(t.band)) },
                        onClick = {
                            close()
                            state.table(name)
                        },
                    )
                }
                DropdownMenuItem(text = { Text("No table look") }, enabled = state.data.table != null, onClick = {
                    close()
                    state.table(null)
                })
            }
            Menu(icon = { Icon(Icons.Rounded.MoreHoriz, null) }, description = "More: cut, copy, paste, rows and columns", enabled = true) { close ->
                SheetMenuItems(
                    state = state,
                    locked = locked,
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                    pasteText = { clipboard.getText()?.text },
                    onDone = close,
                )
            }
            trailing()
        }
    }
}

/** A button that opens a menu. */
@Composable
private fun Menu(
    icon: @Composable () -> Unit,
    description: String,
    enabled: Boolean,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(onClick = { if (enabled) open = true }, description = description) { icon() }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorMenu(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    presets: List<Color>,
    current: String?,
    enabled: Boolean,
    onPick: (String?) -> Unit,
) {
    Menu(icon = icon, description = description, enabled = enabled) { close ->
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(title, fontSize = 13.sp, color = LocalQuartzColors.current.textSecondary)
            FlowRow(
                Modifier.padding(top = 10.dp).width(232.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DefaultSwatch(selected = current == null, onClick = {
                    close()
                    onPick(null)
                }, size = 34.dp)
                presets.forEach { c ->
                    Swatch(c, selected = current == c.hex(), onClick = {
                        close()
                        onPick(c.hex())
                    }, size = 34.dp)
                }
            }
        }
    }
}

@Composable
private fun TableSample(header: Color, band: Color) {
    Column(
        Modifier
            .size(width = 26.dp, height = 20.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, header, RoundedCornerShape(3.dp)),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).background(header))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.White))
        Box(Modifier.fillMaxWidth().weight(1f).background(band))
    }
}

/** Which of a column's values to show; the rest are hidden. */
@Composable
private fun FilterDialog(state: SheetState, col: Int, onDismiss: () -> Unit) {
    val values = remember(state.data, col) { SheetGrid.columnValues(state.data, col, state.calc) }
    val hidden = remember(col) { mutableStateListOf<String>().apply { addAll(state.data.hide[colName(col)].orEmpty()) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter column ${colName(col)}") },
        text = {
            if (values.isEmpty()) {
                Text("Nothing in this column yet (under the first row, which stays as the header).")
            } else {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { hidden.clear() }) { Text("Show all") }
                        TextButton(onClick = {
                            hidden.clear()
                            hidden.addAll(values)
                        }) { Text("Hide all") }
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(values) { v ->
                            Row(
                                Modifier.fillMaxWidth().toggleable(value = v !in hidden, onValueChange = { on -> if (on) hidden.remove(v) else hidden.add(v) }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = v !in hidden, onCheckedChange = null)
                                Text(v.ifEmpty { "(empty)" })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                state.filter(col, hidden.toList())
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                if (state.data.hide.isNotEmpty()) {
                    TextButton(onClick = {
                        state.clearFilters()
                        onDismiss()
                    }) { Text("Clear all filters") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
