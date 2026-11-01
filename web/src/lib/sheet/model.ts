// The sheet JSON kept in pages.sheet (docs/sheet-format.md), and cell addresses.

export interface SheetStyle {
  fmt?: string
  dp?: number
  b?: boolean
  i?: boolean
  u?: boolean
  color?: string
  fill?: string
  align?: string
  wrap?: boolean
  border?: string
}

export interface SheetCell {
  v?: string
  s?: SheetStyle
}

export interface SheetData {
  v: number
  rows: number
  cols: number
  widths: Record<string, number>
  cells: Record<string, SheetCell>
  table?: { style: string }
  hide?: Record<string, string[]>
}

export const DEFAULT_ROWS = 50
export const DEFAULT_COLS = 12
export const DEFAULT_WIDTH = 100
export const TABLE_STYLES: Record<string, { header: string; band: string }> = {
  blue: { header: "#2F6FDE", band: "#EAF1FD" },
  green: { header: "#2E9E5B", band: "#E8F5EC" },
  orange: { header: "#E07A1F", band: "#FDF0E3" },
  red: { header: "#D6334C", band: "#FCE8EB" },
  purple: { header: "#6A3FD0", band: "#F0EAFB" },
  gray: { header: "#5F6368", band: "#F1F3F4" },
}

/** Column letters for a 0-based column: 0 → A, 26 → AA. */
export function colName(c: number): string {
  let s = ""
  let n = c + 1
  while (n > 0) {
    const r = (n - 1) % 26
    s = String.fromCharCode(65 + r) + s
    n = Math.floor((n - 1) / 26)
  }
  return s
}

/** 0-based column for letters (any case): A → 0. */
export function colIndex(letters: string): number {
  let n = 0
  for (const ch of letters.toUpperCase()) n = n * 26 + (ch.charCodeAt(0) - 64)
  return n - 1
}

/** "B3" for 0-based row 2, column 1. */
export function addr(r: number, c: number): string {
  return colName(c) + (r + 1)
}

/** 0-based [row, column] of "B3", or null. */
export function parseAddr(a: string): [number, number] | null {
  const m = /^([A-Za-z]{1,3})(\d+)$/.exec(a)
  if (!m || Number(m[2]) < 1) return null
  return [Number(m[2]) - 1, colIndex(m[1])]
}

export function emptySheet(): SheetData {
  return { v: 1, rows: DEFAULT_ROWS, cols: DEFAULT_COLS, widths: {}, cells: {} }
}

function isRecord(x: unknown): x is Record<string, unknown> {
  return typeof x === "object" && x !== null && !Array.isArray(x)
}

function cleanStyle(x: unknown): SheetStyle | undefined {
  if (!isRecord(x)) return undefined
  const s: SheetStyle = {}
  if (typeof x.fmt === "string") s.fmt = x.fmt
  if (typeof x.dp === "number" && Number.isFinite(x.dp)) s.dp = Math.max(0, Math.min(10, Math.round(x.dp)))
  if (x.b === true) s.b = true
  if (x.i === true) s.i = true
  if (x.u === true) s.u = true
  if (typeof x.color === "string") s.color = x.color
  if (typeof x.fill === "string") s.fill = x.fill
  if (typeof x.align === "string") s.align = x.align
  if (x.wrap === true) s.wrap = true
  if (typeof x.border === "string" && x.border !== "") s.border = x.border
  return Object.keys(s).length ? s : undefined
}

/** Reads stored sheet JSON (an object or a string of it), dropping anything malformed. */
export function readSheet(raw: unknown): SheetData {
  let x = raw
  if (typeof x === "string") {
    try {
      x = JSON.parse(x)
    } catch {
      x = null
    }
  }
  const sheet = emptySheet()
  if (!isRecord(x)) return sheet
  const cells = isRecord(x.cells) ? x.cells : {}
  let maxR = 0
  let maxC = 0
  for (const [key, val] of Object.entries(cells)) {
    const at = parseAddr(key)
    if (!at || !isRecord(val)) continue
    const cell: SheetCell = {}
    if (typeof val.v === "string" && val.v !== "") cell.v = val.v
    const s = cleanStyle(val.s)
    if (s) cell.s = s
    if (cell.v === undefined && !cell.s) continue
    sheet.cells[addr(at[0], at[1])] = cell
    maxR = Math.max(maxR, at[0] + 1)
    maxC = Math.max(maxC, at[1] + 1)
  }
  const num = (n: unknown, d: number) => (typeof n === "number" && Number.isFinite(n) && n >= 1 ? Math.floor(n) : d)
  sheet.rows = Math.max(num(x.rows, DEFAULT_ROWS), maxR)
  sheet.cols = Math.max(num(x.cols, DEFAULT_COLS), maxC)
  if (isRecord(x.widths)) {
    for (const [k, w] of Object.entries(x.widths)) {
      if (/^[A-Z]{1,3}$/.test(k) && typeof w === "number" && Number.isFinite(w) && w > 0) sheet.widths[k] = w
    }
  }
  if (isRecord(x.table) && typeof x.table.style === "string" && TABLE_STYLES[x.table.style]) {
    sheet.table = { style: x.table.style }
  }
  if (isRecord(x.hide)) {
    const hide: Record<string, string[]> = {}
    for (const [k, list] of Object.entries(x.hide)) {
      if (/^[A-Z]{1,3}$/.test(k) && Array.isArray(list)) {
        const vals = list.filter((v): v is string => typeof v === "string")
        if (vals.length) hide[k] = vals
      }
    }
    if (Object.keys(hide).length) sheet.hide = hide
  }
  return sheet
}

/** The used area: one past the last row and column that hold anything. */
export function usedSize(sheet: SheetData): [number, number] {
  let r = 0
  let c = 0
  for (const key of Object.keys(sheet.cells)) {
    const at = parseAddr(key)
    if (!at) continue
    r = Math.max(r, at[0] + 1)
    c = Math.max(c, at[1] + 1)
  }
  return [r, c]
}
