// Reading formulas: tokens, the parse tree, and rewriting the references in a formula's text.
// Kept in step with the app's data/sheet/SheetFormula.kt.

import { colIndex, colName } from "./model"

export interface Ref {
  r: number // 0-based
  c: number
  rAbs: boolean
  cAbs: boolean
}

export type Token =
  | { t: "num"; n: number; start: number; end: number }
  | { t: "str"; s: string; start: number; end: number }
  | { t: "bool"; b: boolean; start: number; end: number }
  | { t: "err"; e: string; start: number; end: number }
  | { t: "ref"; a: Ref; start: number; end: number }
  | { t: "range"; a: Ref; b: Ref; start: number; end: number }
  | { t: "func"; name: string; start: number; end: number }
  | { t: "name"; name: string; start: number; end: number }
  | { t: "op"; op: string; start: number; end: number }

export const ERRORS = ["#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#N/A", "#NUM!", "#CIRC!", "#ERROR!"]

const NUM = /^(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?/
const IDENT = /^[A-Za-z_][A-Za-z0-9_.]*/
const REF = /^(\$?)([A-Za-z]{1,3})(\$?)(\d+)/
const OPS = ["<=", ">=", "<>", "+", "-", "*", "/", "^", "&", "=", "<", ">", "%", "(", ")", ","]

function readRef(s: string, at: number): [Ref, number] | null {
  const m = REF.exec(s.slice(at))
  if (!m) return null
  const row = Number(m[4])
  if (row < 1) return null
  const end = at + m[0].length
  if (end < s.length && /[A-Za-z0-9_.(]/.test(s[end])) return null
  return [{ r: row - 1, c: colIndex(m[2]), cAbs: m[1] === "$", rAbs: m[3] === "$" }, end]
}

/** The tokens of a formula's text (without the leading `=`), or null if it can't be read. */
export function tokenize(s: string): Token[] | null {
  const out: Token[] = []
  let i = 0
  while (i < s.length) {
    const ch = s[i]
    if (ch === " " || ch === "\t" || ch === "\n" || ch === "\r") {
      i++
      continue
    }
    const start = i
    if ((ch >= "0" && ch <= "9") || (ch === "." && /\d/.test(s[i + 1] ?? ""))) {
      const m = NUM.exec(s.slice(i))!
      i += m[0].length
      out.push({ t: "num", n: Number(m[0]), start, end: i })
      continue
    }
    if (ch === '"') {
      let text = ""
      i++
      for (;;) {
        if (i >= s.length) return null
        if (s[i] === '"') {
          if (s[i + 1] === '"') {
            text += '"'
            i += 2
            continue
          }
          i++
          break
        }
        text += s[i++]
      }
      out.push({ t: "str", s: text, start, end: i })
      continue
    }
    if (ch === "#") {
      const e = ERRORS.find((code) => s.startsWith(code, i))
      if (!e) return null
      i += e.length
      out.push({ t: "err", e, start, end: i })
      continue
    }
    if (ch === "$" || /[A-Za-z_]/.test(ch)) {
      const ident = ch === "$" ? null : IDENT.exec(s.slice(i))![0]
      if (ident !== null) {
        let j = i + ident.length
        while (s[j] === " ") j++
        if (s[j] === "(") {
          i += ident.length
          out.push({ t: "func", name: ident.toUpperCase(), start, end: i })
          continue
        }
      }
      const ref = readRef(s, i)
      if (ref) {
        const [a, end] = ref
        if (s[end] === ":") {
          const ref2 = readRef(s, end + 1)
          if (ref2) {
            i = ref2[1]
            out.push({ t: "range", a, b: ref2[0], start, end: i })
            continue
          }
        }
        i = end
        out.push({ t: "ref", a, start, end: i })
        continue
      }
      if (ident === null) return null
      i += ident.length
      const up = ident.toUpperCase()
      if (up === "TRUE" || up === "FALSE") out.push({ t: "bool", b: up === "TRUE", start, end: i })
      else out.push({ t: "name", name: up, start, end: i })
      continue
    }
    const op = OPS.find((o) => s.startsWith(o, i))
    if (!op) return null
    i += op.length
    out.push({ t: "op", op, start, end: i })
  }
  return out
}

// ---- The parse tree ----

export type Node =
  | { k: "num"; n: number }
  | { k: "str"; s: string }
  | { k: "bool"; b: boolean }
  | { k: "err"; e: string }
  | { k: "ref"; r: number; c: number }
  | { k: "range"; r1: number; c1: number; r2: number; c2: number }
  | { k: "name"; name: string }
  | { k: "neg"; x: Node }
  | { k: "plus"; x: Node }
  | { k: "pct"; x: Node }
  | { k: "bin"; op: string; a: Node; b: Node }
  | { k: "call"; name: string; args: Node[] }

const BAD: Node = { k: "err", e: "#ERROR!" }

/** The parse tree of a formula (with or without its `=`); a formula that can't be read gives #ERROR!. */
export function parseFormula(text: string): Node {
  const body = text.startsWith("=") ? text.slice(1) : text
  const tokens = tokenize(body)
  if (!tokens || tokens.length === 0) return BAD
  let pos = 0
  const peekOp = (...ops: string[]): string | null => {
    const t = tokens[pos]
    return t && t.t === "op" && ops.includes(t.op) ? t.op : null
  }
  const fail = (): never => {
    throw new Error("parse")
  }
  const compare = (): Node => {
    let a = concat()
    for (let op = peekOp("=", "<>", "<", ">", "<=", ">="); op; op = peekOp("=", "<>", "<", ">", "<=", ">=")) {
      pos++
      a = { k: "bin", op, a, b: concat() }
    }
    return a
  }
  const concat = (): Node => {
    let a = additive()
    while (peekOp("&")) {
      pos++
      a = { k: "bin", op: "&", a, b: additive() }
    }
    return a
  }
  const additive = (): Node => {
    let a = multiplicative()
    for (let op = peekOp("+", "-"); op; op = peekOp("+", "-")) {
      pos++
      a = { k: "bin", op, a, b: multiplicative() }
    }
    return a
  }
  const multiplicative = (): Node => {
    let a = power()
    for (let op = peekOp("*", "/"); op; op = peekOp("*", "/")) {
      pos++
      a = { k: "bin", op, a, b: power() }
    }
    return a
  }
  const power = (): Node => {
    let a = postfix()
    while (peekOp("^")) {
      pos++
      a = { k: "bin", op: "^", a, b: postfix() }
    }
    return a
  }
  const postfix = (): Node => {
    let a = unary()
    while (peekOp("%")) {
      pos++
      a = { k: "pct", x: a }
    }
    return a
  }
  const unary = (): Node => {
    const op = peekOp("-", "+")
    if (op) {
      pos++
      const x = unary()
      return op === "-" ? { k: "neg", x } : { k: "plus", x }
    }
    return primary()
  }
  const primary = (): Node => {
    const t = tokens[pos++]
    if (!t) return fail()
    switch (t.t) {
      case "num":
        return { k: "num", n: t.n }
      case "str":
        return { k: "str", s: t.s }
      case "bool":
        return { k: "bool", b: t.b }
      case "err":
        return { k: "err", e: t.e }
      case "name":
        return { k: "name", name: t.name }
      case "ref":
        return { k: "ref", r: t.a.r, c: t.a.c }
      case "range":
        return {
          k: "range",
          r1: Math.min(t.a.r, t.b.r),
          c1: Math.min(t.a.c, t.b.c),
          r2: Math.max(t.a.r, t.b.r),
          c2: Math.max(t.a.c, t.b.c),
        }
      case "func": {
        if (!peekOp("(")) return fail()
        pos++
        const args: Node[] = []
        if (peekOp(")")) {
          pos++
        } else {
          for (;;) {
            args.push(compare())
            if (peekOp(",")) {
              pos++
              continue
            }
            if (peekOp(")")) {
              pos++
              break
            }
            fail()
          }
        }
        return { k: "call", name: t.name, args }
      }
      case "op":
        if (t.op === "(") {
          const x = compare()
          if (!peekOp(")")) fail()
          pos++
          return x
        }
        return fail()
    }
  }
  try {
    const node = compare()
    return pos === tokens.length ? node : BAD
  } catch {
    return BAD
  }
}

// ---- Rewriting references ----

export function refText(a: Ref): string {
  return (a.cAbs ? "$" : "") + colName(a.c) + (a.rAbs ? "$" : "") + (a.r + 1)
}

/** What happens to one reference or range: its new text, or null for #REF!. */
export type RefChange =
  | { single: (a: Ref) => Ref | null; range: (a: Ref, b: Ref) => [Ref, Ref] | null }

/** The formula with each reference and range rewritten; text that isn't a formula is returned as is. */
export function rewriteRefs(formula: string, change: RefChange): string {
  if (!formula.startsWith("=")) return formula
  const body = formula.slice(1)
  const tokens = tokenize(body)
  if (!tokens) return formula
  let out = ""
  let last = 0
  for (const t of tokens) {
    let text: string | null = null
    if (t.t === "ref") {
      const a = change.single(t.a)
      text = a ? refText(a) : "#REF!"
      if (a && a.r === t.a.r && a.c === t.a.c) text = null
    } else if (t.t === "range") {
      const ab = change.range(t.a, t.b)
      text = ab ? refText(ab[0]) + ":" + refText(ab[1]) : "#REF!"
      if (ab && ab[0].r === t.a.r && ab[0].c === t.a.c && ab[1].r === t.b.r && ab[1].c === t.b.c) text = null
    }
    if (text !== null) {
      out += body.slice(last, t.start) + text
      last = t.end
    }
  }
  return "=" + out + body.slice(last)
}

/** One axis (rows or columns) of a reference: its 0-based index. */
type Axis = "r" | "c"

function along(a: Ref, axis: Axis, to: number): Ref {
  return axis === "r" ? { ...a, r: to } : { ...a, c: to }
}

/** Inserting `n` rows or columns before 0-based `at`. */
export function insertChange(axis: Axis, at: number, n: number): RefChange {
  const move = (a: Ref) => (a[axis] >= at ? along(a, axis, a[axis] + n) : a)
  return { single: move, range: (a, b) => [move(a), move(b)] }
}

/** Deleting `n` rows or columns from 0-based `at`. */
export function deleteChange(axis: Axis, at: number, n: number): RefChange {
  const end = at + n // first one kept after the deleted run
  return {
    single: (a) => (a[axis] < at ? a : a[axis] >= end ? along(a, axis, a[axis] - n) : null),
    range: (a0, b0) => {
      const [a, b] = a0[axis] <= b0[axis] ? [a0, b0] : [b0, a0]
      const lo = a[axis] < at ? a[axis] : a[axis] >= end ? a[axis] - n : at
      const hi = b[axis] < at ? b[axis] : b[axis] >= end ? b[axis] - n : at - 1
      if (lo > hi) return null
      return [along(a, axis, lo), along(b, axis, hi)]
    },
  }
}

/** Copying a formula `dr` rows down and `dc` columns across; `$` parts stay. */
export function shiftChange(dr: number, dc: number): RefChange {
  const move = (a: Ref): Ref | null => {
    const r = a.rAbs ? a.r : a.r + dr
    const c = a.cAbs ? a.c : a.c + dc
    return r < 0 || c < 0 ? null : { ...a, r, c }
  }
  return {
    single: move,
    range: (a, b) => {
      const ma = move(a)
      const mb = move(b)
      return ma && mb ? [ma, mb] : null
    },
  }
}
