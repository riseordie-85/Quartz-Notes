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
        return out.copy(rows = out.rows + n)
    }

    /** Deletes [n] rows from 0-based row [at]. */
    fun deleteRows(sheet: SheetData, at: Int, n: Int): SheetData {
        val change = SheetFormula.deleteChange(true, at, n)
        val out = remap(sheet, { r, c -> if (r < at) r to c else if (r >= at + n) (r - n) to c else null }) {
            SheetFormula.rewriteRefs(it, change)
        }
        return out.copy(rows = maxOf(1, out.rows - n))
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

    /** 0-based rows the filter hides (never row 0, the header). */
    fun hiddenRows(sheet: SheetData, calc: SheetCalc = SheetCalc(sheet)): Set<Int> {
        if (sheet.hide.isEmpty()) return emptySet()
        val hidden = HashSet<Int>()
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
}
