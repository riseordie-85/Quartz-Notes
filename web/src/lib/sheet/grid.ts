// Changing a sheet's grid: typing, inserting and deleting rows and columns, copying formulas,
// sorting, the filter's hidden rows, and the text used for search. Kept in step with the app's
// data/sheet/SheetGrid.kt; see docs/sheet-format.md.

import { addr, colIndex, colName, parseAddr, type SheetCell, type SheetData, type SheetStyle, usedSize } from "./model"
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

/** Inserts `n` empty rows before 0-based row `at`. */
export function insertRows(sheet: SheetData, at: number, n: number): SheetData {
  const change = insertChange("r", at, n)
  const out = remap(sheet, (r, c) => [r >= at ? r + n : r, c], (f) => rewriteRefs(f, change))
  out.rows += n
  return out
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
  return out
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
  return out
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
  return out
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

/** 0-based rows the filter hides (never row 0, the header). */
export function hiddenRows(sheet: SheetData, calc: SheetCalc = new SheetCalc(sheet)): Set<number> {
  const hidden = new Set<number>()
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
