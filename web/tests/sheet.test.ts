// The shared sheet cases (shared/fixtures/sheets/cases.json); the app runs the same ones.
import { describe, expect, it } from "vitest"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import {
  SheetCalc,
  deleteChange,
  emptySheet,
  formatNumber,
  hiddenRows,
  insertChange,
  parseAddr,
  parseInput,
  plainText,
  rewriteRefs,
  shiftChange,
  sortRows,
  type SheetData,
  typeInto,
  insertRows,
  deleteCols,
} from "../src/lib/sheet"

const cases = JSON.parse(
  readFileSync(fileURLToPath(new URL("../../shared/fixtures/sheets/cases.json", import.meta.url)), "utf8"),
)

function sheetOf(cells: Record<string, string>, styles: Record<string, object> = {}): SheetData {
  const s = emptySheet()
  for (const [k, v] of Object.entries(cells)) s.cells[k] = { v }
  for (const [k, st] of Object.entries(styles)) s.cells[k] = { ...(s.cells[k] ?? {}), s: st }
  return s
}

function at(a: string): [number, number] {
  return parseAddr(a)!
}

describe("typing into a cell", () => {
  for (const c of cases.input) {
    it(JSON.stringify(c.typed), () => {
      const t = parseInput(c.typed)
      if (c.clear) expect(t.v).toBeNull()
      else expect(t.v).toBe(c.v)
      expect(t.fmt).toBe(c.fmt)
    })
  }
})

describe("showing numbers", () => {
  for (const c of cases.format) {
    it(`${c.value} ${c.fmt ?? "general"}${c.dp !== undefined ? " " + c.dp : ""}`, () => {
      expect(formatNumber(c.value, c.fmt, c.dp)).toBe(c.out)
    })
  }
})

describe("formulas", () => {
  for (const s of cases.sheets) {
    describe(s.name, () => {
      const calc = new SheetCalc(sheetOf(s.cells, s.styles), { now: s.now })
      for (const [a, out] of Object.entries(s.expect)) {
        it(`${a} ${s.cells[a]}`, () => {
          expect(calc.display(...at(a))).toBe(out)
        })
      }
    })
  }
})

describe("changing the grid", () => {
  for (const c of cases.adjust) {
    it(`${c.formula} ${c.op}`, () => {
      const change =
        c.op === "insertRows" ? insertChange("r", c.at - 1, c.n)
        : c.op === "deleteRows" ? deleteChange("r", c.at - 1, c.n)
        : c.op === "insertCols" ? insertChange("c", c.at - 1, c.n)
        : c.op === "deleteCols" ? deleteChange("c", c.at - 1, c.n)
        : shiftChange(c.dr, c.dc)
      expect(rewriteRefs(c.formula, change)).toBe(c.out)
    })
  }

  it("moves cells when rows go in and columns come out", () => {
    let s = sheetOf({ A1: "1", A2: "2", A3: "=A1+A2", C1: "x" })
    s = insertRows(s, 1, 1)
    expect(s.cells.A3?.v).toBe("2")
    expect(s.cells.A4?.v).toBe("=A1+A3")
    expect(new SheetCalc(s).display(3, 0)).toBe("3")
    s = deleteCols(s, 1, 1)
    expect(s.cells.B1?.v).toBe("x")
  })
})

describe("sorting and filtering", () => {
  for (const c of cases.sort.cases) {
    it(`${c.col} ${c.desc ? "descending" : "ascending"}`, () => {
      const col = at(c.col + "1")[1]
      const sorted = sortRows(sheetOf(cases.sort.cells), col, c.desc, c.from - 1, c.to - 1)
      const names = []
      for (let r = c.from; r <= c.to; r++) names.push(sorted.cells["A" + r]?.v)
      expect(names).toEqual(c.order)
    })
  }

  it("hides filtered rows", () => {
    const s = sheetOf(cases.sort.cells)
    s.hide = cases.sort.hide
    expect([...hiddenRows(s)].map((r) => r + 1).sort()).toEqual(cases.sort.hidden)
  })
})

describe("search text", () => {
  it("joins shown values", () => {
    expect(plainText(sheetOf(cases.plainText.cells))).toBe(cases.plainText.out)
  })
})

describe("typing keeps formats", () => {
  it("sets a format from what's typed and keeps it for plain numbers", () => {
    let s = typeInto(emptySheet(), 0, 0, "$5")
    expect(s.cells.A1).toEqual({ v: "5", s: { fmt: "currency" } })
    s = typeInto(s, 0, 0, "7")
    expect(s.cells.A1).toEqual({ v: "7", s: { fmt: "currency" } })
    s = typeInto(s, 0, 0, "")
    expect(s.cells.A1).toEqual({ s: { fmt: "currency" } })
  })
})
