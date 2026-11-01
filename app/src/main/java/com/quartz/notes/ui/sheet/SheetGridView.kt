package com.quartz.notes.ui.sheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.sheet.SheetArea
import com.quartz.notes.data.sheet.SheetGrid
import com.quartz.notes.data.sheet.colName
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What a press on the grid is doing as it moves. */
private enum class Drag { NONE, SELECT, ROWS, COLS, WIDTH, REFERENCE }

/**
 * The cells, with column letters along the top and row numbers down the side (both stay put while
 * the cells scroll). Tap to select (tap again, or double-tap, to type); drag with a mouse or pen, or
 * hold then drag with a finger, to select more; drag the corner dot to stretch the selection; hold
 * (or right-click) for cut, copy, paste and rows and columns; pinch to zoom.
 */
@Composable
internal fun SheetGridView(
    state: SheetState,
    locked: Boolean,
    paper: Color,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 600)
    val accent = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val viewConfig = LocalViewConfiguration.current
    val focusManager = LocalFocusManager.current

    val data = state.data
    val calc = state.calc
    val zoom = state.zoom
    val look = remember(paper, ink, accent, density.density, zoom) {
        SheetLook(paper = paper, ink = ink, accent = accent, unit = density.density * zoom, fontSp = SheetSizes.FONT * zoom)
    }
    val hidden = remember(data, calc) { SheetGrid.hiddenRows(data, calc) }
    // A column being widened: its live width (page units) until the drag ends.
    var resizing by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    val rows = state.shownRows
    val cols = state.shownCols
    val g = remember(data, calc, hidden, rows, cols, look, resizing) {
        sheetGeometry(data, calc, hidden, rows, cols, look, measurer, resizing)
    }
    val headerW = SheetSizes.HEADER_W * look.unit
    val headerH = SheetSizes.HEADER_H * look.unit
    // Read through these in the touch handling, which outlives any one zoom or size.
    val hw by rememberUpdatedState(headerW)
    val hh by rememberUpdatedState(headerH)
    val unitNow by rememberUpdatedState(look.unit)

    var menuAt by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(modifier) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val cellsW = (viewW - headerW).coerceAtLeast(1f)
        val cellsH = (viewH - headerH).coerceAtLeast(1f)
        var sx by remember { mutableFloatStateOf(0f) }
        var sy by remember { mutableFloatStateOf(0f) }
        val maxX = (g.width - cellsW + 40f).coerceAtLeast(0f)
        val maxY = (g.height - cellsH + 40f).coerceAtLeast(0f)
        if (sx > maxX) sx = maxX
        if (sy > maxY) sy = maxY
        val geometry by rememberUpdatedState(g)
        val vw by rememberUpdatedState(viewW)
        val vh by rememberUpdatedState(viewH)
        val latestMaxX by rememberUpdatedState(maxX)
        val latestMaxY by rememberUpdatedState(maxY)
        val hScroll = rememberScrollableState { d ->
            val old = sx
            sx = (sx - d).coerceIn(0f, latestMaxX)
            old - sx
        }
        val vScroll = rememberScrollableState { d ->
            val old = sy
            sy = (sy - d).coerceIn(0f, latestMaxY)
            old - sy
        }
        // More rows and columns appear as the sheet is scrolled to its end.
        LaunchedEffect(state) {
            snapshotFlow { Triple(sy >= latestMaxY - cellsH, sx >= latestMaxX - cellsW, latestMaxY) }.collect { (nearBottom, nearRight, _) ->
                if (nearBottom && state.shownRows < 5000) state.extraRows += 50
                if (nearRight && state.shownCols < 200) state.extraCols += 5
            }
        }
        // Keeps the moving corner of the selection in view.
        LaunchedEffect(state) {
            snapshotFlow { state.focus }.collect { (r, c) ->
                val gg = geometry
                if (r >= gg.rows || c >= gg.cols) return@collect
                val l = gg.colX[c]
                val rr = gg.colX[c + 1]
                val t = gg.rowY[r]
                val b = gg.rowY[r + 1]
                if (l < sx) sx = l else if (rr > sx + cellsW) sx = (rr - cellsW).coerceAtMost(latestMaxX)
                if (t < sy) sy = t else if (b > sy + cellsH) sy = (b - cellsH).coerceAtMost(latestMaxY)
            }
        }

        fun cellAt(p: Offset): Pair<Int, Int> {
            val gg = geometry
            return gg.rowAt(p.y - hh + sy) to gg.colAt(p.x - hw + sx)
        }

        /** Near a column's right edge in the header row: that column. */
        fun edgeAt(p: Offset): Int? {
            if (p.y > hh || p.x < hw) return null
            val x = p.x - hw + sx
            val gg = geometry
            val c = gg.colAt(x)
            val slop = 10f * density.density
            return when {
                abs(gg.colX[c + 1] - x) < slop -> c
                c > 0 && abs(gg.colX[c] - x) < slop -> c - 1
                else -> null
            }
        }

        fun handleAt(p: Offset): Boolean {
            val a = state.area
            val gg = geometry
            if (a.r2 >= gg.rows || a.c2 >= gg.cols) return false
            val hx = gg.colX[a.c2 + 1] - sx + hw
            val hy = gg.rowY[a.r2 + 1] - sy + hh
            return hypot(p.x - hx, p.y - hy) < 22f * density.density
        }

        // Tapping a cell: puts its reference into a formula being typed, types again in the
        // selected cell, or selects it (finishing any typing first).
        fun tapCell(cell: Pair<Int, Int>, shift: Boolean, again: Boolean) {
            if (state.editing != null) {
                if (state.wantsReference()) {
                    state.insertReference(SheetArea.of(cell, cell))
                    return
                }
                state.commitEdit(0, 0)
            }
            if (shift) {
                state.select(cell.first, cell.second, extend = true)
                return
            }
            val wasSelected = state.area.single && state.anchor == cell
            state.select(cell.first, cell.second)
            if ((again || wasSelected) && !locked) {
                state.startEdit()
                state.focusBar?.invoke()
            }
        }

        var lastTap by remember { mutableStateOf(0L to (-1 to -1)) }

        Canvas(
            Modifier
                .fillMaxSize()
                .scrollable(vScroll, Orientation.Vertical)
                .scrollable(hScroll, Orientation.Horizontal)
                .pointerInput(state, locked) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // The title keeps no hold on the keys once the cells are touched.
                        if (state.editing == null) focusManager.clearFocus()
                        val p0 = down.position
                        val touch = down.type == PointerType.Touch
                        val firstEvent = currentEvent
                        val shift = firstEvent.keyboardModifiers.isShiftPressed
                        if (firstEvent.buttons.isSecondaryPressed) {
                            // Right-click: the menu, for the selection (or the cell clicked outside it).
                            val cell = cellAt(p0)
                            if (p0.x > hw && p0.y > hh && cell !in state.area) state.select(cell.first, cell.second)
                            menuAt = p0
                            down.consume()
                            return@awaitEachGesture
                        }
                        val corner = p0.x < hw && p0.y < hh
                        val edge = edgeAt(p0)
                        var mode = Drag.NONE
                        var startWidth = 0f
                        val now = down.uptimeMillis
                        when {
                            corner -> {
                                if (state.editing != null) state.commitEdit(0, 0)
                                state.selectAll()
                                down.consume()
                                return@awaitEachGesture
                            }
                            edge != null && !locked -> {
                                mode = Drag.WIDTH
                                startWidth = state.data.width(edge)
                                resizing = edge to startWidth
                                down.consume()
                            }
                            p0.y < hh -> {
                                val c = geometry.colAt(p0.x - hw + sx)
                                if (state.editing != null) state.commitEdit(0, 0)
                                if (shift) state.select(state.shownRows - 1, c, extend = true) else state.selectCols(c, c)
                                mode = Drag.COLS
                                down.consume()
                            }
                            p0.x < hw -> {
                                val r = geometry.rowAt(p0.y - hh + sy)
                                if (state.editing != null) state.commitEdit(0, 0)
                                if (shift) state.select(r, state.shownCols - 1, extend = true) else state.selectRows(r, r)
                                mode = Drag.ROWS
                                down.consume()
                            }
                            handleAt(p0) && state.editing == null -> {
                                mode = Drag.SELECT
                                down.consume()
                            }
                            !touch -> {
                                // Mouse and pen: press selects, dragging selects more; a second click types.
                                val cell = cellAt(p0)
                                val again = lastTap.second == cell && now - lastTap.first < viewConfig.doubleTapTimeoutMillis
                                lastTap = now to cell
                                if (state.editing != null && state.wantsReference()) {
                                    state.insertReference(SheetArea.of(cell, cell))
                                    mode = Drag.REFERENCE
                                } else {
                                    if (state.editing != null) state.commitEdit(0, 0)
                                    if (shift) {
                                        state.select(cell.first, cell.second, extend = true)
                                    } else {
                                        state.select(cell.first, cell.second)
                                        if (again && !locked) {
                                            state.startEdit()
                                            state.focusBar?.invoke()
                                        }
                                    }
                                    mode = Drag.SELECT
                                }
                                down.consume()
                            }
                        }

                        if (mode == Drag.NONE) {
                            // A finger on the cells: a tap, a hold, a pinch, or a scroll (left to the scrolling).
                            val cell = cellAt(p0)
                            var moved = false
                            var lifted = false
                            var pinch = false
                            val result = withTimeoutOrNull(viewConfig.longPressTimeoutMillis) {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.changes.count { it.pressed } >= 2) {
                                        pinch = true
                                        return@withTimeoutOrNull
                                    }
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                                    if (!ch.pressed) {
                                        lifted = true
                                        return@withTimeoutOrNull
                                    }
                                    if ((ch.position - p0).getDistance() > viewConfig.touchSlop) {
                                        moved = true
                                        return@withTimeoutOrNull
                                    }
                                }
                            }
                            if (pinch) {
                                // Two fingers: zoom (and nothing scrolls meanwhile).
                                var last: Float? = null
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val pressed = e.changes.filter { it.pressed }
                                    if (pressed.size < 2) {
                                        if (pressed.isEmpty()) break
                                        last = null
                                        continue
                                    }
                                    val d = (pressed[0].position - pressed[1].position).getDistance()
                                    val prev = last
                                    if (prev != null && prev > 0f) state.zoom = (state.zoom * d / prev).coerceIn(0.5f, 2.5f)
                                    last = d
                                    e.changes.forEach { it.consume() }
                                }
                                return@awaitEachGesture
                            }
                            if (moved) return@awaitEachGesture
                            if (lifted || result != null) {
                                if (lifted) {
                                    val again = lastTap.second == cell && now - lastTap.first < viewConfig.doubleTapTimeoutMillis
                                    lastTap = now to cell
                                    tapCell(cell, shift = false, again = again)
                                }
                                return@awaitEachGesture
                            }
                            // Held: select (or keep the selection it's in), then drag to select more.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (state.editing != null) {
                                if (state.wantsReference()) {
                                    state.insertReference(SheetArea.of(cell, cell))
                                    mode = Drag.REFERENCE
                                } else {
                                    state.commitEdit(0, 0)
                                }
                            }
                            if (mode == Drag.NONE) {
                                if (cell !in state.area) state.select(cell.first, cell.second)
                                mode = Drag.SELECT
                            }
                            var dragged = false
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                if (!dragged && (ch.position - p0).getDistance() > viewConfig.touchSlop) {
                                    dragged = true
                                    if (mode == Drag.SELECT) state.select(cell.first, cell.second)
                                }
                                if (dragged) {
                                    val at = cellAt(ch.position)
                                    if (mode == Drag.REFERENCE) state.insertReference(SheetArea.of(cell, at)) else state.select(at.first, at.second, extend = true)
                                }
                                ch.consume()
                            }
                            if (!dragged && mode == Drag.SELECT) menuAt = p0
                            return@awaitEachGesture
                        }

                        // Dragging: selecting cells, rows or columns, or widening a column.
                        val startCell = cellAt(p0)
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (ch.positionChange() == Offset.Zero) continue
                            val p = ch.position
                            val at = cellAt(p)
                            when (mode) {
                                Drag.WIDTH -> resizing = resizing?.let { (c, _) ->
                                    c to (startWidth + (p.x - p0.x) / unitNow).coerceIn(36f, 600f)
                                }
                                Drag.COLS -> state.select(state.shownRows - 1, geometry.colAt(p.x - hw + sx), extend = true)
                                Drag.ROWS -> state.select(geometry.rowAt(p.y - hh + sy), state.shownCols - 1, extend = true)
                                Drag.REFERENCE -> state.insertReference(SheetArea.of(startCell, at))
                                else -> state.select(at.first, at.second, extend = true)
                            }
                            // Dragging past the edge scrolls.
                            if (mode != Drag.WIDTH) {
                                if (p.x > vw - 20f) sx = (sx + 12f).coerceAtMost(latestMaxX)
                                if (p.x < hw + 10f && mode != Drag.ROWS) sx = (sx - 12f).coerceAtLeast(0f)
                                if (p.y > vh - 20f) sy = (sy + 12f).coerceAtMost(latestMaxY)
                                if (p.y < hh + 10f && mode != Drag.COLS) sy = (sy - 12f).coerceAtLeast(0f)
                            }
                            ch.consume()
                        }
                        if (mode == Drag.WIDTH) {
                            resizing?.let { (c, w) -> if (abs(w - startWidth) > 0.5f) state.setWidth(c, w) }
                            resizing = null
                        }
                    }
                },
        ) {
            val gg = g
            val r0 = gg.rowAt(sy)
            val r1 = gg.rowAt(sy + cellsH)
            val c0 = gg.colAt(sx)
            val c1 = gg.colAt(sx + cellsW)
            val a = state.area
            val editing = state.editing
            val (er, ec) = state.editCell

            // Cells.
            clipRect(headerW, headerH, size.width, size.height) {
                translate(headerW, headerH) {
                    drawCells(gg, data, calc, measurer, look, sx, sy, r0, r1, c0, c1)
                    // The selection: tinted, outlined, with a dot to stretch it.
                    if (a.r1 < gg.rows && a.c1 < gg.cols) {
                        val ar2 = minOf(a.r2, gg.rows - 1)
                        val ac2 = minOf(a.c2, gg.cols - 1)
                        val tl = Offset(gg.colX[a.c1] - sx, gg.rowY[a.r1] - sy)
                        val br = Offset(gg.colX[ac2 + 1] - sx, gg.rowY[ar2 + 1] - sy)
                        if (!a.single) drawRect(accent.copy(alpha = 0.12f), tl, Size(br.x - tl.x, br.y - tl.y))
                        drawRect(accent, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(2f * density.density))
                        if (editing == null && !locked) drawCircle(accent, 5f * density.density, br)
                    }
                    // The cell being typed in shows what's typed, over its neighbors if it's long.
                    if (editing != null && er < gg.rows && ec < gg.cols) {
                        val l = gg.colX[ec] - sx
                        val t = gg.rowY[er] - sy
                        val h = gg.rowY[er + 1] - gg.rowY[er]
                        val layout = measurer.measure(
                            editing.text,
                            TextStyle(color = ink, fontSize = (SheetSizes.FONT * zoom).sp),
                            softWrap = false, maxLines = 1, overflow = TextOverflow.Clip,
                        )
                        val w = maxOf(gg.colX[ec + 1] - gg.colX[ec], layout.size.width + 2 * look.pad + 4f)
                        drawRect(paper, Offset(l, t), Size(w, h))
                        drawText(layout, topLeft = Offset(l + look.pad, t + (h - layout.size.height) / 2))
                        drawRect(accent, Offset(l, t), Size(w, h), style = Stroke(2.5f * density.density))
                    }
                }
            }

            // Column letters.
            val headerStyle = TextStyle(color = look.headerText, fontSize = (12f * zoom).sp)
            val headerOn = TextStyle(color = accent, fontSize = (12f * zoom).sp, fontWeight = FontWeight.Bold)
            drawRect(paper, Offset.Zero, Size(size.width, headerH))
            drawRect(look.headerFill, Offset(headerW, 0f), Size(size.width - headerW, headerH))
            clipRect(headerW, 0f, size.width, headerH) {
                for (c in c0..c1) {
                    val l = gg.colX[c] - sx + headerW
                    val w = gg.colX[c + 1] - gg.colX[c]
                    val on = c in a.c1..a.c2
                    if (on) drawRect(accent.copy(alpha = 0.14f), Offset(l, 0f), Size(w, headerH))
                    val filtered = data.hide.containsKey(colName(c))
                    val label = colName(c) + if (filtered) " ▾" else ""
                    val layout = measurer.measure(AnnotatedString(label), if (on || filtered) headerOn else headerStyle)
                    drawText(layout, topLeft = Offset(l + (w - layout.size.width) / 2, (headerH - layout.size.height) / 2))
                    drawLine(look.gridLine, Offset(l + w, 0f), Offset(l + w, headerH), 1f)
                }
            }
            // Row numbers.
            drawRect(paper, Offset(0f, headerH), Size(headerW, size.height - headerH))
            drawRect(look.headerFill, Offset(0f, headerH), Size(headerW, size.height - headerH))
            clipRect(0f, headerH, headerW, size.height) {
                for (r in r0..r1) {
                    val t = gg.rowY[r] - sy + headerH
                    val h = gg.rowY[r + 1] - gg.rowY[r]
                    if (h <= 0f) continue
                    val on = r in a.r1..a.r2
                    if (on) drawRect(accent.copy(alpha = 0.14f), Offset(0f, t), Size(headerW, h))
                    val layout = measurer.measure(AnnotatedString((r + 1).toString()), if (on) headerOn else headerStyle)
                    drawText(layout, topLeft = Offset((headerW - layout.size.width) / 2, t + (h - layout.size.height) / 2))
                    drawLine(look.gridLine, Offset(0f, t + h), Offset(headerW, t + h), 1f)
                }
            }
            // The corner (select everything).
            drawRect(paper, Offset.Zero, Size(headerW, headerH))
            drawRect(look.headerFill, Offset.Zero, Size(headerW, headerH))
            drawLine(look.gridLine, Offset(0f, headerH), Offset(size.width, headerH), 1f)
            drawLine(look.gridLine, Offset(headerW, 0f), Offset(headerW, size.height), 1f)
        }

        // Hold or right-click: what can be done with the selection.
        menuAt?.let { at ->
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.size(1.dp)) {
                DropdownMenu(expanded = true, onDismissRequest = { menuAt = null }) {
                    SheetMenuItems(
                        state = state,
                        locked = locked,
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                        pasteText = { clipboard.getText()?.text },
                        onDone = { menuAt = null },
                    )
                }
            }
        }
    }
}

/** Cut, copy, paste and the row and column commands, for the hold / right-click menu. */
@Composable
internal fun SheetMenuItems(
    state: SheetState,
    locked: Boolean,
    onCopy: (String) -> Unit,
    pasteText: () -> String?,
    onDone: () -> Unit,
) {
    @Composable
    fun Item(label: String, enabled: Boolean = !locked, action: () -> Unit) {
        DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = {
            onDone()
            action()
        })
    }
    val a = state.area
    Item("Cut") { onCopy(state.cut()) }
    Item("Copy", enabled = true) { onCopy(state.copy()) }
    Item("Paste") { state.paste(pasteText()) }
    Item("Clear") { state.clearValues() }
    if (a.rows > 1) Item("Fill down") { state.fillDown() }
    if (a.cols > 1) Item("Fill right") { state.fillRight() }
    HorizontalDivider()
    val rows = if (a.rows > 1) "${a.rows} rows" else "row"
    val cols = if (a.cols > 1) "${a.cols} columns" else "column"
    Item("Insert $rows above") { state.insertRows(below = false) }
    Item("Insert $rows below") { state.insertRows(below = true) }
    Item("Delete $rows") { state.deleteRows() }
    HorizontalDivider()
    Item("Insert $cols left") { state.insertCols(right = false) }
    Item("Insert $cols right") { state.insertCols(right = true) }
    Item("Delete $cols") { state.deleteCols() }
    HorizontalDivider()
    Item("Sort A → Z") { state.sort(desc = false) }
    Item("Sort Z → A") { state.sort(desc = true) }
}
