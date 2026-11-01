// CSV files: a sheet's shown values written out, and a CSV file read in as typed cells.

import { DEFAULT_COLS, DEFAULT_ROWS, emptySheet, type SheetData, usedSize } from "./model"
import { SheetCalc } from "./calc"
import { typeInto } from "./grid"

/** The sheet's shown values as CSV (commas, quotes where needed, CRLF line ends). */
export function toCsv(sheet: SheetData, calc: SheetCalc = new SheetCalc(sheet)): string {
  const [rows, cols] = usedSize(sheet)
  const lines: string[] = []
  for (let r = 0; r < rows; r++) {
    const cells: string[] = []
    for (let c = 0; c < cols; c++) {
      const v = calc.display(r, c)
      cells.push(/[",\r\n]/.test(v) || /^\s|\s$/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)
    }
    lines.push(cells.join(","))
  }
  return lines.join("\r\n") + (lines.length ? "\r\n" : "")
}

/** The rows of a CSV file; the separator (comma, semicolon or tab) is worked out from the first line. */
export function parseCsv(text: string): string[][] {
  const body = text.replace(/^﻿/, "")
  const first = body.split(/\r?\n/, 1)[0] ?? ""
  const count = (ch: string) => first.split(ch).length - 1
  const sep = [",", ";", "\t"].reduce((best, ch) => (count(ch) > count(best) ? ch : best), ",")
  const rows: string[][] = []
  let row: string[] = []
  let cell = ""
  let quoted = false
  for (let i = 0; i < body.length; i++) {
    const ch = body[i]
    if (quoted) {
      if (ch === '"') {
        if (body[i + 1] === '"') {
          cell += '"'
          i++
        } else quoted = false
      } else cell += ch
    } else if (ch === '"' && cell === "") quoted = true
    else if (ch === sep) {
      row.push(cell)
      cell = ""
    } else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && body[i + 1] === "\n") i++
      row.push(cell)
      rows.push(row)
      row = []
      cell = ""
    } else cell += ch
  }
  if (cell !== "" || row.length) {
    row.push(cell)
    rows.push(row)
  }
  return rows
}

/** A CSV file as a sheet: each value typed into its cell (so numbers, dates and formulas work). */
export function fromCsv(text: string): SheetData {
  let sheet = emptySheet()
  parseCsv(text).forEach((cells, r) => cells.forEach((v, c) => (sheet = v.trim() ? typeInto(sheet, r, c, v) : sheet)))
  const [rows, cols] = usedSize(sheet)
  sheet.rows = Math.max(DEFAULT_ROWS, rows + 1)
  sheet.cols = Math.max(DEFAULT_COLS, cols + 1)
  return sheet
}
