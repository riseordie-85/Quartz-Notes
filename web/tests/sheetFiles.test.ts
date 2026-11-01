// @vitest-environment jsdom
// Excel and CSV files: the shared samples read as expected (the app reads the same ones), and a
// sheet written out reads back the same.
import { describe, expect, it } from "vitest"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { dirname, join } from "node:path"
import { SheetCalc, hideCols, hideRows, excelFormula, plainFormula, emptySheet, formatFromCode, fromCsv, fromXlsx, parseAddr, toCsv, toXlsx, typeInto, updateStyles, setBorders, type SheetData } from "../src/lib/sheet"

const dir = join(dirname(fileURLToPath(import.meta.url)), "../../shared/fixtures/sheets")
const expected = JSON.parse(readFileSync(join(dir, "xlsx-expected.json"), "utf8"))

describe("reading Excel files", () => {
  for (const file of expected.files as string[]) {
    it(file, async () => {
      const sheets = await fromXlsx(new Uint8Array(readFileSync(join(dir, file))))
      expect(sheets.map((s) => s.name)).toEqual(expected.sheets.map((s: { name: string }) => s.name))
      for (const [i, want] of (expected.sheets as any[]).entries()) {
        const got = sheets[i].sheet
        const calc = new SheetCalc(got, { now: 46326 })
        for (const [a, cell] of Object.entries<any>(want.cells)) {
          const actual = got.cells[a]
          expect(actual?.v, `${want.name}!${a} value`).toBe(cell.v)
          for (const [k, v] of Object.entries(cell.s ?? {})) expect((actual?.s as any)?.[k], `${want.name}!${a} ${k}`).toEqual(v)
          expect(actual?.s?.fmt, `${want.name}!${a} format`).toBe(cell.s?.fmt)
          if (cell.shows !== undefined) expect(calc.display(...parseAddr(a)!), `${want.name}!${a} shows`).toBe(cell.shows)
        }
        for (const [k, w] of Object.entries(want.widths ?? {})) expect(got.widths[k]).toBe(w)
        expect(got.hidden?.cols ?? [], `${want.name} hidden columns`).toEqual(want.hiddenCols)
        expect(got.hidden?.rows ?? [], `${want.name} hidden rows`).toEqual(want.hiddenRows)
      }
    })
  }
})

describe("Excel number formats", () => {
  it.each([
    [0, undefined, {}],
    [4, undefined, { fmt: "number", dp: 2 }],
    [164, '"$"#,##0.00', { fmt: "currency", dp: 2 }],
    [164, "[$$-409]#,##0.00", { fmt: "currency", dp: 2 }],
    [164, "[$-409]#,##0", { fmt: "number", dp: 0 }],
    [164, "0.0%", { fmt: "percent", dp: 1 }],
    [164, "yyyy-mm-dd", { fmt: "date" }],
    [164, "mmm d", { fmt: "date" }],
    [164, "[h]:mm:ss", { fmt: "time" }],
    [164, "mm:ss", { fmt: "time" }],
    [164, "m/d/yyyy h:mm", { fmt: "datetime" }],
    [164, "@", { fmt: "text" }],
    [164, "0.00E+00", {}],
  ])("%s %s", (id, code, want) => expect(formatFromCode(id as number, code as string | undefined)).toEqual(want))
})

function sample(): SheetData {
  let s = emptySheet()
  const typed: [string, string][] = [
    ["A1", "Item"], ["B1", "Cost"], ["A2", "Rent"], ["B2", "$1,450"], ["A3", "Power & gas <est>"], ["B3", "92.18"],
    ["A4", "Total"], ["B4", "=SUM(B2:B3)"], ["C4", "=B4>1000"], ["D4", '=IF(C4,"over","under")'], ["C2", "10/31/2026"], ["D2", "45%"],
    ["E2", "'00123"], ["E3", "3:30 PM"],
  ]
  for (const [a, v] of typed) {
    const [r, c] = parseAddr(a)!
    s = typeInto(s, r, c, v)
  }
  s = updateStyles(s, { r1: 0, c1: 0, r2: 0, c2: 1 }, (x) => ({ ...x, b: true, fill: "#FFF3B0", align: "center" }))
  s = updateStyles(s, { r1: 2, c1: 0, r2: 2, c2: 0 }, (x) => ({ ...x, i: true, u: true, color: "#D6334C", wrap: true }))
  s = updateStyles(s, { r1: 1, c1: 1, r2: 2, c2: 1 }, (x) => ({ ...x, fmt: "number", dp: 1 }))
  s = setBorders(s, { r1: 3, c1: 0, r2: 3, c2: 1 }, "outline")
  return { ...s, widths: { A: 154 } }
}

describe("writing Excel files", () => {
  it("reads back the same", async () => {
    const s = sample()
    const [back] = await fromXlsx(await toXlsx(s, "My: sheet?"))
    expect(back.name).toBe("My  sheet")
    for (const [a, cell] of Object.entries(s.cells)) {
      expect(back.sheet.cells[a]?.v, a).toBe(cell.v)
      expect(back.sheet.cells[a]?.s ?? {}, a).toEqual(cell.s ?? {})
    }
    expect(Object.keys(back.sheet.cells).sort()).toEqual(Object.keys(s.cells).sort())
    expect(back.sheet.widths.A).toBe(154)
  })

  it("keeps hidden columns and rows", async () => {
    const s = hideRows(hideCols(sample(), 2, 2), 1, 1)
    const [back] = await fromXlsx(await toXlsx(s))
    expect(back.sheet.hidden).toEqual({ cols: ["C"], rows: [2] })
  })

  it("bakes in the table look", async () => {
    const s = { ...sample(), table: { style: "green" } }
    const [back] = await fromXlsx(await toXlsx(s))
    expect(back.sheet.cells.A1?.s).toMatchObject({ b: true, fill: "#FFF3B0" })
    expect(back.sheet.cells.A3?.s).toMatchObject({ fill: "#E8F5EC" })
    expect(back.sheet.cells.A2?.s).toMatchObject({ fill: "#FFFFFF" })
  })
})

describe("Excel's marks on newer functions", () => {
  it("adds and removes them outside quoted text", () => {
    expect(excelFormula('CONCAT(A1,"CONCAT(")&concat (B1)')).toBe('_xlfn.CONCAT(A1,"CONCAT(")&_xlfn.concat (B1)')
    expect(plainFormula('_xlfn.CONCAT(A1,"_xlfn.x")')).toBe('CONCAT(A1,"_xlfn.x")')
  })
})

describe("CSV", () => {
  it("writes shown values with quotes where needed", () => {
    let s = emptySheet()
    s = typeInto(s, 0, 0, 'say "hi", ok')
    s = typeInto(s, 0, 1, "$5")
    s = typeInto(s, 1, 0, "=1+1")
    expect(toCsv(s)).toBe('"say ""hi"", ok",$5.00\r\n2,\r\n')
  })
  it("reads quoted fields, line breaks in quotes, semicolons and types the values", () => {
    const s = fromCsv('﻿Name;Cost;Due\r\n"Smith; J";"1.234,5";10/31/2026\n"two\nlines";=1+2;\n')
    expect(s.cells.A2?.v).toBe("Smith; J")
    expect(s.cells.C2).toEqual({ v: "46326", s: { fmt: "date" } })
    expect(s.cells.A3?.v).toBe("two\nlines")
    expect(s.cells.B3?.v).toBe("=1+2")
    expect(new SheetCalc(s).display(2, 1)).toBe("3")
  })
})
