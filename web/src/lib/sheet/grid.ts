// Changing a sheet's grid: typing, inserting and deleting rows and columns, copying formulas,
// sorting, the filter's hidden rows, and the text used for search. Kept in step with the app's
// data/sheet/SheetGrid.kt; see docs/sheet-format.md.

import { addr, colIndex, colName, handHiddenRows, parseAddr, type SheetCell, type SheetData, type SheetStyle, usedSize } from "./model"
import { deleteChange, insertChange, rewriteRefs, shiftChange } from "./formula"
import { SheetCalc, type CalcOptions, type Value } from "./calc"
import { parseInput } from "./numbers"

function clone(sheet: SheetData): SheetData {
  return JSON.parse(JSON.stringify(sheet)) as SheetData
}

function putCell(cells: Record<string, SheetCell>, key: string, cell: SheetCell | undefined) {
  if (!cell || (cell.v === undefined && (!cell.s || Object.keys(cell.s).length === 0))) delete cells[key]
  else cells[key] = cell
}

/** The sheet after typing `text` into the cell at 0-based row and column. */
export function typeInto(sheet: SheetData, r: number, c: number, text: string): SheetData {
  const out = clone(sheet)
  const key = addr(r, c)
  const cell: SheetCell = { ...(out.cells[key] ?? {}) }
  const style: SheetStyle = { ...(cell.s ?? {}) }
  if (style.fmt === "text") {
    const t = text.trim()
    cell.v = t === "" ? undefined : t
  } else {
    const typed = parseInput(text)
    cell.v = typed.v ?? undefined
    if (typed.fmt) style.fmt = typed.fmt
  }
  if (cell.v === undefined) delete cell.v
  cell.s = Object.keys(style).length ? style : undefined
  if (!cell.s) delete cell.s
  putCell(out.cells, key, cell)
  return grow(out, r, c)
}

/** Makes the grid at least one row and column bigger than the cell at r, c. */
export function grow(sheet: SheetData, r: number, c: number): SheetData {
  if (sheet.rows < r + 2) sheet.rows = r + 2
  if (sheet.cols < c + 2) sheet.cols = c + 2
  return sheet
}

/** Every formula in the sheet rewritten, cells moved to new places by `move` (null drops a cell). */
function remap(
  sheet: SheetData,
  move: (r: number, c: number) => [number, number] | null,
  rewrite: (formula: string) => string,
): SheetData {
  const out = clone(sheet)
  const cells: Record<string, SheetCell> = {}
  for (const [key, cell] of Object.entries(sheet.cells)) {
    const at = parseAddr(key)
    if (!at) continue
    const to = move(at[0], at[1])
    if (!to) continue
    const moved: SheetCell = { ...cell }
    if (moved.v?.startsWith("=") && moved.s?.fmt !== "text") moved.v = rewrite(moved.v)
    cells[addr(to[0], to[1])] = moved
  }
  out.cells = cells
  return out
}

/** [sheet] with these hand-hidden columns and rows (0-based rows), leaving out what's empty. */
function withHidden(sheet: SheetData, cols: string[], rows: number[]): SheetData {
  const out = { ...sheet }
  const c = [...new Set(cols)].sort((a, b) => colIndex(a) - colIndex(b))
  const r = [...new Set(rows)].filter((x) => x >= 0).sort((a, b) => a - b).map((x) => x + 1)
  if (c.length || r.length) out.hidden = { ...(c.length ? { cols: c } : {}), ...(r.length ? { rows: r } : {}) }
  else delete out.hidden
  return out
}

/** Inserts `n` empty rows before 0-based row `at`. */
export function insertRows(sheet: SheetData, at: number, n: number): SheetData {
  const change = insertChange("r", at, n)
  const out = remap(sheet, (r, c) => [r >= at ? r + n : r, c], (f) => rewriteRefs(f, change))
  out.rows += n
  return withHidden(out, out.hidden?.cols ?? [], [...handHiddenRows(sheet)].map((r) => (r >= at ? r + n : r)))
}

/** Deletes `n` rows from 0-based row `at`. */
export function deleteRows(sheet: SheetData, at: number, n: number): SheetData {
  const change = deleteChange("r", at, n)
  const out = remap(
    sheet,
    (r, c) => (r < at ? [r, c] : r >= at + n ? [r - n, c] : null),
    (f) => rewriteRefs(f, change),
  )
  out.rows = Math.max(1, out.rows - n)
  const rows = [...handHiddenRows(sheet)].flatMap((r) => (r < at ? [r] : r >= at + n ? [r - n] : []))
  return withHidden(out, out.hidden?.cols ?? [], rows)
}

function moveColumnKeys<T>(rec: Record<string, T> | undefined, move: (c: number) => number | null): Record<string, T> {
  const out: Record<string, T> = {}
  for (const [k, v] of Object.entries(rec ?? {})) {
    const to = move(colIndex(k))
    if (to !== null) out[colName(to)] = v
  }
  return out
}

/** Inserts `n` empty columns before 0-based column `at`. */
export function insertCols(sheet: SheetData, at: number, n: number): SheetData {
  const change = insertChange("c", at, n)
  const move = (c: number) => (c >= at ? c + n : c)
  const out = remap(sheet, (r, c) => [r, move(c)], (f) => rewriteRefs(f, change))
  out.cols += n
  out.widths = moveColumnKeys(sheet.widths, move)
  if (sheet.hide) out.hide = moveColumnKeys(sheet.hide, move)
  return withHidden(out, Object.keys(moveColumnKeys(Object.fromEntries((sheet.hidden?.cols ?? []).map((k) => [k, true])), move)), [...handHiddenRows(sheet)])
}

/** Deletes `n` columns from 0-based column `at`. */
export function deleteCols(sheet: SheetData, at: number, n: number): SheetData {
  const change = deleteChange("c", at, n)
  const move = (c: number) => (c < at ? c : c >= at + n ? c - n : null)
  const out = remap(sheet, (r, c) => {
    const to = move(c)
    return to === null ? null : [r, to]
  }, (f) => rewriteRefs(f, change))
  out.cols = Math.max(1, out.cols - n)
  out.widths = moveColumnKeys(sheet.widths, move)
  if (sheet.hide) {
    out.hide = moveColumnKeys(sheet.hide, move)
    if (Object.keys(out.hide).length === 0) delete out.hide
  }
  return withHidden(out, Object.keys(moveColumnKeys(Object.fromEntries((sheet.hidden?.cols ?? []).map((k) => [k, true])), move)), [...handHiddenRows(sheet)])
}

/** What a cell's typed value becomes when copied `dr` rows down and `dc` columns across. */
export function shiftFormula(v: string, dr: number, dc: number): string {
  return v.startsWith("=") ? rewriteRefs(v, shiftChange(dr, dc)) : v
}

/** Where a value sorts: numbers, text, TRUE/FALSE, errors, then empty. */
function sortRank(v: Value): number {
  return v.k === "num" ? 0 : v.k === "str" ? 1 : v.k === "bool" ? 2 : v.k === "err" ? 3 : 4
}

/**
 * Sorts 0-based rows `from`..`to` (inclusive) by column `col`: whole rows move, formulas unchanged.
 */
export function sortRows(sheet: SheetData, col: number, desc: boolean, from: number, to: number, options: CalcOptions = {}): SheetData {
  const calc = new SheetCalc(sheet, options)
  const [, usedCols] = usedSize(sheet)
  const width = Math.max(usedCols, sheet.cols)
  const rows: { key: Value; cells: (SheetCell | undefined)[] }[] = []
  for (let r = from; r <= to; r++) {
    const cells: (SheetCell | undefined)[] = []
    for (let c = 0; c < width; c++) cells.push(sheet.cells[addr(r, c)])
    rows.push({ key: calc.value(r, col), cells })
  }
  const dir = desc ? -1 : 1
  const order = rows
    .map((row, i) => ({ row, i }))
    .sort((x, y) => {
      const a = x.row.key
      const b = y.row.key
      const ra = sortRank(a)
      const rb = sortRank(b)
      if (ra !== rb) return ra - rb
      let c = 0
      if (a.k === "num" && b.k === "num") c = a.n === b.n ? 0 : a.n < b.n ? -1 : 1
      else if (a.k === "str" && b.k === "str") {
        const x2 = a.s.toLowerCase()
        const y2 = b.s.toLowerCase()
        c = x2 === y2 ? 0 : x2 < y2 ? -1 : 1
      } else if (a.k === "bool" && b.k === "bool") c = a.b === b.b ? 0 : a.b ? 1 : -1
      return c !== 0 ? c * dir : x.i - y.i
    })
  const out = clone(sheet)
  order.forEach(({ row }, i) => {
    const r = from + i
    row.cells.forEach((cell, c) => putCell(out.cells, addr(r, c), cell ? { ...cell } : undefined))
  })
  return out
}

/** 0-based rows hidden: by hand, and by the filter (which never hides row 0, the header). */
export function hiddenRows(sheet: SheetData, calc: SheetCalc = new SheetCalc(sheet)): Set<number> {
  const hidden = handHiddenRows(sheet)
  const filters = Object.entries(sheet.hide ?? {})
  if (!filters.length) return hidden
  const [usedRows] = usedSize(sheet)
  for (let r = 1; r < usedRows; r++) {
    for (const [letters, values] of filters) {
      if (values.includes(calc.display(r, colIndex(letters)))) {
        hidden.add(r)
        break
      }
    }
  }
  return hidden
}

/** The sheet as text for search: shown values, tabs between cells, line breaks between rows. */
export function plainText(sheet: SheetData, options: CalcOptions = {}): string {
  const calc = new SheetCalc(sheet, options)
  const [rows, cols] = usedSize(sheet)
  const lines: string[] = []
  for (let r = 0; r < rows; r++) {
    const cells: string[] = []
    for (let c = 0; c < cols; c++) cells.push(calc.display(r, c))
    while (cells.length && cells[cells.length - 1] === "") cells.pop()
    lines.push(cells.join("\t"))
  }
  while (lines.length && lines[lines.length - 1] === "") lines.pop()
  return lines.join("\n")
}

// ---- Working on a selected area ----

/** A rectangle of cells, 0-based and inclusive, corners in order. */
export interface Area {
  r1: number
  c1: number
  r2: number
  c2: number
}

export function areaOf(a: [number, number], b: [number, number]): Area {
  return { r1: Math.min(a[0], b[0]), c1: Math.min(a[1], b[1]), r2: Math.max(a[0], b[0]), c2: Math.max(a[1], b[1]) }
}

/** "B2" or "B2:D9". */
export function areaLabel(a: Area): string {
  const one = addr(a.r1, a.c1)
  return a.r1 === a.r2 && a.c1 === a.c2 ? one : one + ":" + addr(a.r2, a.c2)
}

/** A style with its unset parts left out. */
export function cleanStyle(s: SheetStyle): SheetStyle | undefined {
  const out: SheetStyle = {}
  for (const [k, v] of Object.entries(s) as [keyof SheetStyle, unknown][]) {
    if (v === undefined || v === null || v === false || v === "") continue
    ;(out as Record<string, unknown>)[k] = v
  }
  return Object.keys(out).length ? out : undefined
}

/** The sheet with [change] applied to the style of every cell in [area]. */
export function updateStyles(sheet: SheetData, area: Area, change: (s: SheetStyle) => SheetStyle): SheetData {
  const out = clone(sheet)
  for (let r = area.r1; r <= area.r2; r++) {
    for (let c = area.c1; c <= area.c2; c++) {
      const key = addr(r, c)
      const old = out.cells[key] ?? {}
      const s = cleanStyle(change({ ...(old.s ?? {}) }))
      const cell: SheetCell = { ...old }
      if (s) cell.s = s
      else delete cell.s
      putCell(out.cells, key, cell)
    }
  }
  return out
}

/** Clears what's typed in [area] (styles stay unless [styles]). */
export function clearArea(sheet: SheetData, area: Area, styles = false): SheetData {
  const out = clone(sheet)
  for (let r = area.r1; r <= area.r2; r++) {
    for (let c = area.c1; c <= area.c2; c++) {
      const key = addr(r, c)
      const old = out.cells[key]
      if (!old) continue
      if (styles) delete out.cells[key]
      else {
        const cell = { ...old }
        delete cell.v
        putCell(out.cells, key, cell)
      }
    }
  }
  return out
}

export type Borders = "all" | "outline" | "top" | "bottom" | "left" | "right" | "none"

export function setBorders(sheet: SheetData, area: Area, b: Borders): SheetData {
  const out = clone(sheet)
  for (let r = area.r1; r <= area.r2; r++) {
    for (let c = area.c1; c <= area.c2; c++) {
      const key = addr(r, c)
      const old = out.cells[key] ?? {}
      const sides = new Set((old.s?.border ?? "").split(""))
      if (b === "all") "trbl".split("").forEach((x) => sides.add(x))
      else if (b === "none") sides.clear()
      else {
        if ((b === "outline" || b === "top") && r === area.r1) sides.add("t")
        if ((b === "outline" || b === "bottom") && r === area.r2) sides.add("b")
        if ((b === "outline" || b === "left") && c === area.c1) sides.add("l")
        if ((b === "outline" || b === "right") && c === area.c2) sides.add("r")
      }
      const border = "trbl".split("").filter((x) => sides.has(x)).join("")
      const s = cleanStyle({ ...(old.s ?? {}), border })
      const cell: SheetCell = { ...old }
      if (s) cell.s = s
      else delete cell.s
      putCell(out.cells, key, cell)
    }
  }
  return out
}

/** Cells copied from a sheet: their typed values and styles, and where they came from. */
export interface Block {
  r0: number
  c0: number
  rows: number
  cols: number
  cells: (SheetCell | undefined)[]
}

export function copyBlock(sheet: SheetData, area: Area): Block {
  const cells: (SheetCell | undefined)[] = []
  for (let r = area.r1; r <= area.r2; r++) for (let c = area.c1; c <= area.c2; c++) cells.push(sheet.cells[addr(r, c)])
  return { r0: area.r1, c0: area.c1, rows: area.r2 - area.r1 + 1, cols: area.c2 - area.c1 + 1, cells: JSON.parse(JSON.stringify(cells.map((x) => x ?? null))).map((x: SheetCell | null) => x ?? undefined) }
}

/** The area's shown values as tab-separated text, for other apps. */
export function blockText(sheet: SheetData, area: Area, calc: SheetCalc = new SheetCalc(sheet)): string {
  const lines: string[] = []
  for (let r = area.r1; r <= area.r2; r++) {
    const row: string[] = []
    for (let c = area.c1; c <= area.c2; c++) row.push(calc.display(r, c))
    lines.push(row.join("\t"))
  }
  return lines.join("\n")
}

/** Pastes [block] with its top-left at 0-based r, c; formulas move with it. */
export function pasteBlock(sheet: SheetData, block: Block, r: number, c: number): SheetData {
  const out = clone(sheet)
  const dr = r - block.r0
  const dc = c - block.c0
  for (let i = 0; i < block.rows; i++) {
    for (let j = 0; j < block.cols; j++) {
      const cell = block.cells[i * block.cols + j]
      let moved = cell ? { ...cell } : undefined
      if (moved?.v !== undefined && moved.s?.fmt !== "text") moved.v = shiftFormula(moved.v, dr, dc)
      putCell(out.cells, addr(r + i, c + j), moved)
    }
  }
  return grow(out, r + block.rows - 1, c + block.cols - 1)
}

/** Pastes text from another app: lines are rows, tabs split cells; each is typed in. */
export function pasteText(sheet: SheetData, text: string, r: number, c: number): SheetData {
  let out = sheet
  const lines = text.replace(/\r\n?/g, "\n").replace(/\n+$/, "").split("\n")
  lines.forEach((line, i) => line.split("\t").forEach((cell, j) => (out = typeInto(out, r + i, c + j, cell))))
  return out
}

export function fillDown(sheet: SheetData, area: Area): SheetData {
  if (area.r2 <= area.r1) return sheet
  const top = copyBlock(sheet, { ...area, r2: area.r1 })
  let out = sheet
  for (let r = area.r1 + 1; r <= area.r2; r++) out = pasteBlock(out, top, r, area.c1)
  return out
}

export function fillRight(sheet: SheetData, area: Area): SheetData {
  if (area.c2 <= area.c1) return sheet
  const left = copyBlock(sheet, { ...area, c2: area.c1 })
  let out = sheet
  for (let c = area.c1 + 1; c <= area.c2; c++) out = pasteBlock(out, left, area.r1, c)
  return out
}

/**
 * AutoSum. One cell: a total of the numbers just above it (or else just left of it). Several
 * cells: a total under each column, in the row below. Returns the sheet and the cell to select.
 */
export function autoSum(sheet: SheetData, area: Area, calc: SheetCalc = new SheetCalc(sheet)): [SheetData, [number, number]] {
  const isNum = (r: number, c: number) => r >= 0 && c >= 0 && calc.value(r, c).k === "num"
  if (area.r1 === area.r2 && area.c1 === area.c2) {
    const { r1: r, c1: c } = area
    let top = r
    while (isNum(top - 1, c)) top--
    if (top < r) return [typeInto(sheet, r, c, `=SUM(${addr(top, c)}:${addr(r - 1, c)})`), [r, c]]
    let left = c
    while (isNum(r, left - 1)) left--
    if (left < c) return [typeInto(sheet, r, c, `=SUM(${addr(r, left)}:${addr(r, c - 1)})`), [r, c]]
    return [typeInto(sheet, r, c, "=SUM()"), [r, c]]
  }
  let out = sheet
  const below = area.r2 + 1
  for (let c = area.c1; c <= area.c2; c++) out = typeInto(out, below, c, `=SUM(${addr(area.r1, c)}:${addr(area.r2, c)})`)
  return [out, [below, area.c1]]
}

/** Sum, average and count of the numbers in [area], or null when it holds none. */
export function areaStats(sheet: SheetData, calc: SheetCalc, area: Area): { sum: number; average: number; count: number } | null {
  const [rows, cols] = usedSize(sheet)
  let sum = 0
  let count = 0
  for (let r = area.r1; r <= Math.min(area.r2, rows - 1); r++) {
    for (let c = area.c1; c <= Math.min(area.c2, cols - 1); c++) {
      const v = calc.value(r, c)
      if (v.k === "num") {
        sum += v.n
        count++
      }
    }
  }
  return count ? { sum, average: sum / count, count } : null
}

/** Shown values in a column's data rows (row 2 down), for the filter's list. */
export function columnValues(sheet: SheetData, col: number, calc: SheetCalc = new SheetCalc(sheet)): string[] {
  const seen = new Set<string>()
  const [rows] = usedSize(sheet)
  for (let r = 1; r < rows; r++) seen.add(calc.display(r, col))
  return [...seen].sort((a, b) => (a === "" ? 1 : 0) - (b === "" ? 1 : 0) || (a.toLowerCase() < b.toLowerCase() ? -1 : a.toLowerCase() > b.toLowerCase() ? 1 : 0))
}

/** A formula with its unclosed brackets closed, as people often leave them. */
export function closeParens(text: string): string {
  if (!text.startsWith("=")) return text
  let depth = 0
  let quoted = false
  for (const ch of text) {
    if (ch === '"') quoted = !quoted
    else if (!quoted && ch === "(") depth++
    else if (!quoted && ch === ")") depth--
  }
  return depth > 0 && !quoted ? text + ")".repeat(depth) : text
}

/** The sheet as stored: cells and settings only (no empty parts). */
export function sheetJson(sheet: SheetData): SheetData {
  const out: SheetData = { v: 1, rows: sheet.rows, cols: sheet.cols, widths: { ...sheet.widths }, cells: {} }
  for (const [k, cell] of Object.entries(sheet.cells)) {
    const c: SheetCell = {}
    if (cell.v !== undefined && cell.v !== "") c.v = cell.v
    const s = cell.s ? cleanStyle(cell.s) : undefined
    if (s) c.s = s
    if (c.v !== undefined || c.s) out.cells[k] = c
  }
  if (sheet.table) out.table = { style: sheet.table.style }
  if (sheet.hide && Object.keys(sheet.hide).length) out.hide = { ...sheet.hide }
  if (sheet.hidden && (sheet.hidden.cols?.length || sheet.hidden.rows?.length)) out.hidden = JSON.parse(JSON.stringify(sheet.hidden))
  return out
}

// ---- Hiding rows and columns ----

export function hideCols(sheet: SheetData, c1: number, c2: number): SheetData {
  const cols = [...(sheet.hidden?.cols ?? [])]
  for (let c = c1; c <= c2; c++) cols.push(colName(c))
  return withHidden(sheet, cols, [...handHiddenRows(sheet)])
}

export function unhideCols(sheet: SheetData, c1: number, c2: number): SheetData {
  const cols = (sheet.hidden?.cols ?? []).filter((k) => colIndex(k) < c1 || colIndex(k) > c2)
  return withHidden(sheet, cols, [...handHiddenRows(sheet)])
}

export function hideRows(sheet: SheetData, r1: number, r2: number): SheetData {
  const rows = [...handHiddenRows(sheet)]
  for (let r = r1; r <= r2; r++) rows.push(r)
  return withHidden(sheet, sheet.hidden?.cols ?? [], rows)
}

export function unhideRows(sheet: SheetData, r1: number, r2: number): SheetData {
  return withHidden(sheet, sheet.hidden?.cols ?? [], [...handHiddenRows(sheet)].filter((r) => r < r1 || r > r2))
}

export function unhideAll(sheet: SheetData): SheetData {
  return withHidden(sheet, [], [])
}
