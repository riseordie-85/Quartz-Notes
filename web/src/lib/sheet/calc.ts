// Working out a sheet's values and what each cell shows. Kept in step with the app's
// data/sheet/SheetCalc.kt; see docs/sheet-format.md.

import { addr, type SheetData, usedSize } from "./model"
import { parseFormula, type Node } from "./formula"
import { dateOf, dec15, formatNumber, general, parseNumber, roundDec, serialOf } from "./numbers"

export type Value =
  | { k: "num"; n: number }
  | { k: "str"; s: string }
  | { k: "bool"; b: boolean }
  | { k: "err"; e: string }
  | { k: "empty" }

const EMPTY: Value = { k: "empty" }
const num = (n: number): Value => (Number.isFinite(n) ? { k: "num", n: n === 0 ? 0 : n } : err("#NUM!"))
const str = (s: string): Value => ({ k: "str", s })
const bool = (b: boolean): Value => ({ k: "bool", b })
const err = (e: string): Value => ({ k: "err", e })
const VALUE = err("#VALUE!")

const STORED_NUMBER = /^-?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/

/** Serial number for a moment, in local time. */
export function serialNow(d: Date = new Date()): number {
  return (
    serialOf(d.getFullYear(), d.getMonth() + 1, d.getDate()) +
    (d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds()) / 86400
  )
}

function numEq(a: number, b: number): boolean {
  return a === b || dec15(a) === dec15(b)
}

function numCmp(a: number, b: number): number {
  return numEq(a, b) ? 0 : a < b ? -1 : 1
}

function strCmp(a: string, b: string): number {
  const x = a.toLowerCase()
  const y = b.toLowerCase()
  return x === y ? 0 : x < y ? -1 : 1
}

/** A value as text: numbers in General form. */
export function textOf(v: Value): string {
  switch (v.k) {
    case "num":
      return general(v.n)
    case "str":
      return v.s
    case "bool":
      return v.b ? "TRUE" : "FALSE"
    case "err":
      return v.e
    case "empty":
      return ""
  }
}

/** A value turned into a number for arithmetic, or an error. */
function toNum(v: Value): number | Value {
  switch (v.k) {
    case "num":
      return v.n
    case "empty":
      return 0
    case "bool":
      return v.b ? 1 : 0
    case "str": {
      const n = parseNumber(v.s)
      return n === null ? VALUE : n
    }
    case "err":
      return v
  }
}

/** A value as a condition, or an error. */
function toCond(v: Value): boolean | Value {
  switch (v.k) {
    case "num":
      return v.n !== 0
    case "bool":
      return v.b
    case "empty":
      return false
    case "str": {
      const up = v.s.trim().toUpperCase()
      return up === "TRUE" ? true : up === "FALSE" ? false : VALUE
    }
    case "err":
      return v
  }
}

/** Order of two values for comparisons: numbers, then text, then TRUE/FALSE. */
function compareValues(a: Value, b: Value): number {
  const as = a.k === "empty" ? emptyLike(b) : a
  const bs = b.k === "empty" ? emptyLike(a) : b
  const rank = (v: Value) => (v.k === "num" ? 0 : v.k === "str" ? 1 : v.k === "bool" ? 2 : 3)
  const ra = rank(as)
  const rb = rank(bs)
  if (ra !== rb) return ra < rb ? -1 : 1
  if (as.k === "num" && bs.k === "num") return numCmp(as.n, bs.n)
  if (as.k === "str" && bs.k === "str") return strCmp(as.s, bs.s)
  if (as.k === "bool" && bs.k === "bool") return as.b === bs.b ? 0 : as.b ? 1 : -1
  return 0
}

function emptyLike(other: Value): Value {
  return other.k === "str" ? str("") : other.k === "bool" ? bool(false) : num(0)
}

/** A COUNTIF/SUMIF test. */
function makeTest(t: Value): (x: Value) => boolean {
  if (t.k === "num") return (x) => x.k === "num" && numEq(x.n, t.n)
  if (t.k === "bool") return (x) => x.k === "bool" && x.b === t.b
  const isBlank = (x: Value) => x.k === "empty" || (x.k === "str" && x.s === "")
  if (t.k === "empty") return isBlank
  const s = t.k === "str" ? t.s : ""
  const m = /^(<=|>=|<>|=|<|>)?([\s\S]*)$/.exec(s)!
  const op = m[1] ?? "="
  const rest = m[2]
  const n = rest.trim() === "" ? null : parseNumber(rest)
  const pass = (c: number) =>
    op === "=" ? c === 0 : op === "<>" ? c !== 0 : op === "<" ? c < 0 : op === ">" ? c > 0 : op === "<=" ? c <= 0 : c >= 0
  if (n !== null) {
    if (op === "<>") return (x) => !(x.k === "num" && numEq(x.n, n))
    return (x) => x.k === "num" && pass(numCmp(x.n, n))
  }
  if (op === "=") return rest === "" ? isBlank : (x) => x.k === "str" && strCmp(x.s, rest) === 0
  if (op === "<>") return rest === "" ? (x) => !isBlank(x) : (x) => !(x.k === "str" && strCmp(x.s, rest) === 0)
  return (x) => x.k === "str" && pass(strCmp(x.s, rest))
}

const ARITY: Record<string, [number, number]> = {
  SUM: [1, 255], AVERAGE: [1, 255], MIN: [1, 255], MAX: [1, 255], COUNT: [1, 255], COUNTA: [1, 255],
  COUNTIF: [2, 2], SUMIF: [2, 3], IF: [2, 3], IFERROR: [2, 2], AND: [1, 255], OR: [1, 255], NOT: [1, 1],
  ROUND: [1, 2], ABS: [1, 1], SQRT: [1, 1], POWER: [2, 2], INT: [1, 1], MOD: [2, 2], TODAY: [0, 0],
  NOW: [0, 0], DATE: [3, 3], YEAR: [1, 1], MONTH: [1, 1], DAY: [1, 1], CONCAT: [1, 255],
  CONCATENATE: [1, 255], LEN: [1, 1], UPPER: [1, 1], LOWER: [1, 1], TRIM: [1, 1], LEFT: [1, 2],
  RIGHT: [1, 2], MID: [3, 3],
}

/** The names of the functions sheets know, for suggestions. */
export const FUNCTIONS = Object.keys(ARITY)

export interface CalcOptions {
  /** The serial number TODAY() and NOW() use; the current local time if left out. */
  now?: number
}

/** Values of one sheet, worked out as they're asked for. Make a new one after any change. */
export class SheetCalc {
  private memo = new Map<string, Value>()
  private busy = new Set<string>()
  private parsed = new Map<string, Node>()
  private readonly lastRow: number
  private readonly lastCol: number
  private readonly now: number

  constructor(private readonly sheet: SheetData, options: CalcOptions = {}) {
    ;[this.lastRow, this.lastCol] = usedSize(sheet)
    this.now = options.now ?? serialNow()
  }

  /** The value of the cell at 0-based row and column. */
  value(r: number, c: number): Value {
    const key = addr(r, c)
    const known = this.memo.get(key)
    if (known) return known
    const cell = this.sheet.cells[key]
    const v = cell?.v
    if (!v) return EMPTY
    let out: Value
    if (cell.s?.fmt === "text") {
      out = str(v)
    } else if (v.startsWith("=")) {
      if (this.busy.has(key)) return err("#CIRC!")
      this.busy.add(key)
      let node = this.parsed.get(v)
      if (!node) {
        node = parseFormula(v)
        this.parsed.set(v, node)
      }
      out = this.ev(node)
      this.busy.delete(key)
      if (out.k === "empty") out = num(0)
    } else if (STORED_NUMBER.test(v)) {
      out = num(Number(v))
    } else {
      out = str(v)
    }
    this.memo.set(key, out)
    return out
  }

  /** What the cell at 0-based row and column shows. */
  display(r: number, c: number): string {
    const v = this.value(r, c)
    if (v.k === "num") {
      const s = this.sheet.cells[addr(r, c)]?.s
      return formatNumber(v.n, s?.fmt, s?.dp)
    }
    return textOf(v)
  }

  // ---- Working out a formula ----

  private ev(node: Node): Value {
    switch (node.k) {
      case "num":
        return num(node.n)
      case "str":
        return str(node.s)
      case "bool":
        return bool(node.b)
      case "err":
        return err(node.e)
      case "name":
        return err("#NAME?")
      case "ref":
        return this.value(node.r, node.c)
      case "range":
        return VALUE
      case "plus":
        return this.ev(node.x)
      case "neg": {
        const x = toNum(this.ev(node.x))
        return typeof x === "number" ? num(-x) : x
      }
      case "pct": {
        const x = toNum(this.ev(node.x))
        return typeof x === "number" ? num(x / 100) : x
      }
      case "bin":
        return this.binary(node.op, node.a, node.b)
      case "call":
        return this.call(node.name, node.args)
    }
  }

  private binary(op: string, an: Node, bn: Node): Value {
    const a = this.ev(an)
    if (op === "&") {
      if (a.k === "err") return a
      const b = this.ev(bn)
      if (b.k === "err") return b
      return str(textOf(a) + textOf(b))
    }
    if (op === "=" || op === "<>" || op === "<" || op === ">" || op === "<=" || op === ">=") {
      if (a.k === "err") return a
      const b = this.ev(bn)
      if (b.k === "err") return b
      const c = compareValues(a, b)
      return bool(
        op === "=" ? c === 0 : op === "<>" ? c !== 0 : op === "<" ? c < 0 : op === ">" ? c > 0 : op === "<=" ? c <= 0 : c >= 0,
      )
    }
    const x = toNum(a)
    if (typeof x !== "number") return x
    const y = toNum(this.ev(bn))
    if (typeof y !== "number") return y
    switch (op) {
      case "+":
        return num(x + y)
      case "-":
        return num(x - y)
      case "*":
        return num(x * y)
      case "/":
        return y === 0 ? err("#DIV/0!") : num(x / y)
      default:
        return power(x, y)
    }
  }

  /** Each cell's value in a reference or range (only the used part; `blanks` counts the rest). */
  private cells(node: Node): { values: Value[]; blanks: number; at: [number, number][] } {
    const [r1, c1, r2, c2] =
      node.k === "ref" ? [node.r, node.c, node.r, node.c] : node.k === "range" ? [node.r1, node.c1, node.r2, node.c2] : [0, 0, -1, -1]
    const values: Value[] = []
    const at: [number, number][] = []
    const rEnd = Math.min(r2, this.lastRow - 1)
    const cEnd = Math.min(c2, this.lastCol - 1)
    for (let r = r1; r <= rEnd; r++) {
      for (let c = c1; c <= cEnd; c++) {
        values.push(this.value(r, c))
        at.push([r, c])
      }
    }
    const total = (r2 - r1 + 1) * (c2 - c1 + 1)
    return { values, blanks: total - values.length, at }
  }

  private isArea(n: Node): boolean {
    return n.k === "ref" || n.k === "range"
  }

  /** The numbers in a "number" function's arguments; `skipErrors` for COUNT. */
  private numbers(args: Node[], skipErrors: boolean): number[] | Value {
    const out: number[] = []
    for (const a of args) {
      if (this.isArea(a)) {
        for (const v of this.cells(a).values) {
          if (v.k === "num") out.push(v.n)
          else if (v.k === "err" && !skipErrors) return v
        }
      } else {
        const n = toNum(this.ev(a))
        if (typeof n === "number") out.push(n)
        else if (!skipErrors) return n
      }
    }
    return out
  }

  /** Every value in the arguments, ranges spread out (empties left out). */
  private flat(args: Node[]): Value[] {
    const out: Value[] = []
    for (const a of args) {
      if (this.isArea(a)) out.push(...this.cells(a).values.filter((v) => v.k !== "empty"))
      else out.push(this.ev(a))
    }
    return out
  }

  private scalarNum(n: Node): number | Value {
    return toNum(this.ev(n))
  }

  private scalarText(n: Node): string | Value {
    const v = this.ev(n)
    return v.k === "err" ? v : textOf(v)
  }

  private call(name: string, args: Node[]): Value {
    const arity = ARITY[name]
    if (!arity) return err("#NAME?")
    if (args.length < arity[0] || args.length > arity[1]) return VALUE
    switch (name) {
      case "SUM":
      case "AVERAGE":
      case "MIN":
      case "MAX": {
        const ns = this.numbers(args, false)
        if (!Array.isArray(ns)) return ns
        if (name === "SUM") return num(ns.reduce((s, n) => s + n, 0))
        if (name === "AVERAGE") return ns.length ? num(ns.reduce((s, n) => s + n, 0) / ns.length) : err("#DIV/0!")
        if (!ns.length) return num(0)
        return num(name === "MIN" ? Math.min(...ns) : Math.max(...ns))
      }
      case "COUNT": {
        const ns = this.numbers(args, true) as number[]
        return num(ns.length)
      }
      case "COUNTA": {
        let n = 0
        for (const a of args) {
          if (this.isArea(a)) n += this.cells(a).values.filter((v) => v.k !== "empty").length
          else n++
        }
        return num(n)
      }
      case "COUNTIF":
      case "SUMIF": {
        if (!this.isArea(args[0])) return VALUE
        const t = this.ev(args[1])
        if (t.k === "err") return t
        const test = makeTest(t)
        const area = this.cells(args[0])
        if (name === "COUNTIF") {
          let n = area.values.filter(test).length
          if (area.blanks > 0 && test(EMPTY)) n += area.blanks
          return num(n)
        }
        const sumArea = args[2] ?? args[0]
        if (!this.isArea(sumArea)) return VALUE
        const [r0, c0] = sumArea.k === "ref" ? [sumArea.r, sumArea.c] : sumArea.k === "range" ? [sumArea.r1, sumArea.c1] : [0, 0]
        const [tr, tc] = args[0].k === "ref" ? [args[0].r, args[0].c] : args[0].k === "range" ? [args[0].r1, args[0].c1] : [0, 0]
        let total = 0
        for (let i = 0; i < area.values.length; i++) {
          if (!test(area.values[i])) continue
          const [r, c] = area.at[i]
          const v = this.value(r0 + (r - tr), c0 + (c - tc))
          if (v.k === "err") return v
          if (v.k === "num") total += v.n
        }
        return num(total)
      }
      case "IF": {
        const c = toCond(this.ev(args[0]))
        if (typeof c !== "boolean") return c
        if (c) return this.ev(args[1])
        return args[2] ? this.ev(args[2]) : bool(false)
      }
      case "IFERROR": {
        const v = this.ev(args[0])
        return v.k === "err" ? this.ev(args[1]) : v
      }
      case "AND":
      case "OR": {
        const conds: boolean[] = []
        for (const a of args) {
          if (this.isArea(a)) {
            for (const v of this.cells(a).values) {
              if (v.k === "err") return v
              if (v.k === "num" || v.k === "bool") conds.push(toCond(v) as boolean)
            }
          } else {
            const c = toCond(this.ev(a))
            if (typeof c !== "boolean") return c
            conds.push(c)
          }
        }
        if (!conds.length) return VALUE
        return bool(name === "AND" ? conds.every((c) => c) : conds.some((c) => c))
      }
      case "NOT": {
        const c = toCond(this.ev(args[0]))
        return typeof c === "boolean" ? bool(!c) : c
      }
      case "ROUND": {
        const x = this.scalarNum(args[0])
        if (typeof x !== "number") return x
        const p = args[1] ? this.scalarNum(args[1]) : 0
        if (typeof p !== "number") return p
        return num(Number(roundDec(dec15(x), Math.trunc(p))))
      }
      case "ABS":
      case "SQRT":
      case "INT":
      case "YEAR":
      case "MONTH":
      case "DAY": {
        const x = this.scalarNum(args[0])
        if (typeof x !== "number") return x
        if (name === "ABS") return num(Math.abs(x))
        if (name === "SQRT") return x < 0 ? err("#NUM!") : num(Math.sqrt(x))
        if (name === "INT") return num(Math.floor(x))
        if (x < 0) return err("#NUM!")
        const [y, m, d] = dateOf(x)
        return num(name === "YEAR" ? y : name === "MONTH" ? m : d)
      }
      case "POWER":
      case "MOD": {
        const x = this.scalarNum(args[0])
        if (typeof x !== "number") return x
        const y = this.scalarNum(args[1])
        if (typeof y !== "number") return y
        if (name === "POWER") return power(x, y)
        if (y === 0) return err("#DIV/0!")
        return num(x - y * Math.floor(x / y))
      }
      case "TODAY":
        return num(Math.floor(this.now))
      case "NOW":
        return num(this.now)
      case "DATE": {
        const parts: number[] = []
        for (const a of args) {
          const n = this.scalarNum(a)
          if (typeof n !== "number") return n
          parts.push(Math.trunc(n))
        }
        const [y0, m0, d] = parts
        const y = y0 + Math.floor((m0 - 1) / 12)
        const m = (((m0 - 1) % 12) + 12) % 12 + 1
        const serial = serialOf(y, m, 1) + d - 1
        return serial < 0 ? err("#NUM!") : num(serial)
      }
      case "CONCAT":
      case "CONCATENATE": {
        let s = ""
        for (const v of this.flat(args)) {
          if (v.k === "err") return v
          s += textOf(v)
        }
        return str(s)
      }
      case "LEN":
      case "UPPER":
      case "LOWER":
      case "TRIM": {
        const s = this.scalarText(args[0])
        if (typeof s !== "string") return s
        if (name === "LEN") return num(s.length)
        if (name === "UPPER") return str(s.toUpperCase())
        if (name === "LOWER") return str(s.toLowerCase())
        return str(s.split(" ").filter((w) => w !== "").join(" "))
      }
      case "LEFT":
      case "RIGHT": {
        const s = this.scalarText(args[0])
        if (typeof s !== "string") return s
        const n = args[1] ? this.scalarNum(args[1]) : 1
        if (typeof n !== "number") return n
        const k = Math.trunc(n)
        if (k < 0) return VALUE
        return str(name === "LEFT" ? s.slice(0, k) : s.slice(Math.max(0, s.length - k)))
      }
      case "MID": {
        const s = this.scalarText(args[0])
        if (typeof s !== "string") return s
        const start = this.scalarNum(args[1])
        if (typeof start !== "number") return start
        const n = this.scalarNum(args[2])
        if (typeof n !== "number") return n
        const st = Math.trunc(start)
        const k = Math.trunc(n)
        if (st < 1 || k < 0) return VALUE
        return str(s.slice(st - 1, st - 1 + k))
      }
    }
    return err("#NAME?")
  }
}

function power(x: number, y: number): Value {
  if (x === 0 && y < 0) return err("#DIV/0!")
  const p = Math.pow(x, y)
  return Number.isNaN(p) ? err("#NUM!") : num(p)
}
