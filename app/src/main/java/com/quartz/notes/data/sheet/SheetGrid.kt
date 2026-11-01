package com.quartz.notes.data.sheet

// Changing a sheet's grid: typing, inserting and deleting rows and columns, copying formulas,
// sorting, the filter's hidden rows, and the text used for search. Kept in step with the web
// app's web/src/lib/sheet/grid.ts; see docs/sheet-format.md.
object SheetGrid {
    private fun MutableMap<String, SheetCell>.putCell(key: String, cell: SheetCell?) {
        if (cell == null || cell.isEmpty) remove(key) else put(key, cell)
    }

    /** The sheet after typing [text] into the cell at 0-based row and column. */
    fun typeInto(sheet: SheetData, r: Int, c: Int, text: String): SheetData {
        val key = addr(r, c)
        val old = sheet.cells[key] ?: SheetCell()
        val cell = if (old.s.fmt == "text") {
            old.copy(v = text.trim().ifEmpty { null })
        } else {
            val typed = SheetNumbers.parseInput(text)
            old.copy(v = typed.v, s = if (typed.fmt != null) old.s.copy(fmt = typed.fmt) else old.s)
        }
        val cells = LinkedHashMap(sheet.cells)
        cells.putCell(key, cell)
        return grow(sheet.copy(cells = cells), r, c)
    }

    /** Makes the grid at least one row and column bigger than the cell at r, c. */
    fun grow(sheet: SheetData, r: Int, c: Int): SheetData =
        sheet.copy(rows = maxOf(sheet.rows, r + 2), cols = maxOf(sheet.cols, c + 2))

    /** Every formula rewritten, cells moved to new places by [move] (null drops a cell). */
    private fun remap(sheet: SheetData, move: (Int, Int) -> Pair<Int, Int>?, rewrite: (String) -> String): SheetData {
        val cells = LinkedHashMap<String, SheetCell>()
        for ((key, cell) in sheet.cells) {
            val at = parseAddr(key) ?: continue
            val to = move(at.first, at.second) ?: continue
            val v = cell.v
            val moved = if (v != null && v.startsWith("=") && cell.s.fmt != "text") cell.copy(v = rewrite(v)) else cell
            cells[addr(to.first, to.second)] = moved
        }
        return sheet.copy(cells = cells)
    }

    /** Inserts [n] empty rows before 0-based row [at]. */
    fun insertRows(sheet: SheetData, at: Int, n: Int): SheetData {
        val change = SheetFormula.insertChange(true, at, n)
        val out = remap(sheet, { r, c -> (if (r >= at) r + n else r) to c }) { SheetFormula.rewriteRefs(it, change) }
        return out.copy(rows = out.rows + n, hiddenRows = sheet.hiddenRows.map { if (it >= at) it + n else it }.toSet())
    }

    /** Deletes [n] rows from 0-based row [at]. */
    fun deleteRows(sheet: SheetData, at: Int, n: Int): SheetData {
        val change = SheetFormula.deleteChange(true, at, n)
        val out = remap(sheet, { r, c -> if (r < at) r to c else if (r >= at + n) (r - n) to c else null }) {
            SheetFormula.rewriteRefs(it, change)
        }
        return out.copy(
            rows = maxOf(1, out.rows - n),
            hiddenRows = sheet.hiddenRows.mapNotNull { if (it < at) it else if (it >= at + n) it - n else null }.toSet(),
        )
    }

    private fun <T> moveColumnKeys(rec: Map<String, T>, move: (Int) -> Int?): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        for ((k, v) in rec) {
            val to = move(colIndex(k)) ?: continue
            out[colName(to)] = v
        }
        return out
    }

    /** Inserts [n] empty columns before 0-based column [at]. */
    fun insertCols(sheet: SheetData, at: Int, n: Int): SheetData {
        val change = SheetFormula.insertChange(false, at, n)
        val move = { c: Int -> if (c >= at) c + n else c }
        val out = remap(sheet, { r, c -> r to move(c) }) { SheetFormula.rewriteRefs(it, change) }
        return out.copy(
            cols = out.cols + n,
            widths = moveColumnKeys(sheet.widths, move),
            hide = moveColumnKeys(sheet.hide, move),
            hiddenCols = moveColumnKeys(sheet.hiddenCols.associateWith { true }, move).keys,
        )
    }

    /** Deletes [n] columns from 0-based column [at]. */
    fun deleteCols(sheet: SheetData, at: Int, n: Int): SheetData {
        val change = SheetFormula.deleteChange(false, at, n)
        val move = { c: Int -> if (c < at) c else if (c >= at + n) c - n else null }
        val out = remap(sheet, { r, c -> move(c)?.let { r to it } }) { SheetFormula.rewriteRefs(it, change) }
        return out.copy(
            cols = maxOf(1, out.cols - n),
            widths = moveColumnKeys(sheet.widths, move),
            hide = moveColumnKeys(sheet.hide, move),
            hiddenCols = moveColumnKeys(sheet.hiddenCols.associateWith { true }, move).keys,
        )
    }

    /** What a cell's typed value becomes when copied [dr] rows down and [dc] columns across. */
    fun shiftFormula(v: String, dr: Int, dc: Int): String =
        if (v.startsWith("=")) SheetFormula.rewriteRefs(v, SheetFormula.shiftChange(dr, dc)) else v

    /** Where a value sorts: numbers, text, TRUE/FALSE, errors, then empty. */
    private fun sortRank(v: SheetValue) = when (v) {
        is SheetValue.Num -> 0
        is SheetValue.Str -> 1
        is SheetValue.Bool -> 2
        is SheetValue.Err -> 3
        SheetValue.Empty -> 4
    }

    /** Sorts 0-based rows [from]..[to] (inclusive) by column [col]: whole rows move, formulas unchanged. */
    fun sortRows(sheet: SheetData, col: Int, desc: Boolean, from: Int, to: Int, now: Double? = null): SheetData {
        val calc = SheetCalc(sheet, now)
        val width = maxOf(sheet.usedSize.second, sheet.cols)
        class Row(val key: SheetValue, val cells: List<SheetCell?>)
        val rows = (from..to).map { r -> Row(calc.value(r, col), (0 until width).map { c -> sheet.cells[addr(r, c)] }) }
        val dir = if (desc) -1 else 1
        // sortedWith is stable, so equal values keep their order.
        val order = rows.sortedWith { x, y ->
            val a = x.key
            val b = y.key
            val ra = sortRank(a)
            val rb = sortRank(b)
            if (ra != rb) return@sortedWith ra - rb
            val c = when {
                a is SheetValue.Num && b is SheetValue.Num -> a.n.compareTo(b.n).coerceIn(-1, 1)
                a is SheetValue.Str && b is SheetValue.Str -> a.s.lowercase().compareTo(b.s.lowercase()).coerceIn(-1, 1)
                a is SheetValue.Bool && b is SheetValue.Bool -> if (a.b == b.b) 0 else if (a.b) 1 else -1
                else -> 0
            }
            c * dir
        }
        val cells = LinkedHashMap(sheet.cells)
        order.forEachIndexed { i, row ->
            val r = from + i
            row.cells.forEachIndexed { c, cell -> cells.putCell(addr(r, c), cell) }
        }
        return sheet.copy(cells = cells)
    }

    /** 0-based rows hidden: by hand, and by the filter (which never hides row 0, the header). */
    fun hiddenRows(sheet: SheetData, calc: SheetCalc = SheetCalc(sheet)): Set<Int> {
        if (sheet.hide.isEmpty()) return sheet.hiddenRows
        val hidden = HashSet<Int>(sheet.hiddenRows)
        val filters = sheet.hide.map { (letters, values) -> colIndex(letters) to values.toSet() }
        for (r in 1 until sheet.usedSize.first) {
            if (filters.any { (c, values) -> calc.display(r, c) in values }) hidden += r
        }
        return hidden
    }

    /** The sheet as text for search: shown values, tabs between cells, line breaks between rows. */
    fun plainText(sheet: SheetData, now: Double? = null): String {
        val calc = SheetCalc(sheet, now)
        val (rows, cols) = sheet.usedSize
        val lines = (0 until rows).map { r ->
            val cells = (0 until cols).map { c -> calc.display(r, c) }.toMutableList()
            while (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.size - 1)
            cells.joinToString("\t")
        }.toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines.joinToString("\n")
    }

    // ---- Working on a selected area ----

    /** The sheet with [change] applied to the style of every cell in [area]. */
    fun updateStyles(sheet: SheetData, area: SheetArea, change: (SheetStyle) -> SheetStyle): SheetData {
        val cells = LinkedHashMap(sheet.cells)
        area.forEach { r, c ->
            val key = addr(r, c)
            val old = cells[key] ?: SheetCell()
            cells.putCell(key, old.copy(s = change(old.s)))
        }
        return sheet.copy(cells = cells)
    }

    /** Clears what's typed in [area] (styles stay unless [styles]). */
    fun clear(sheet: SheetData, area: SheetArea, styles: Boolean = false): SheetData {
        val cells = LinkedHashMap(sheet.cells)
        area.forEach { r, c ->
            val key = addr(r, c)
            val old = cells[key] ?: return@forEach
            cells.putCell(key, if (styles) null else old.copy(v = null))
        }
        return sheet.copy(cells = cells)
    }

    /** Borders for an area: every side of every cell, the outside edge, one side, or none. */
    enum class Borders { ALL, OUTLINE, TOP, BOTTOM, LEFT, RIGHT, NONE }

    fun setBorders(sheet: SheetData, area: SheetArea, borders: Borders): SheetData {
        val cells = LinkedHashMap(sheet.cells)
        area.forEach { r, c ->
            val key = addr(r, c)
            val old = cells[key] ?: SheetCell()
            val sides = old.s.border.orEmpty().toHashSet()
            when (borders) {
                Borders.ALL -> sides.addAll(listOf('t', 'r', 'b', 'l'))
                Borders.NONE -> sides.clear()
                Borders.OUTLINE -> {
                    if (r == area.r1) sides += 't'
                    if (r == area.r2) sides += 'b'
                    if (c == area.c1) sides += 'l'
                    if (c == area.c2) sides += 'r'
                }
                Borders.TOP -> if (r == area.r1) sides += 't'
                Borders.BOTTOM -> if (r == area.r2) sides += 'b'
                Borders.LEFT -> if (c == area.c1) sides += 'l'
                Borders.RIGHT -> if (c == area.c2) sides += 'r'
            }
            val text = "trbl".filter { it in sides }.ifEmpty { null }
            cells.putCell(key, old.copy(s = old.s.copy(border = text)))
        }
        return sheet.copy(cells = cells)
    }

    /** Cells copied from a sheet: their typed values and styles, and where they came from. */
    data class Block(val r0: Int, val c0: Int, val rows: Int, val cols: Int, val cells: List<SheetCell?>) {
        fun at(dr: Int, dc: Int): SheetCell? = cells[dr * cols + dc]
    }

    fun copy(sheet: SheetData, area: SheetArea): Block {
        val cells = ArrayList<SheetCell?>()
        for (r in area.r1..area.r2) for (c in area.c1..area.c2) cells += sheet.cells[addr(r, c)]
        return Block(area.r1, area.c1, area.rows, area.cols, cells)
    }

    /** The block's shown values as tab-separated text, for other apps. */
    fun blockText(sheet: SheetData, area: SheetArea, calc: SheetCalc = SheetCalc(sheet)): String =
        (area.r1..area.r2).joinToString("\n") { r -> (area.c1..area.c2).joinToString("\t") { c -> calc.display(r, c) } }

    /** Pastes [block] with its top-left at 0-based [r], [c]; formulas move with it. */
    fun paste(sheet: SheetData, block: Block, r: Int, c: Int): SheetData {
        val cells = LinkedHashMap(sheet.cells)
        val dr = r - block.r0
        val dc = c - block.c0
        for (i in 0 until block.rows) for (j in 0 until block.cols) {
            val cell = block.at(i, j)
            val moved = cell?.v?.let { v -> if (cell.s.fmt == "text") cell else cell.copy(v = shiftFormula(v, dr, dc)) } ?: cell
            cells.putCell(addr(r + i, c + j), moved)
        }
        return grow(sheet.copy(cells = cells), r + block.rows - 1, c + block.cols - 1)
    }

    /** Pastes text from another app: lines are rows, tabs split cells; each is typed in. */
    fun pasteText(sheet: SheetData, text: String, r: Int, c: Int): SheetData {
        var out = sheet
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n').split('\n')
        lines.forEachIndexed { i, line ->
            line.split('\t').forEachIndexed { j, cell -> out = typeInto(out, r + i, c + j, cell) }
        }
        return out
    }

    /** Copies the top row of [area] down through it (formulas move with each row). */
    fun fillDown(sheet: SheetData, area: SheetArea): SheetData {
        if (area.rows < 2) return sheet
        val top = copy(sheet, SheetArea(area.r1, area.c1, area.r1, area.c2))
        var out = sheet
        for (r in area.r1 + 1..area.r2) out = paste(out, top, r, area.c1)
        return out
    }

    /** Copies the left column of [area] across it. */
    fun fillRight(sheet: SheetData, area: SheetArea): SheetData {
        if (area.cols < 2) return sheet
        val left = copy(sheet, SheetArea(area.r1, area.c1, area.r2, area.c1))
        var out = sheet
        for (c in area.c1 + 1..area.c2) out = paste(out, left, area.r1, c)
        return out
    }

    /**
     * AutoSum. One cell: a total of the numbers just above it (or else just left of it). Several
     * cells: a total under each column, in the row below. Returns the sheet and the cell to select.
     */
    fun autoSum(sheet: SheetData, area: SheetArea, calc: SheetCalc = SheetCalc(sheet)): Pair<SheetData, Pair<Int, Int>> {
        fun isNum(r: Int, c: Int) = r >= 0 && c >= 0 && calc.value(r, c) is SheetValue.Num
        if (area.rows == 1 && area.cols == 1) {
            val r = area.r1
            val c = area.c1
            var top = r
            while (isNum(top - 1, c)) top--
            if (top < r) return typeInto(sheet, r, c, "=SUM(${addr(top, c)}:${addr(r - 1, c)})") to (r to c)
            var left = c
            while (isNum(r, left - 1)) left--
            if (left < c) return typeInto(sheet, r, c, "=SUM(${addr(r, left)}:${addr(r, c - 1)})") to (r to c)
            return typeInto(sheet, r, c, "=SUM()") to (r to c)
        }
        var out = sheet
        val below = area.r2 + 1
        for (c in area.c1..area.c2) {
            out = typeInto(out, below, c, "=SUM(${addr(area.r1, c)}:${addr(area.r2, c)})")
        }
        return out to (below to area.c1)
    }

    /** Sum, average and count of the numbers in [area], or null when it holds none. */
    data class Stats(val sum: Double, val average: Double, val count: Int)

    fun stats(calc: SheetCalc, area: SheetArea, used: Pair<Int, Int>): Stats? {
        var sum = 0.0
        var count = 0
        val rEnd = minOf(area.r2, used.first - 1)
        val cEnd = minOf(area.c2, used.second - 1)
        for (r in area.r1..rEnd) for (c in area.c1..cEnd) {
            val v = calc.value(r, c)
            if (v is SheetValue.Num) {
                sum += v.n
                count++
            }
        }
        return if (count == 0) null else Stats(sum, sum / count, count)
    }

    /** Shown values in a column's data rows (row 2 down), for the filter's list. */
    fun columnValues(sheet: SheetData, col: Int, calc: SheetCalc = SheetCalc(sheet)): List<String> {
        val seen = LinkedHashSet<String>()
        for (r in 1 until sheet.usedSize.first) seen += calc.display(r, col)
        return seen.sortedWith(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })
    }

    // ---- Hiding rows and columns ----

    fun hideCols(sheet: SheetData, c1: Int, c2: Int): SheetData = sheet.copy(hiddenCols = sheet.hiddenCols + (c1..c2).map(::colName))

    /** Shows the hidden columns from [c1] to [c2]. */
    fun unhideCols(sheet: SheetData, c1: Int, c2: Int): SheetData = sheet.copy(hiddenCols = sheet.hiddenCols.filter { colIndex(it) !in c1..c2 }.toSet())

    fun hideRows(sheet: SheetData, r1: Int, r2: Int): SheetData = sheet.copy(hiddenRows = sheet.hiddenRows + (r1..r2))

    /** Shows the hidden rows from [r1] to [r2]. */
    fun unhideRows(sheet: SheetData, r1: Int, r2: Int): SheetData = sheet.copy(hiddenRows = sheet.hiddenRows.filter { it !in r1..r2 }.toSet())
}
