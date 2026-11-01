package com.quartz.notes.data.sheet

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

// Numbers in sheets: written out to 15 significant digits (as Excel does), rounded on that decimal
// form, shown in each format, and read back from what's typed. Kept in step with the web app's
// web/src/lib/sheet/numbers.ts; see docs/sheet-format.md and shared/fixtures/sheets/cases.json.
object SheetNumbers {
    private val MC15 = MathContext(15, RoundingMode.HALF_UP)

    /** [x] written out exactly to 15 significant digits, plain (no exponent), trailing zeros dropped. */
    fun dec15(x: Double): String {
        if (x == 0.0 || !x.isFinite()) return "0"
        val s = BigDecimal(x).round(MC15).stripTrailingZeros().toPlainString()
        return if (s == "0" || s == "-0") "0" else s
    }

    private fun stripZeros(s: String): String {
        if (!s.contains('.')) return s
        return s.trimEnd('0').trimEnd('.')
    }

    private val LEADING_ZEROS = Regex("^0+(?=\\d)")

    /**
     * Rounds a plain decimal string to [places] decimals (negative: to tens, hundreds…), halves away
     * from zero. Positive [places] keep exactly that many decimals.
     */
    fun roundDec(s: String, places: Int): String {
        val neg = s.startsWith("-")
        val body = if (neg) s.substring(1) else s
        val dot = body.indexOf('.')
        val intPart = if (dot < 0) body else body.substring(0, dot)
        val fracPart = if (dot < 0) "" else body.substring(dot + 1)
        // All the digits, with the decimal point after `point` of them; keep the first `keep`.
        val all = intPart + fracPart
        val point = intPart.length
        val keep = point + places
        var kept: String
        if (keep < 0) {
            kept = "0"
        } else {
            val head = (if (keep <= all.length) all.substring(0, keep) else all).padEnd(keep, '0')
            val next = if (keep < all.length) all[keep] else ' '
            kept = if (head.isEmpty()) "0" else head
            if (next in '5'..'9') kept = increment(kept)
        }
        var out = if (places > 0) {
            val padded = kept.padStart(places + 1, '0')
            padded.substring(0, padded.length - places) + "." + padded.substring(padded.length - places)
        } else {
            kept + "0".repeat(-places)
        }
        out = out.replace(LEADING_ZEROS, "")
        val zero = out.none { it in '1'..'9' }
        return if (neg && !zero) "-$out" else out
    }

    private fun increment(digits: String): String {
        val a = digits.toCharArray()
        var i = a.size - 1
        while (i >= 0) {
            if (a[i] == '9') {
                a[i] = '0'
                i--
            } else {
                a[i] = a[i] + 1
                return String(a)
            }
        }
        return "1" + String(a)
    }

    /** Where the first significant digit sits: 0 for 1–9.99, 2 for 100–999, -1 for 0.1–0.99. */
    private fun exponentOf(plain: String): Int {
        val body = plain.replace("-", "")
        val dot = body.indexOf('.')
        val i = if (dot < 0) body else body.substring(0, dot)
        val f = if (dot < 0) "" else body.substring(dot + 1)
        val intDigits = i.trimStart('0')
        if (intDigits.isNotEmpty()) return intDigits.length - 1
        val zeros = f.length - f.trimStart('0').length
        return -(zeros + 1)
    }

    /** Rounds a plain decimal string to [sig] significant digits, trailing zeros dropped. */
    fun toSig(plain: String, sig: Int): String {
        if (plain.none { it in '1'..'9' }) return "0"
        return stripZeros(roundDec(plain, sig - 1 - exponentOf(plain)))
    }

    /** How a number is stored in a cell's `v`. */
    fun canonical(x: Double): String = dec15(x)

    /** A number in General form: whole numbers in full, else 10 significant digits; very large or small in E form. */
    fun general(x: Double): String {
        if (x == 0.0 || !x.isFinite()) return "0"
        val plain = dec15(x)
        val a = abs(x)
        if (a >= 1e15 || a < 1e-9) return eForm(plain)
        if (!plain.contains('.')) return plain
        return toSig(plain, 10)
    }

    private fun eForm(plain: String): String {
        val rounded = toSig(plain, 6)
        val neg = rounded.startsWith("-")
        val e = exponentOf(rounded)
        val digits = rounded.replace("-", "").replace(".", "").trimStart('0').trimEnd('0')
        val mantissa = if (digits.length > 1) digits.substring(0, 1) + "." + digits.substring(1) else digits
        val exp = abs(e).toString().padStart(2, '0')
        return (if (neg) "-" else "") + mantissa + "E" + (if (e < 0) "-" else "+") + exp
    }

    private fun grouped(intDigits: String): String {
        val sb = StringBuilder()
        intDigits.forEachIndexed { i, ch ->
            if (i > 0 && (intDigits.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return sb.toString()
    }

    /** `1,234.50` with [dp] places. */
    fun fixed(x: Double, dp: Int, separators: Boolean = true): String {
        val s = roundDec(dec15(x), dp)
        val neg = s.startsWith("-")
        val body0 = s.removePrefix("-")
        val dot = body0.indexOf('.')
        val i = if (dot < 0) body0 else body0.substring(0, dot)
        val f = if (dot < 0) null else body0.substring(dot + 1)
        val body = (if (separators) grouped(i) else i) + (if (f != null) ".$f" else "")
        return (if (neg) "-" else "") + body
    }

    // ---- Dates: serial numbers, days since 30 December 1899 ----

    private const val EPOCH_OFFSET = 25569 // serial of 1 January 1970

    private fun fdiv(a: Long, b: Long): Long = Math.floorDiv(a, b)

    fun daysFromCivil(y: Long, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = fdiv(yy, 400)
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719468
        val era = fdiv(z, 146097)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple(if (m <= 2) y + 1 else y, m, d)
    }

    fun serialOf(y: Long, m: Int, d: Int): Double = (daysFromCivil(y, m, d) + EPOCH_OFFSET).toDouble()

    fun dateOf(serial: Double): Triple<Long, Int, Int> = civilFromDays(floor(serial).toLong() - EPOCH_OFFSET)

    private fun daysInMonth(y: Long, m: Int): Int {
        val leap = (y % 4 == 0L && y % 100 != 0L) || y % 400 == 0L
        return intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[m - 1]
    }

    private fun dateText(serial: Double): String {
        val (y, m, d) = dateOf(serial)
        return "$m/$d/$y"
    }

    private fun timeText(minutes: Int): String {
        val h = minutes / 60
        val mm = minutes % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${mm.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
    }

    /** A number shown in a cell format. */
    fun format(x: Double, fmt: String?, dp: Int?): String = when (fmt) {
        "number" -> fixed(x, dp ?: 2)
        "currency" -> fixed(x, dp ?: 2).let { if (it.startsWith("-")) "-\$" + it.substring(1) else "\$" + it }
        "percent" -> fixed(x * 100, dp ?: 0, separators = false) + "%"
        "date" -> dateText(x)
        "time" -> {
            var minutes = Math.round((x - floor(x)) * 1440).toInt()
            if (minutes >= 1440) minutes = 0
            timeText(minutes)
        }
        "datetime" -> {
            var day = floor(x)
            var minutes = Math.round((x - day) * 1440).toInt()
            if (minutes >= 1440) {
                minutes = 0
                day++
            }
            dateText(day) + " " + timeText(minutes)
        }
        else -> general(x)
    }

    // ---- Reading what's typed ----

    private val NUMBER = Regex("^[+-]?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d*)?([eE][+-]?\\d+)?$|^[+-]?\\.\\d+([eE][+-]?\\d+)?$")
    private val STORED = Regex("^-?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    /** A typed number (`1,234.5`, `.5`, `1e3`), or null. */
    fun parseNumber(s: String): Double? {
        val t = s.trim()
        if (!NUMBER.matches(t)) return null
        val n = t.replace(",", "").toDoubleOrNull() ?: return null
        return if (n.isFinite()) n else null
    }

    /** A stored cell value that is a number. */
    fun isStoredNumber(v: String): Boolean = STORED.matches(v)

    private val DATE_US = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{2}|\\d{4})$")
    private val DATE_ISO = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
    private val TIME = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([AaPp][Mm])?$")
    private val CURRENCY = Regex("^(-?)\\$\\s?(.+)$")
    private val PERCENT = Regex("^(.+?)\\s?%$")
    private val DATETIME = Regex("^(\\S+)\\s+(.+)$")

    private fun parseDate(s: String): Double? {
        val y: Long
        val mo: Int
        val d: Int
        val us = DATE_US.matchEntire(s)
        if (us != null) {
            mo = us.groupValues[1].toInt()
            d = us.groupValues[2].toInt()
            val yt = us.groupValues[3]
            y = if (yt.length == 2) 2000L + yt.toLong() else yt.toLong()
        } else {
            val iso = DATE_ISO.matchEntire(s) ?: return null
            y = iso.groupValues[1].toLong()
            mo = iso.groupValues[2].toInt()
            d = iso.groupValues[3].toInt()
        }
        if (mo < 1 || mo > 12 || d < 1 || d > daysInMonth(y, mo)) return null
        return serialOf(y, mo, d)
    }

    private fun parseTime(s: String): Double? {
        val m = TIME.matchEntire(s) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val sec = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (min > 59 || sec > 59) return null
        val ampm = m.groupValues[4]
        if (ampm.isNotEmpty()) {
            if (h < 1 || h > 12) return null
            h = h % 12 + if (ampm.uppercase() == "PM") 12 else 0
        } else if (h > 23) {
            return null
        }
        return (h * 3600 + min * 60 + sec) / 86400.0
    }

    /** What typing into a cell stores: the new value (null clears it) and a format it implies. */
    data class Typed(val v: String?, val fmt: String? = null)

    /** What typing [text] into a cell stores (see "Typing into a cell" in docs/sheet-format.md). */
    fun parseInput(text: String): Typed {
        val t = text.trim()
        if (t.isEmpty()) return Typed(null)
        if (t.startsWith("=")) return Typed(t)
        if (t.startsWith("'")) return Typed(t.substring(1), "text")
        parseNumber(t)?.let { return Typed(canonical(it)) }
        CURRENCY.matchEntire(t)?.let { m ->
            val c = parseNumber(m.groupValues[2])
            if (c != null && !m.groupValues[2].startsWith("-")) {
                return Typed(canonical(if (m.groupValues[1].isNotEmpty()) -c else c), "currency")
            }
        }
        PERCENT.matchEntire(t)?.let { m ->
            parseNumber(m.groupValues[1])?.let { return Typed(canonical(it / 100), "percent") }
        }
        parseDate(t)?.let { return Typed(canonical(it), "date") }
        parseTime(t)?.let { return Typed(canonical(it), "time") }
        DATETIME.matchEntire(t)?.let { m ->
            val d = parseDate(m.groupValues[1])
            val tm = parseTime(m.groupValues[2])
            if (d != null && tm != null) return Typed(canonical(d + tm), "datetime")
        }
        return Typed(t)
    }
}
