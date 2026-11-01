package com.quartz.notes.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.sheet.SheetGrid
import com.quartz.notes.data.sheet.SheetNumbers
import com.quartz.notes.ui.notebook.PageSide
import com.quartz.notes.ui.notebook.drawCenterSeam
import com.quartz.notes.ui.notebook.drawSeam
import com.quartz.notes.ui.theme.LocalQuartzColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/** A sheet page: its title, the formula bar and the grid. */
@OptIn(FlowPreview::class)
@Composable
internal fun SheetPageView(
    state: SheetState,
    title: String,
    onTitle: (String) -> Unit,
    locked: Boolean,
    onLock: () -> Unit,
    side: PageSide,
    paper: Color,
    ink: Color,
    onActivate: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestSave by rememberUpdatedState(onSave)
    val latestActivate by rememberUpdatedState(onActivate)
    DisposableEffect(state) { onDispose { latestSave() } }
    LaunchedEffect(state) {
        snapshotFlow { state.version to title }.drop(1).debounce(600).collect { latestSave() }
    }
    // NOW() and TODAY() keep up with the clock.
    LaunchedEffect(state) {
        while (true) {
            delay(60_000)
            state.clock++
        }
    }
    LaunchedEffect(locked) {
        if (locked && state.editing != null) state.cancelEdit()
    }

    val quartz = LocalQuartzColors.current
    val shape = side.shape
    val seamShade = if (quartz.isDark) 0.45f else 0.13f
    Column(
        modifier
            .fillMaxSize()
            .padding(start = side.outerStart, end = side.outerEnd, top = 6.dp, bottom = 6.dp)
            .shadow(2.dp, shape)
            .clip(shape)
            .background(paper)
            .drawBehind {
                when (side) {
                    PageSide.LEFT, PageSide.RIGHT -> drawSeam(atRightEdge = side == PageSide.LEFT, strength = seamShade)
                    PageSide.SPREAD -> drawCenterSeam(seamShade)
                    PageSide.SINGLE -> Unit
                }
            }
            .onFocusChanged { if (it.hasFocus) onActivate() }
            // Any touch makes this the page the toolbar works on.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    latestActivate()
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = title,
                onValueChange = { onTitle(it.replace("\n", "")) },
                readOnly = locked,
                singleLine = true,
                textStyle = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ink),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) Text("Sheet title", style = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.32f)))
                        inner()
                    }
                },
            )
            IconButton(onClick = {
                onActivate()
                onLock()
            }) {
                Icon(
                    if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    contentDescription = if (locked) "Unlock page" else "Lock page",
                    tint = if (locked) MaterialTheme.colorScheme.primary else ink.copy(alpha = 0.42f),
                )
            }
        }
        FormulaBar(state, locked, ink, Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onFocusChanged { if (it.hasFocus) onActivate() },
        ) {
            SheetGridView(
                state = state,
                locked = locked,
                paper = paper,
                ink = ink,
                modifier = Modifier.fillMaxSize().testTag("sheet-grid"),
            )
        }
    }
}

/** The selected cell's name, what's typed in it (to read or change), and totals for a selection. */
@Composable
private fun FormulaBar(state: SheetState, locked: Boolean, ink: Color, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    DisposableEffect(state) {
        state.focusBar = { runCatching { focus.requestFocus() } }
        onDispose { state.focusBar = null }
    }
    val editing = state.editing
    var barFocused by remember { mutableStateOf(false) }
    // Typing finished somewhere else (a tap on the grid): let go of the keyboard.
    LaunchedEffect(editing == null) {
        if (editing == null && barFocused) focusManager.clearFocus()
    }
    val anchor = state.anchor
    val shown = editing ?: TextFieldValue(state.editText(anchor.first, anchor.second))
    val muted = ink.copy(alpha = 0.55f)
    Row(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ink.copy(alpha = 0.05f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            state.label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = ink.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 52.dp, max = 96.dp).padding(horizontal = 10.dp),
        )
        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(ink.copy(alpha = 0.15f)))
        Text("fx", fontSize = 13.sp, fontFamily = FontFamily.Serif, color = muted, modifier = Modifier.padding(horizontal = 8.dp))
        BasicTextField(
            value = shown,
            onValueChange = { if (!locked) state.updateEdit(it) },
            readOnly = locked,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = ink),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { state.commitEdit(1, 0) }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .testTag("sheet-formula")
                .onFocusChanged { barFocused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || state.editing == null) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> true.also { state.commitEdit(if (e.isShiftPressed) -1 else 1, 0) }
                        Key.Tab -> true.also { state.commitEdit(0, if (e.isShiftPressed) -1 else 1) }
                        Key.Escape -> true.also { state.cancelEdit() }
                        else -> false
                    }
                },
        )
        val area = state.area
        if (!area.single && editing == null) {
            val stats = remember(state.data, area, state.calc) { SheetGrid.stats(state.data, state.calc, area) }
            if (stats != null) {
                Text(
                    "Sum ${SheetNumbers.general(stats.sum)} · Avg ${SheetNumbers.general(stats.average)} · ${stats.count}",
                    fontSize = 12.sp,
                    color = muted,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }
    }
}

/**
 * Keys for a sheet when nothing is being typed: arrows (Shift: select more), Tab, Enter, F2 to
 * type, Delete to clear, Ctrl+Z / Y, C / X / V, A, B / I / U, D / R to fill, and any letter or
 * digit starts typing in the selected cell.
 */
fun sheetKey(state: SheetState, e: KeyEvent, locked: Boolean, clipboard: ClipboardManager): Boolean {
    if (e.type != KeyEventType.KeyDown || state.editing != null) return false
    val cmd = e.isCtrlPressed || e.isMetaPressed
    val shift = e.isShiftPressed
    if (cmd) {
        return when (e.key) {
            Key.Z -> true.also { if (!locked) { if (shift) state.redo() else state.undo() } }
            Key.Y -> true.also { if (!locked) state.redo() }
            Key.C -> true.also { clipboard.setText(AnnotatedString(state.copy())) }
            Key.X -> true.also { if (!locked) clipboard.setText(AnnotatedString(state.cut())) }
            Key.V -> true.also { if (!locked) state.paste(clipboard.getText()?.text) }
            Key.A -> true.also { state.selectAll() }
            Key.B -> true.also { if (!locked) { val on = !state.currentStyle.b; state.style { it.copy(b = on) } } }
            Key.I -> true.also { if (!locked) { val on = !state.currentStyle.i; state.style { it.copy(i = on) } } }
            Key.U -> true.also { if (!locked) { val on = !state.currentStyle.u; state.style { it.copy(u = on) } } }
            Key.D -> true.also { if (!locked) state.fillDown() }
            Key.R -> true.also { if (!locked) state.fillRight() }
            Key.DirectionUp -> true.also { state.select(0, state.focus.second, shift) }
            Key.DirectionDown -> true.also { state.select(maxOf(state.data.usedSize.first - 1, 0), state.focus.second, shift) }
            Key.DirectionLeft -> true.also { state.select(state.focus.first, 0, shift) }
            Key.DirectionRight -> true.also { state.select(state.focus.first, maxOf(state.data.usedSize.second - 1, 0), shift) }
            else -> false
        }
    }
    if (e.isAltPressed) return false
    return when (e.key) {
        Key.DirectionUp -> true.also { state.moveBy(-1, 0, shift) }
        Key.DirectionDown -> true.also { state.moveBy(1, 0, shift) }
        Key.DirectionLeft -> true.also { state.moveBy(0, -1, shift) }
        Key.DirectionRight -> true.also { state.moveBy(0, 1, shift) }
        Key.Tab -> true.also { state.moveBy(0, if (shift) -1 else 1) }
        Key.Enter, Key.NumPadEnter -> true.also { state.moveBy(if (shift) -1 else 1, 0) }
        Key.PageDown -> true.also { state.moveBy(20, 0, shift) }
        Key.PageUp -> true.also { state.moveBy(-20, 0, shift) }
        Key.MoveHome -> true.also { state.select(state.focus.first, 0, shift) }
        Key.F2 -> true.also {
            if (!locked) {
                state.startEdit()
                state.focusBar?.invoke()
            }
        }
        Key.Delete, Key.Backspace -> true.also { if (!locked) state.clearValues() }
        else -> {
            val ch = e.utf16CodePoint
            if (ch >= 32 && ch != 127 && !locked) {
                state.startEdit(String(Character.toChars(ch)))
                state.focusBar?.invoke()
                true
            } else {
                false
            }
        }
    }
}

/** The sheet's search text with its title, for sharing as text. */
fun sheetAsText(title: String, state: SheetState): String {
    val body = SheetGrid.plainText(state.data)
    return listOf(title.trim(), body).filter { it.isNotEmpty() }.joinToString("\n\n")
}
