package com.quartz.notes.ui.sheet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.quartz.notes.data.sheet.SheetArea
import com.quartz.notes.data.sheet.SheetCalc
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.SheetGrid
import com.quartz.notes.data.sheet.SheetNumbers
import com.quartz.notes.data.sheet.SheetStyle
import com.quartz.notes.data.sheet.SheetValue
import com.quartz.notes.data.sheet.addr
import com.quartz.notes.data.sheet.colName

/**
 * A sheet page being worked on: the grid, what's selected, the cell being typed in, and undo.
 * Every change makes a new [SheetData], so undo keeps whole sheets.
 */
@Stable
class SheetState(initial: SheetData) {
    var data by mutableStateOf(initial)
        private set

    /** Goes up with every change, for saving. */
    var version by mutableIntStateOf(0)
        private set

    /** Values of [data]; worked out again after a change (and when the clock moves on, for NOW()). */
    var clock by mutableIntStateOf(0)
    val calc: SheetCalc by derivedStateOf { clock.let { SheetCalc(data) } }

    private val undoStack = ArrayDeque<SheetData>()
    private val redoStack = ArrayDeque<SheetData>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Where the selection started, and the cell at its moving corner. */
    var anchor by mutableStateOf(0 to 0)
        private set
    var focus by mutableStateOf(0 to 0)
        private set
    val area: SheetArea get() = SheetArea.of(anchor, focus)

    /** Typing into the selected cell: what's typed so far (null when not typing). */
    var editing by mutableStateOf<TextFieldValue?>(null)
        private set
    var editCell by mutableStateOf(0 to 0)
        private set

    /** A reference put into a formula by tapping a cell: where it sits in the text. */
    private var insertedRef: TextRange? = null

    /** Extra empty rows and columns shown past the grid, as the sheet is scrolled. */
    var extraRows by mutableIntStateOf(0)
    var extraCols by mutableIntStateOf(0)

    /** Puts the keyboard in the formula bar (set by the bar while it's showing). */
    var focusBar: (() -> Unit)? = null

    /** How big the grid is drawn (1 = normal). */
    var zoom by mutableFloatStateOf(1f)

    /** Cells copied in this app, with their styles; pasted as such while the clipboard still has their text. */
    var clip: SheetGrid.Block? = null
    var clipText: String? = null

    val shownRows: Int get() = maxOf(data.rows, data.usedSize.first + 10, focus.first + 10) + extraRows
    val shownCols: Int get() = maxOf(data.cols, data.usedSize.second + 3, focus.second + 3) + extraCols

    // ---- Changes ----

    /** Replaces the sheet, keeping the old one for undo. */
    fun change(next: SheetData) {
        if (next == data) return
        undoStack.addLast(data)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        data = next
        version++
        updateUndo()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(data)
        data = prev
        version++
        updateUndo()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(data)
        data = next
        version++
        updateUndo()
    }

    private fun updateUndo() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** Shows another device's version (nothing here is unsaved). Undo can still go back past it. */
    fun replaceFromRemote(next: SheetData) {
        if (next == data) return
        undoStack.addLast(data)
        redoStack.clear()
        data = next
        updateUndo()
    }

    // ---- Selection ----

    fun select(r: Int, c: Int, extend: Boolean = false) {
        val cell = r.coerceAtLeast(0) to c.coerceAtLeast(0)
        if (!extend) anchor = cell
        focus = cell
    }

    fun selectArea(a: SheetArea) {
        anchor = a.r1 to a.c1
        focus = a.r2 to a.c2
    }

    fun selectRows(r1: Int, r2: Int) {
        anchor = r1 to 0
        focus = r2 to (shownCols - 1)
    }

    fun selectCols(c1: Int, c2: Int) {
        anchor = 0 to c1
        focus = (shownRows - 1) to c2
    }

    fun selectAll() {
        anchor = 0 to 0
        focus = (maxOf(data.usedSize.first, 1) - 1) to (maxOf(data.usedSize.second, 1) - 1)
    }

    /** Moves the selected cell (or, with [extend], the selection's moving corner); hidden rows are skipped. */
    fun moveBy(dr: Int, dc: Int, extend: Boolean = false) {
        val hidden = SheetGrid.hiddenRows(data, calc)
        var r = focus.first
        if (dr != 0) {
            val step = if (dr > 0) 1 else -1
            repeat(kotlin.math.abs(dr)) {
                var next = r + step
                while (next in hidden) next += step
                if (next >= 0) r = next
            }
        }
        val c = (focus.second + dc).coerceAtLeast(0)
        select(r.coerceAtLeast(0), c, extend)
    }

    // ---- Typing ----

    /** What typing in a cell starts from: the formula, or the value in a form that reads back the same. */
    fun editText(r: Int, c: Int): String {
        val cell = data.cell(r, c) ?: return ""
        val v = cell.v ?: return ""
        if (v.startsWith("=") || cell.s.fmt == "text") return v
        val n = (calc.value(r, c) as? SheetValue.Num)?.n ?: return v
        return when (cell.s.fmt) {
            "percent" -> SheetNumbers.general(n * 100) + "%"
            "date", "time", "datetime" -> calc.display(r, c)
            else -> SheetNumbers.general(n)
        }
    }

    /** Starts typing in the selected cell, from [text] (replacing what's there) or from what's there. */
    fun startEdit(text: String? = null) {
        val cell = anchor
        focus = anchor
        editCell = cell
        val t = text ?: editText(cell.first, cell.second)
        editing = TextFieldValue(t, TextRange(t.length))
        insertedRef = null
    }

    fun updateEdit(value: TextFieldValue) {
        if (editing == null) {
            editCell = anchor
        }
        if (value.text != editing?.text) insertedRef = null
        editing = value
    }

    /** Puts what's typed into the cell and moves the selection by [dr], [dc]. */
    fun commitEdit(dr: Int = 1, dc: Int = 0) {
        val text = editing?.text ?: return
        val (r, c) = editCell
        editing = null
        insertedRef = null
        change(SheetGrid.typeInto(data, r, c, closeParens(text)))
        select(r, c)
        if (dr != 0 || dc != 0) moveBy(dr, dc)
    }

    fun cancelEdit() {
        editing = null
        insertedRef = null
        select(editCell.first, editCell.second)
    }

    /** True when tapping a cell now should put its reference into the formula being typed. */
    fun wantsReference(): Boolean {
        val e = editing ?: return false
        if (!e.text.startsWith("=")) return false
        if (insertedRef != null && e.selection.collapsed && e.selection.start == insertedRef!!.end) return true
        val before = e.text.substring(0, e.selection.min).trimEnd()
        return before.isNotEmpty() && before.last() in "=+-*/^&(,:<>"
    }

    /** Puts a cell's (or area's) reference into the formula being typed, replacing the one put in last. */
    fun insertReference(a: SheetArea) {
        val e = editing ?: return
        val ref = a.label()
        val range = insertedRef?.takeIf { e.selection.collapsed && e.selection.start == it.end }
            ?: TextRange(e.selection.min, e.selection.max)
        val text = e.text.substring(0, range.min) + ref + e.text.substring(range.max)
        insertedRef = TextRange(range.min, range.min + ref.length)
        editing = TextFieldValue(text, TextRange(range.min + ref.length))
    }

    // ---- Working on the selection ----

    fun style(edit: (SheetStyle) -> SheetStyle) = change(SheetGrid.updateStyles(data, area, edit))

    /** The style of the selected cell, for showing which buttons are on. */
    val currentStyle: SheetStyle get() = data.cell(anchor.first, anchor.second)?.s ?: SheetStyle.NONE

    fun clearValues() = change(SheetGrid.clear(data, area))

    fun clearAll() = change(SheetGrid.clear(data, area, styles = true))

    fun borders(b: SheetGrid.Borders) = change(SheetGrid.setBorders(data, area, b))

    /** Copies the selection; returns the text for the clipboard. */
    fun copy(): String {
        val a = area
        clip = SheetGrid.copy(data, a)
        val text = SheetGrid.blockText(data, a, calc)
        clipText = text
        return text
    }

    fun cut(): String {
        val text = copy()
        clearAll()
        return text
    }

    /** Pastes at the selected cell: cells copied here (with styles), or text from another app. */
    fun paste(text: String?) {
        val (r, c) = anchor
        val block = clip
        if (block != null && (text == null || text == clipText)) {
            change(SheetGrid.paste(data, block, r, c))
            selectArea(SheetArea(r, c, r + block.rows - 1, c + block.cols - 1))
        } else if (!text.isNullOrEmpty()) {
            change(SheetGrid.pasteText(data, text, r, c))
            val lines = text.trimEnd('\n', '\r').lines()
            selectArea(SheetArea(r, c, r + lines.size - 1, c + (lines.maxOfOrNull { it.split('\t').size } ?: 1) - 1))
        }
    }

    fun fillDown() = change(SheetGrid.fillDown(data, area))

    fun fillRight() = change(SheetGrid.fillRight(data, area))

    fun autoSum() {
        val (next, cell) = SheetGrid.autoSum(data, area, calc)
        change(next)
        select(cell.first, cell.second)
    }

    // ---- Rows and columns ----

    fun insertRows(below: Boolean) {
        val a = area
        change(SheetGrid.insertRows(data, if (below) a.r2 + 1 else a.r1, a.rows))
        if (below) selectArea(SheetArea(a.r2 + 1, a.c1, a.r2 + a.rows, a.c2))
    }

    fun deleteRows() {
        val a = area
        change(SheetGrid.deleteRows(data, a.r1, a.rows))
        select(a.r1, a.c1)
    }

    fun insertCols(right: Boolean) {
        val a = area
        change(SheetGrid.insertCols(data, if (right) a.c2 + 1 else a.c1, a.cols))
        if (right) selectArea(SheetArea(a.r1, a.c2 + 1, a.r2, a.c2 + a.cols))
    }

    fun deleteCols() {
        val a = area
        change(SheetGrid.deleteCols(data, a.c1, a.cols))
        select(a.r1, a.c1)
    }

    fun setWidth(c: Int, width: Float) {
        val w = width.coerceIn(36f, 600f)
        change(data.copy(widths = data.widths + (colName(c) to w)))
    }

    // ---- Sorting, filtering, tables ----

    /**
     * Sorts by the selected column: the selected rows when several are selected, otherwise every row
     * under the header row.
     */
    fun sort(desc: Boolean) {
        val a = area
        val used = data.usedSize.first
        val (from, to) = if (a.rows > 1 && a.r2 < shownRows - 1) a.r1 to minOf(a.r2, used - 1) else 1 to used - 1
        if (to <= from) return
        change(SheetGrid.sortRows(data, a.c1, desc, from, to))
    }

    /** Hides rows whose [col] shows one of [hidden]; an empty list clears that column's filter. */
    fun filter(col: Int, hidden: List<String>) {
        val key = colName(col)
        change(data.copy(hide = if (hidden.isEmpty()) data.hide - key else data.hide + (key to hidden)))
    }

    fun clearFilters() = change(data.copy(hide = emptyMap()))

    fun table(style: String?) = change(data.copy(table = style))

    /** "B3" or "B3:D7" for the selection. */
    val label: String get() = area.label()

    companion object {
        /** A formula with its unclosed brackets closed, as people often leave them. */
        fun closeParens(text: String): String {
            if (!text.startsWith("=")) return text
            var depth = 0
            var quoted = false
            for (ch in text) {
                when {
                    ch == '"' -> quoted = !quoted
                    quoted -> {}
                    ch == '(' -> depth++
                    ch == ')' -> depth--
                }
            }
            return if (depth > 0 && !quoted) text + ")".repeat(depth) else text
        }

        /** Default places shown for each format. */
        fun defaultPlaces(fmt: String?): Int = when (fmt) {
            "number", "currency" -> 2
            else -> 0
        }

        fun cellName(r: Int, c: Int) = addr(r, c)
    }
}
