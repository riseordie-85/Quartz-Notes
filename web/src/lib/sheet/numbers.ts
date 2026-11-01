// Numbers in sheets: written out to 15 significant digits (as Excel does), rounded on that decimal
// form, shown in each format, and read back from what's typed. Kept in step with the app's
// data/sheet/SheetNumbers.kt; see docs/sheet-format.md and shared/fixtures/sheets/cases.json.

/** `x` written out exactly to 15 significant digits, plain (no exponent), trailing zeros dropped. */
export function dec15(x: number): string {
  if (x === 0 || !Number.isFinite(x)) return "0"
  const neg = x < 0
  const plain = stripZeros(expToPlain(Math.abs(x).toPrecision(15)))
  return plain === "0" ? "0" : (neg ? "-" : "") + plain
}

function expToPlain(s: string): string {
  const m = /^(\d+)(?:\.(\d+))?e([+-]\d+)$/.exec(s)
  if (!m) return s
  const digits = m[1] + (m[2] ?? "")
  const point = m[1].length + Number(m[3])
  if (point <= 0) return "0." + "0".repeat(-point) + digits
  if (point >= digits.length) return digits + "0".repeat(point - digits.length)
  return digits.slice(0, point) + "." + digits.slice(point)
}

function stripZeros(s: string): string {
  if (!s.includes(".")) return s
  return s.replace(/0+$/, "").replace(/\.$/, "")
}

/**
 * Rounds a plain decimal string to `places` decimals (negative: to tens, hundreds…), halves away
 * from zero. Positive `places` keep exactly that many decimals.
 */
export function roundDec(s: string, places: number): string {
  const neg = s.startsWith("-")
  const body = neg ? s.slice(1) : s
  const [intPart, fracPart = ""] = body.split(".")
  // All the digits, with the decimal point after `point` of them; keep the first `keep`.
  const all = intPart + fracPart
  const point = intPart.length
  const keep = point + places
  let kept: string
  if (keep < 0) {
    kept = "0"
  } else {
    const head = all.slice(0, keep).padEnd(keep, "0")
    const next = all.charAt(keep)
    kept = head === "" ? "0" : head
    if (next >= "5" && next <= "9") kept = increment(kept)
  }
  // `kept` holds the digits up to `places` decimals; put the point back.
  let out: string
  if (places > 0) {
    const padded = kept.padStart(places + 1, "0")
    out = padded.slice(0, padded.length - places) + "." + padded.slice(padded.length - places)
  } else {
    out = kept + "0".repeat(-places)
  }
  out = out.replace(/^0+(?=\d)/, "")
  const zero = !/[1-9]/.test(out)
  return neg && !zero ? "-" + out : out
}

function increment(digits: string): string {
  const a = digits.split("")
  let i = a.length - 1
  while (i >= 0) {
    if (a[i] === "9") {
      a[i] = "0"
      i--
    } else {
      a[i] = String(Number(a[i]) + 1)
      return a.join("")
    }
  }
  return "1" + a.join("")
}

/** Where the first significant digit sits: 0 for 1–9.99, 2 for 100–999, -1 for 0.1–0.99. */
function exponentOf(plain: string): number {
  const body = plain.replace("-", "")
  const [i, f = ""] = body.split(".")
  const intDigits = i.replace(/^0+/, "")
  if (intDigits.length > 0) return intDigits.length - 1
  const zeros = f.length - f.replace(/^0+/, "").length
  return -(zeros + 1)
}

/** Rounds a plain decimal string to `sig` significant digits, trailing zeros dropped. */
export function toSig(plain: string, sig: number): string {
  if (!/[1-9]/.test(plain)) return "0"
  return stripZeros(roundDec(plain, sig - 1 - exponentOf(plain)))
}

/** How a number is stored in a cell's `v`. */
export function canonical(x: number): string {
  return dec15(x)
}

/** A number in General form: whole numbers in full, else 10 significant digits; very large or small in E form. */
export function general(x: number): string {
  if (x === 0 || !Number.isFinite(x)) return "0"
  const plain = dec15(x)
  const abs = Math.abs(x)
  if (abs >= 1e15 || abs < 1e-9) return eForm(plain)
  if (!plain.includes(".")) return plain
  return toSig(plain, 10)
}

function eForm(plain: string): string {
  const rounded = toSig(plain, 6)
  const neg = rounded.startsWith("-")
  const e = exponentOf(rounded)
  const digits = rounded.replace("-", "").replace(".", "").replace(/^0+/, "").replace(/0+$/, "")
  const mantissa = digits.length > 1 ? digits[0] + "." + digits.slice(1) : digits
  const exp = Math.abs(e).toString().padStart(2, "0")
  return (neg ? "-" : "") + mantissa + "E" + (e < 0 ? "-" : "+") + exp
}

function grouped(intDigits: string): string {
  return intDigits.replace(/\B(?=(\d{3})+(?!\d))/g, ",")
}

/** `1,234.50` with `dp` places. */
export function fixed(x: number, dp: number, separators = true): string {
  const s = roundDec(dec15(x), dp)
  const neg = s.startsWith("-")
  const [i, f] = s.replace("-", "").split(".")
  const body = (separators ? grouped(i) : i) + (f !== undefined ? "." + f : "")
  return (neg ? "-" : "") + body
}

// ---- Dates: serial numbers, days since 30 December 1899 ----

const EPOCH_OFFSET = 25569 // serial of 1 January 1970

export function daysFromCivil(y: number, m: number, d: number): number {
  const yy = m <= 2 ? y - 1 : y
  const era = Math.floor(yy / 400)
  const yoe = yy - era * 400
  const mp = (m + 9) % 12
  const doy = Math.floor((153 * mp + 2) / 5) + d - 1
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy
  return era * 146097 + doe - 719468
}

export function civilFromDays(z0: number): [number, number, number] {
  const z = z0 + 719468
  const era = Math.floor(z / 146097)
  const doe = z - era * 146097
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365)
  const y = yoe + era * 400
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100))
  const mp = Math.floor((5 * doy + 2) / 153)
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1
  const m = mp < 10 ? mp + 3 : mp - 9
  return [m <= 2 ? y + 1 : y, m, d]
}

export function serialOf(y: number, m: number, d: number): number {
  return daysFromCivil(y, m, d) + EPOCH_OFFSET
}

export function dateOf(serial: number): [number, number, number] {
  return civilFromDays(Math.floor(serial) - EPOCH_OFFSET)
}

function daysInMonth(y: number, m: number): number {
  return [31, (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0 ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]
}

function dateText(serial: number): string {
  const [y, m, d] = dateOf(serial)
  return `${m}/${d}/${y}`
}

function timeText(minutes: number): string {
  const h = Math.floor(minutes / 60)
  const mm = minutes % 60
  const h12 = h % 12 === 0 ? 12 : h % 12
  return `${h12}:${mm.toString().padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`
}

/** A number shown in a cell format. */
export function formatNumber(x: number, fmt: string | undefined, dp: number | undefined): string {
  switch (fmt) {
    case "number":
      return fixed(x, dp ?? 2)
    case "currency": {
      const s = fixed(x, dp ?? 2)
      return s.startsWith("-") ? "-$" + s.slice(1) : "$" + s
    }
    case "percent":
      return fixed(x * 100, dp ?? 0, false) + "%"
    case "date":
      return dateText(x)
    case "time": {
      let minutes = Math.round((x - Math.floor(x)) * 1440)
      if (minutes >= 1440) minutes = 0
      return timeText(minutes)
    }
    case "datetime": {
      let day = Math.floor(x)
      let minutes = Math.round((x - day) * 1440)
      if (minutes >= 1440) {
        minutes = 0
        day++
      }
      return dateText(day) + " " + timeText(minutes)
    }
    default:
      return general(x)
  }
}

// ---- Reading what's typed ----

const NUMBER = /^[+-]?(\d{1,3}(,\d{3})+|\d+)(\.\d*)?([eE][+-]?\d+)?$|^[+-]?\.\d+([eE][+-]?\d+)?$/

/** A typed number (`1,234.5`, `.5`, `1e3`), or null. */
export function parseNumber(s: string): number | null {
  const t = s.trim()
  if (!NUMBER.test(t)) return null
  const n = Number(t.replace(/,/g, ""))
  return Number.isFinite(n) ? n : null
}

/** A stored cell value that is a number (as written by `canonical`). */
export function isStoredNumber(v: string): boolean {
  return /^-?(\d+\.?\d*|\.\d+)$/.test(v)
}

function parseDate(s: string): number | null {
  let m = /^(\d{1,2})\/(\d{1,2})\/(\d{2}|\d{4})$/.exec(s)
  let y: number, mo: number, d: number
  if (m) {
    mo = Number(m[1])
    d = Number(m[2])
    y = m[3].length === 2 ? 2000 + Number(m[3]) : Number(m[3])
  } else {
    m = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(s)
    if (!m) return null
    y = Number(m[1])
    mo = Number(m[2])
    d = Number(m[3])
  }
  if (mo < 1 || mo > 12 || d < 1 || d > daysInMonth(y, mo)) return null
  return serialOf(y, mo, d)
}

function parseTime(s: string): number | null {
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp][Mm])?$/.exec(s)
  if (!m) return null
  let h = Number(m[1])
  const min = Number(m[2])
  const sec = m[3] ? Number(m[3]) : 0
  if (min > 59 || sec > 59) return null
  if (m[4]) {
    if (h < 1 || h > 12) return null
    const pm = m[4].toUpperCase() === "PM"
    h = (h % 12) + (pm ? 12 : 0)
  } else if (h > 23) {
    return null
  }
  return (h * 3600 + min * 60 + sec) / 86400
}

export interface Typed {
  /** The cell's new value; null clears it. */
  v: string | null
  /** A format the typing implies, if any. */
  fmt?: string
}

/** What typing `text` into a cell stores (see "Typing into a cell" in docs/sheet-format.md). */
export function parseInput(text: string): Typed {
  const t = text.trim()
  if (t === "") return { v: null }
  if (t.startsWith("=")) return { v: t }
  if (t.startsWith("'")) return { v: t.slice(1), fmt: "text" }
  const n = parseNumber(t)
  if (n !== null) return { v: canonical(n) }
  let m = /^(-?)\$\s?(.+)$/.exec(t)
  if (m) {
    const c = parseNumber(m[2])
    if (c !== null && !m[2].startsWith("-")) return { v: canonical(m[1] ? -c : c), fmt: "currency" }
  }
  m = /^(.+?)\s?%$/.exec(t)
  if (m) {
    const p = parseNumber(m[1])
    if (p !== null) return { v: canonical(p / 100), fmt: "percent" }
  }
  const date = parseDate(t)
  if (date !== null) return { v: canonical(date), fmt: "date" }
  const time = parseTime(t)
  if (time !== null) return { v: canonical(time), fmt: "time" }
  m = /^(\S+)\s+(.+)$/.exec(t)
  if (m) {
    const d = parseDate(m[1])
    const tm = parseTime(m[2])
    if (d !== null && tm !== null) return { v: canonical(d + tm), fmt: "datetime" }
  }
  return { v: t }
}
