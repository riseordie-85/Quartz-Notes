package com.quartz.notes.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatAlignJustify
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.ui.components.ColorPickerDialog
import com.quartz.notes.ui.components.DefaultSwatch
import com.quartz.notes.ui.components.Swatch
import com.quartz.notes.ui.theme.HighlightPresets
import com.quartz.notes.ui.theme.InkPresets
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.toHex

/**
 * Formatting toolbar shared by quick notes and notebook pages.
 * [ink] is null for automatic ink. Choosing colors calls [onInkChange] / [onHighlightChange] so they persist.
 */
@Composable
fun EditorToolbar(
    state: RichEditorState?,
    ink: Color?,
    autoInk: Color,
    highlight: Color,
    onInkChange: (Color?) -> Unit,
    onHighlightChange: (Color) -> Unit,
    onFormat: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = LocalQuartzColors.current.pill,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolButton(onClick = onFormat, description = "Text style") {
                Text("Aa", fontSize = 21.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            }
            ToolButton(
                selected = state?.currentType == BlockType.BULLET,
                onClick = { state?.toggleBlockType(BlockType.BULLET) },
                description = "Bulleted list",
            ) { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null) }
            ToolButton(onClick = { state?.cycleAlign() }, description = "Alignment") {
                Icon(
                    when (state?.currentAlign ?: Align.START) {
                        Align.START -> Icons.AutoMirrored.Rounded.FormatAlignLeft
                        Align.CENTER -> Icons.Rounded.FormatAlignCenter
                        Align.END -> Icons.AutoMirrored.Rounded.FormatAlignRight
                        Align.JUSTIFY -> Icons.Rounded.FormatAlignJustify
                    },
                    null,
                )
            }
            InkButton(state, ink, autoInk, onInkChange)
            HighlightButton(state, highlight, onHighlightChange)
            ToolButton(
                selected = state?.currentType == BlockType.TASK,
                onClick = { state?.toggleBlockType(BlockType.TASK) },
                description = "Checklist",
            ) { Icon(Icons.Rounded.Checklist, null) }
            trailing()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    description: String? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = description),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ColorCircle(color: Color, ring: Boolean = false) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (ring) 2.dp else 1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (ring) 0.85f else 0.2f), CircleShape),
    )
}

/** Default ink color. Tap applies it to the selection (or opens choices); long press always opens choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InkButton(state: RichEditorState?, ink: Color?, autoInk: Color, onInkChange: (Color?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }

    fun choose(c: Color?) {
        onInkChange(c)
        // Colors the selection, or what's typed next when nothing is selected.
        state?.applyValue(InlineStyle.COLOR, c?.toHex())
        menu = false
    }

    Box {
        ToolButton(
            onClick = {
                if (state?.hasSelection == true) state.applyValue(InlineStyle.COLOR, ink?.toHex()) else menu = true
            },
            onLongClick = { menu = true },
            description = "Ink color",
        ) { ColorCircle(ink ?: autoInk) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("Ink color", fontSize = 13.sp, color = LocalQuartzColors.current.textSecondary)
                FlowRow(
                    Modifier.padding(top = 10.dp).width(220.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DefaultSwatch(selected = ink == null, onClick = { choose(null) }, size = 34.dp)
                    InkPresets.forEach { c ->
                        Swatch(c, selected = ink?.toArgb() == c.toArgb(), onClick = { choose(c) }, size = 34.dp)
                    }
                }
                TextButton(onClick = { menu = false; custom = true }) { Text("Custom color…") }
            }
        }
    }
    if (custom) {
        ColorPickerDialog(
            title = "Ink color",
            initial = ink,
            presets = InkPresets,
            allowDefault = true,
            onDismiss = { custom = false },
            onPick = {
                custom = false
                choose(it)
            },
        )
    }
}

/** Highlighter. Tap toggles highlight on the selection; long press picks the highlight color. */
@Composable
private fun HighlightButton(state: RichEditorState?, highlight: Color, onHighlightChange: (Color) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ToolButton(
            selected = state?.isStyleActive(InlineStyle.HIGHLIGHT) == true,
            onClick = { state?.toggleStyle(InlineStyle.HIGHLIGHT, highlight.toHex()) },
            onLongClick = { menu = true },
            description = "Highlight",
        ) { ColorCircle(highlight) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("Highlight color", fontSize = 13.sp, color = LocalQuartzColors.current.textSecondary)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HighlightPresets.forEach { c ->
                        Swatch(c, selected = c.toArgb() == highlight.toArgb(), size = 34.dp, onClick = {
                            onHighlightChange(c)
                            if (state?.hasSelection == true) state.applyValue(InlineStyle.HIGHLIGHT, c.toHex())
                            menu = false
                        })
                    }
                }
                if (state?.hasSelection == true) {
                    TextButton(onClick = {
                        state.applyValue(InlineStyle.HIGHLIGHT, null)
                        menu = false
                    }) { Text("Remove highlight") }
                }
            }
        }
    }
}
