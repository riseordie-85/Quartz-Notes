// Excel files (.xlsx): a sheet written as one, and the worksheets of one read as sheets. Kept in
// step with the app's data/sheet/SheetXlsx.kt; see docs/sheet-format.md ("Excel files").

import { DEFAULT_COLS, DEFAULT_ROWS, TABLE_STYLES, addr, colIndex, colName, emptySheet, parseAddr, type SheetCell, type SheetData, type SheetStyle, usedSize, valuedSize } from "./model"
import { SheetCalc, textOf } from "./calc"
import { hiddenRows, shiftFormula } from "./grid"
import { canonical, isStoredNumber } from "./numbers"
import { readZip, writeZip } from "./zip"

// ---- Writing ----

/** Applies [f] to the parts of a formula outside quoted text. */
function outsideQuotes(formula: string, f: (part: string) => string): string {
  return formula
    .split(/("(?:[^"]|"")*")/)
    .map((part, i) => (i % 2 === 1 ? part : f(part)))
    .join("")
}

/** A formula as Excel files keep it: newer functions carry Excel's "_xlfn." mark. */
export function excelFormula(formula: string): string {
  return outsideQuotes(formula, (p) => p.replace(/(^|[^A-Za-z0-9_.])(CONCAT)(\s*\()/gi, "$1_xlfn.$2$3"))
}

/** A formula from an Excel file, without Excel's marks on newer functions. */
export function plainFormula(formula: string): string {
  return outsideQuotes(formula, (p) => p.replace(/_xl(fn|ws)\./gi, ""))
}

/** A cell's style with the sheet's table look baked in (header color and bold text, bands). */
export function lookOf(sheet: SheetData, r: number, c: number, valued: [number, number] = valuedSize(sheet)): SheetStyle {
  const s: SheetStyle = { ...(sheet.cells[addr(r, c)]?.s ?? {}) }
  const table = sheet.table ? TABLE_STYLES[sheet.table.style] : undefined
  if (!table || r >= valued[0] || c >= valued[1]) return s
  if (r === 0) {
    s.b = true
    if (!s.fill) {
      s.fill = table.header
      s.color ??= "#FFFFFF"
    }
  } else if (!s.fill) {
    s.fill = r % 2 === 0 ? table.band : "#FFFFFF"
  }
  return s
}

/** Excel's number format code for a style, or null for General. */
export function formatCode(fmt: string | undefined, dp: number | undefined): string | null {
  const places = (n: number) => (n > 0 ? "." + "0".repeat(n) : "")
  switch (fmt) {
    case "number":
      return "#,##0" + places(dp ?? 2)
    case "currency":
      return '"$"#,##0' + places(dp ?? 2)
    case "percent":
      return "0" + places(dp ?? 0) + "%"
    case "date":
      return "m/d/yyyy"
    case "time":
      return "h:mm AM/PM"
    case "datetime":
      return "m/d/yyyy h:mm AM/PM"
    case "text":
      return "@"
    default:
      return null
  }
}

const esc = (s: string) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "")
const argb = (hex: string) => "FF" + hex.replace("#", "").toUpperCase()

/** A worksheet name Excel accepts. */
export function sheetName(title: string): string {
  const clean = title.replace(/[[\]:*?/\\]/g, " ").replace(/^'+|'+$/g, "").trim().slice(0, 31).trim()
  return clean || "Sheet1"
}

/** The sheet as an .xlsx file (values, formulas with their results, formats, styles, widths). */
export async function toXlsx(sheet: SheetData, title = ""): Promise<Uint8Array> {
  const calc = new SheetCalc(sheet)
  const [rows, cols] = usedSize(sheet)
  const valued = valuedSize(sheet)
  const hidden = hiddenRows(sheet, calc)

  const numFmts: string[] = []
  const fonts = ["<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"]
  const fills = ['<fill><patternFill patternType="none"/></fill>', '<fill><patternFill patternType="gray125"/></fill>']
  const borders = ["<border><left/><right/><top/><bottom/><diagonal/></border>"]
  const xfs = ['<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>']
  const index = (list: string[], item: string) => {
    const i = list.indexOf(item)
    if (i >= 0) return i
    list.push(item)
    return list.length - 1
  }
  const xfOf = (s: SheetStyle): number => {
    const code = formatCode(s.fmt, s.dp)
    const numFmtId = code === null ? 0 : code === "@" ? 49 : 164 + index(numFmts, code)
    const font = `<font>${s.b ? "<b/>" : ""}${s.i ? "<i/>" : ""}${s.u ? "<u/>" : ""}<sz val="11"/>${s.color ? `<color rgb="${argb(s.color)}"/>` : ""}<name val="Calibri"/></font>`
    const fontId = index(fonts, font)
    const fillId = s.fill ? index(fills, `<fill><patternFill patternType="solid"><fgColor rgb="${argb(s.fill)}"/><bgColor indexed="64"/></patternFill></fill>`) : 0
    const side = (name: string, on: boolean) => (on ? `<${name} style="thin"><color auto="1"/></${name}>` : `<${name}/>`)
    const b = s.border ?? ""
    const borderId = b ? index(borders, `<border>${side("left", b.includes("l"))}${side("right", b.includes("r"))}${side("top", b.includes("t"))}${side("bottom", b.includes("b"))}<diagonal/></border>`) : 0
    const align = s.align || s.wrap ? `<alignment${s.align ? ` horizontal="${s.align}"` : ""}${s.wrap ? ' wrapText="1"' : ""}/>` : ""
    const xf =
      `<xf numFmtId="${numFmtId}" fontId="${fontId}" fillId="${fillId}" borderId="${borderId}" xfId="0"` +
      `${numFmtId ? ' applyNumberFormat="1"' : ""}${fontId ? ' applyFont="1"' : ""}${fillId ? ' applyFill="1"' : ""}${borderId ? ' applyBorder="1"' : ""}` +
      (align ? ` applyAlignment="1">${align}</xf>` : "/>")
    return index(xfs, xf)
  }

  const rowXml: string[] = []
  for (let r = 0; r < rows; r++) {
    const cellsXml: string[] = []
    for (let c = 0; c < cols; c++) {
      const cell = sheet.cells[addr(r, c)]
      const style = lookOf(sheet, r, c, valued)
      if (!cell?.v && Object.keys(style).length === 0) continue
      const ref = addr(r, c)
      const xf = xfOf(style)
      const sAttr = xf ? ` s="${xf}"` : ""
      const v = cell?.v
      if (!v) {
        cellsXml.push(`<c r="${ref}"${sAttr}/>`)
      } else if (v.startsWith("=") && style.fmt !== "text") {
        const value = calc.value(r, c)
        const f = `<f>${esc(excelFormula(v.slice(1)))}</f>`
        if (value.k === "num") cellsXml.push(`<c r="${ref}"${sAttr}>${f}<v>${canonical(value.n)}</v></c>`)
        else if (value.k === "bool") cellsXml.push(`<c r="${ref}"${sAttr} t="b">${f}<v>${value.b ? 1 : 0}</v></c>`)
        else if (value.k === "err") cellsXml.push(`<c r="${ref}"${sAttr} t="e">${f}<v>${esc(value.e === "#CIRC!" || value.e === "#ERROR!" ? "#VALUE!" : value.e)}</v></c>`)
        else cellsXml.push(`<c r="${ref}"${sAttr} t="str">${f}<v>${esc(textOf(value))}</v></c>`)
      } else if (style.fmt !== "text" && isStoredNumber(v)) {
        cellsXml.push(`<c r="${ref}"${sAttr}><v>${v}</v></c>`)
      } else {
        cellsXml.push(`<c r="${ref}"${sAttr} t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>`)
      }
    }
    if (cellsXml.length || hidden.has(r)) rowXml.push(`<row r="${r + 1}"${hidden.has(r) ? ' hidden="1"' : ""}>${cellsXml.join("")}</row>`)
  }
  const colXml = Object.entries(sheet.widths)
    .map(([letters, w]) => [colIndex(letters) + 1, w] as [number, number])
    .sort((a, b) => a[0] - b[0])
    .map(([i, w]) => `<col min="${i}" max="${i}" width="${(w / 7).toFixed(2)}" customWidth="1"/>`)
    .join("")

  const worksheet =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
    `<dimension ref="A1${rows && cols ? ":" + addr(rows - 1, cols - 1) : ""}"/>` +
    '<sheetFormatPr defaultRowHeight="15"/>' +
    (colXml ? `<cols>${colXml}</cols>` : "") +
    `<sheetData>${rowXml.join("")}</sheetData></worksheet>`
  const styles =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
    '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    (numFmts.length ? `<numFmts count="${numFmts.length}">${numFmts.map((code, i) => `<numFmt numFmtId="${164 + i}" formatCode="${esc(code)}"/>`).join("")}</numFmts>` : "") +
    `<fonts count="${fonts.length}">${fonts.join("")}</fonts>` +
    `<fills count="${fills.length}">${fills.join("")}</fills>` +
    `<borders count="${borders.length}">${borders.join("")}</borders>` +
    '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
    `<cellXfs count="${xfs.length}">${xfs.join("")}</cellXfs>` +
    '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>'
  const workbook =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
    '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
    `<sheets><sheet name="${esc(sheetName(title))}" sheetId="1" r:id="rId1"/></sheets><calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>`
  const enc = new TextEncoder()
  return writeZip([
    {
      name: "[Content_Types].xml",
      data: enc.encode(
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>' +
          '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
          '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' +
          '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>',
      ),
    },
    {
      name: "_rels/.rels",
      data: enc.encode(
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
          '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
      ),
    },
    { name: "xl/workbook.xml", data: enc.encode(workbook) },
    {
      name: "xl/_rels/workbook.xml.rels",
      data: enc.encode(
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
          '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>' +
          '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
      ),
    },
    { name: "xl/worksheets/sheet1.xml", data: enc.encode(worksheet) },
    { name: "xl/styles.xml", data: enc.encode(styles) },
  ])
}

// ---- Reading ----

/** What one of Excel's number formats shows as here. */
export function formatFromCode(id: number, code: string | undefined): { fmt?: string; dp?: number } {
  const builtIn: Record<number, { fmt?: string; dp?: number }> = {
    1: { fmt: "number", dp: 0 }, 2: { fmt: "number", dp: 2 }, 3: { fmt: "number", dp: 0 }, 4: { fmt: "number", dp: 2 },
    5: { fmt: "currency", dp: 0 }, 6: { fmt: "currency", dp: 0 }, 7: { fmt: "currency", dp: 2 }, 8: { fmt: "currency", dp: 2 },
    9: { fmt: "percent", dp: 0 }, 10: { fmt: "percent", dp: 2 }, 14: { fmt: "date" }, 15: { fmt: "date" }, 16: { fmt: "date" },
    17: { fmt: "date" }, 18: { fmt: "time" }, 19: { fmt: "time" }, 20: { fmt: "time" }, 21: { fmt: "time" }, 22: { fmt: "datetime" },
    37: { fmt: "number", dp: 0 }, 38: { fmt: "number", dp: 0 }, 39: { fmt: "number", dp: 2 }, 40: { fmt: "number", dp: 2 },
    44: { fmt: "currency", dp: 2 }, 45: { fmt: "time" }, 46: { fmt: "time" }, 47: { fmt: "time" }, 49: { fmt: "text" },
  }
  if (code === undefined) return builtIn[id] ?? {}
  // Looks at the first section, without quoted text, escapes and [colors].
  const section = code.split(";")[0]
  const bare = section.replace(/"[^"]*"/g, "").replace(/\\./g, "").replace(/\[(?![hms]+\])[^\]]*\]/gi, "").replace(/_.|\*./g, "")
  const places = (/\.(0+)/.exec(bare)?.[1].length) ?? 0
  const lower = bare.toLowerCase()
  if (bare.trim() === "@") return { fmt: "text" }
  const date = /[yd]/.test(lower) || (/m/.test(lower) && !/[hs]/.test(lower) && !/0/.test(lower))
  const time = /[hs]/.test(lower)
  if (date && time) return { fmt: "datetime" }
  if (date) return { fmt: "date" }
  if (time) return { fmt: "time" }
  if (bare.includes("%")) return { fmt: "percent", dp: places }
  if (/[$€£¥]/.test(section.replace(/\[\$-[^\]]*\]/g, "")) && /0|#/.test(bare)) return { fmt: "currency", dp: places }
  if (/0|#/.test(bare) && !/e[+-]/i.test(bare)) return { fmt: "number", dp: places }
  return {}
}

function children(el: Element, name: string): Element[] {
  return Array.from(el.children).filter((c) => c.localName === name)
}
function child(el: Element | undefined, name: string): Element | undefined {
  return el ? children(el, name)[0] : undefined
}
function allText(el: Element, name: string): string {
  return Array.from(el.getElementsByTagNameNS("*", name)).map((t) => t.textContent ?? "").join("")
}
function colorOf(el: Element | undefined): string | undefined {
  const rgb = el?.getAttribute("rgb")
  return rgb && /^[0-9A-Fa-f]{8}$/.test(rgb) ? "#" + rgb.slice(2).toUpperCase() : rgb && /^[0-9A-Fa-f]{6}$/.test(rgb) ? "#" + rgb.toUpperCase() : undefined
}

/** The folder part of a zip path ("xl/" for "xl/workbook.xml"). */
function resolve(base: string, target: string): string {
  if (target.startsWith("/")) return target.slice(1)
  const parts = base.split("/").slice(0, -1)
  for (const p of target.split("/")) {
    if (p === "..") parts.pop()
    else if (p !== ".") parts.push(p)
  }
  return parts.join("/")
}

export interface ImportedSheet {
  name: string
  sheet: SheetData
}

/** The worksheets of an .xlsx file, as sheets (values, formulas, formats, styles, widths). */
export async function fromXlsx(bytes: Uint8Array): Promise<ImportedSheet[]> {
  const files = await readZip(bytes)
  const dec = new TextDecoder()
  const xml = (path: string): Document | null => {
    const data = files.get(path)
    return data ? new DOMParser().parseFromString(dec.decode(data), "application/xml") : null
  }
  const rootRels = xml("_rels/.rels")
  const bookPath =
    Array.from(rootRels?.getElementsByTagNameNS("*", "Relationship") ?? []).find((r) => (r.getAttribute("Type") ?? "").endsWith("/officeDocument"))?.getAttribute("Target")?.replace(/^\//, "") ??
    "xl/workbook.xml"
  const book = xml(bookPath)
  if (!book) throw new Error("This isn't an Excel workbook")
  const relsPath = resolve(bookPath, "_rels/" + bookPath.split("/").pop() + ".rels")
  const rels = new Map<string, { type: string; target: string }>()
  for (const r of Array.from(xml(relsPath)?.getElementsByTagNameNS("*", "Relationship") ?? [])) {
    rels.set(r.getAttribute("Id") ?? "", { type: r.getAttribute("Type") ?? "", target: resolve(bookPath, r.getAttribute("Target") ?? "") })
  }
  const pathOf = (type: string) => [...rels.values()].find((r) => r.type.endsWith("/" + type))?.target

  // Shared text.
  const strings: string[] = []
  const sst = pathOf("sharedStrings") ? xml(pathOf("sharedStrings")!) : null
  for (const si of Array.from(sst?.documentElement.children ?? [])) if (si.localName === "si") strings.push(allText(si, "t"))

  // Styles: each cell style as ours.
  const styles: SheetStyle[] = []
  const st = pathOf("styles") ? xml(pathOf("styles")!) : null
  if (st) {
    const root = st.documentElement
    const codes = new Map<number, string>()
    for (const f of children(child(root, "numFmts") ?? root, "numFmt")) codes.set(Number(f.getAttribute("numFmtId")), f.getAttribute("formatCode") ?? "")
    const fonts = children(child(root, "fonts") ?? root, "font").map((f) => {
      const on = (n: string) => {
        const e = child(f, n)
        return !!e && e.getAttribute("val") !== "0" && e.getAttribute("val") !== "false" && e.getAttribute("val") !== "none"
      }
      return { b: on("b"), i: on("i"), u: on("u"), color: colorOf(child(f, "color")) }
    })
    const fills = children(child(root, "fills") ?? root, "fill").map((f) => {
      const p = child(f, "patternFill")
      return p?.getAttribute("patternType") === "solid" ? (colorOf(child(p, "fgColor")) ?? colorOf(child(p, "bgColor"))) : undefined
    })
    const borders = children(child(root, "borders") ?? root, "border").map((b) =>
      [["top", "t"], ["right", "r"], ["bottom", "b"], ["left", "l"]].filter(([n]) => !!child(b, n)?.getAttribute("style")).map(([, l]) => l).join(""),
    )
    for (const xf of children(child(root, "cellXfs") ?? root, "xf")) {
      const s: SheetStyle = {}
      const id = Number(xf.getAttribute("numFmtId") ?? 0)
      Object.assign(s, formatFromCode(id, codes.get(id)))
      const font = fonts[Number(xf.getAttribute("fontId") ?? 0)]
      if (font?.b) s.b = true
      if (font?.i) s.i = true
      if (font?.u) s.u = true
      if (font?.color && font.color !== "#000000") s.color = font.color
      const fill = fills[Number(xf.getAttribute("fillId") ?? 0)]
      if (fill) s.fill = fill
      const border = borders[Number(xf.getAttribute("borderId") ?? 0)]
      if (border) s.border = border
      const al = child(xf, "alignment")
      const h = al?.getAttribute("horizontal")
      if (h === "left" || h === "center" || h === "right") s.align = h
      if (al?.getAttribute("wrapText") === "1" || al?.getAttribute("wrapText") === "true") s.wrap = true
      if (s.dp !== undefined && s.dp === (s.fmt === "number" || s.fmt === "currency" ? 2 : 0)) delete s.dp
      styles.push(s)
    }
  }

  const out: ImportedSheet[] = []
  const sheets = Array.from(book.getElementsByTagNameNS("*", "sheet"))
  for (const sh of sheets) {
    const rid = sh.getAttribute("r:id") ?? sh.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id") ?? ""
    const path = rels.get(rid)?.target
    const doc = path ? xml(path) : null
    if (!doc) continue
    const sheet = emptySheet()
    const shared = new Map<string, { formula: string; r: number; c: number }>()
    for (const row of Array.from(doc.getElementsByTagNameNS("*", "row"))) {
      for (const c of children(row, "c")) {
        const at = parseAddr(c.getAttribute("r") ?? "")
        if (!at) continue
        const [r, col] = at
        const style = styles[Number(c.getAttribute("s") ?? 0)] ?? {}
        const t = c.getAttribute("t") ?? "n"
        const f = child(c, "f")
        const v = child(c, "v")?.textContent ?? undefined
        let value: string | undefined
        if (f) {
          let formula = f.textContent ?? ""
          const si = f.getAttribute("si")
          if (f.getAttribute("t") === "shared" && si !== null) {
            if (formula) shared.set(si, { formula, r, c: col })
            else {
              const base = shared.get(si)
              if (base) formula = shiftFormula("=" + base.formula, r - base.r, col - base.c).slice(1)
            }
          }
          if (formula) value = "=" + plainFormula(formula)
        }
        if (value === undefined) {
          if (t === "s") value = strings[Number(v)]
          else if (t === "inlineStr") value = allText(c, "t")
          else if (t === "b") value = v === "1" ? "TRUE" : "FALSE"
          else if (t === "str" || t === "e") value = v
          else if (v !== undefined && v !== "") value = style.fmt === "text" ? v : canonical(Number(v))
        }
        const cell: SheetCell = {}
        if (value !== undefined && value !== "") cell.v = value
        if (Object.keys(style).length) cell.s = { ...style }
        if (cell.v !== undefined || cell.s) sheet.cells[addr(r, col)] = cell
      }
    }
    for (const col of Array.from(doc.getElementsByTagNameNS("*", "col"))) {
      const min = Number(col.getAttribute("min"))
      const max = Math.min(Number(col.getAttribute("max")), min + 60)
      const width = Number(col.getAttribute("width"))
      if (!min || !width || col.getAttribute("customWidth") === "0") continue
      for (let i = min; i <= max; i++) sheet.widths[colName(i - 1)] = Math.round(width * 7)
    }
    const [rows, cols] = usedSize(sheet)
    sheet.rows = Math.max(DEFAULT_ROWS, rows + 1)
    sheet.cols = Math.max(DEFAULT_COLS, cols + 1)
    out.push({ name: sh.getAttribute("name") ?? "Sheet", sheet })
  }
  return out
}
