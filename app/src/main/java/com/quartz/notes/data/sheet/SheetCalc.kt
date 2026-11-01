package com.quartz.notes.data.sheet

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.truncate

// Working out a sheet's values and what each cell shows. Kept in step with the web app's
// web/src/lib/sheet/calc.ts; see docs/sheet-format.md.

sealed interface SheetValue {
    data class Num(val n: Double) : SheetValue
    data class Str(val s: String) : SheetValue
    data class Bool(val b: Boolean) : SheetValue
    data class Err(val e: String) : SheetValue
    data object Empty : SheetValue
}

/** Values of one sheet, worked out as they're asked for. Make a new one after any change. */
class SheetCalc(private val sheet: SheetData, now: Double? = null) {
    private val memo = HashMap<String, SheetValue>()
    private val busy = HashSet<String>()
    private val parsed = HashMap<String, Node>()
    private val lastRow = sheet.usedSize.first
    private val lastCol = sheet.usedSize.second
    private val now: Double = now ?: serialNow()

    /** The value of the cell at 0-based row and column. */
    fun value(r: Int, c: Int): SheetValue {
        val key = addr(r, c)
        memo[key]?.let { return it }
        val cell = sheet.cells[key] ?: return SheetValue.Empty
        val v = cell.v
        if (v.isNullOrEmpty()) return SheetValue.Empty
        var out: SheetValue
        if (cell.s.fmt == "text") {
            out = SheetValue.Str(v)
        } else if (v.startsWith("=")) {
            if (key in busy) return err("#CIRC!")
            busy += key
            val node = parsed.getOrPut(v) { SheetFormula.parse(v) }
            out = ev(node)
            busy -= key
            if (out == SheetValue.Empty) out = num(0.0)
        } else if (SheetNumbers.isStoredNumber(v)) {
            out = num(v.toDouble())
        } else {
            out = SheetValue.Str(v)
        }
        memo[key] = out
        return out
    }

    /** What the cell at 0-based row and column shows. */
    fun display(r: Int, c: Int): String {
        val v = value(r, c)
        if (v is SheetValue.Num) {
            val s = sheet.cells[addr(r, c)]?.s
            return SheetNumbers.format(v.n, s?.fmt, s?.dp)
        }
        return textOf(v)
    }

    // ---- Working out a formula ----

    private fun ev(node: Node): SheetValue = when (node) {
        is Node.Num -> num(node.n)
        is Node.Str -> SheetValue.Str(node.s)
        is Node.Bool -> SheetValue.Bool(node.b)
        is Node.Err -> err(node.e)
        is Node.Name -> err("#NAME?")
        is Node.RefN -> value(node.r, node.c)
        is Node.RangeN -> VALUE
        is Node.Plus -> ev(node.x)
        is Node.Neg -> when (val x = toNum(ev(node.x))) {
            is Double -> num(-x)
            else -> x as SheetValue
        }
        is Node.Pct -> when (val x = toNum(ev(node.x))) {
            is Double -> num(x / 100)
            else -> x as SheetValue
        }
        is Node.Bin -> binary(node.op, node.a, node.b)
        is Node.Call -> call(node.name, node.args)
    }

    private fun binary(op: String, an: Node, bn: Node): SheetValue {
        val a = ev(an)
        if (op == "&") {
            if (a is SheetValue.Err) return a
            val b = ev(bn)
            if (b is SheetValue.Err) return b
            return SheetValue.Str(textOf(a) + textOf(b))
        }
        if (op in COMPARE) {
            if (a is SheetValue.Err) return a
            val b = ev(bn)
            if (b is SheetValue.Err) return b
            return SheetValue.Bool(passes(op, compareValues(a, b)))
        }
        val x = toNum(a) as? Double ?: return toNum(a) as SheetValue
        val bv = toNum(ev(bn))
        val y = bv as? Double ?: return bv as SheetValue
        return when (op) {
            "+" -> num(x + y)
            "-" -> num(x - y)
            "*" -> num(x * y)
            "/" -> if (y == 0.0) err("#DIV/0!") else num(x / y)
            else -> power(x, y)
        }
    }

    private class Area(val values: List<SheetValue>, val blanks: Long, val at: List<Pair<Int, Int>>)

    private fun bounds(n: Node): IntArray? = when (n) {
        is Node.RefN -> intArrayOf(n.r, n.c, n.r, n.c)
        is Node.RangeN -> intArrayOf(n.r1, n.c1, n.r2, n.c2)
        else -> null
    }

    /** Each cell's value in a reference or range (only the used part; `blanks` counts the rest). */
    private fun cells(node: Node): Area {
        val (r1, c1, r2, c2) = bounds(node) ?: intArrayOf(0, 0, -1, -1)
        val values = ArrayList<SheetValue>()
        val at = ArrayList<Pair<Int, Int>>()
        val rEnd = minOf(r2, lastRow - 1)
        val cEnd = minOf(c2, lastCol - 1)
        for (r in r1..rEnd) {
            for (c in c1..cEnd) {
                values += value(r, c)
                at += r to c
            }
        }
        val total = (r2 - r1 + 1).toLong() * (c2 - c1 + 1).toLong()
        return Area(values, total - values.size, at)
    }

    private fun isArea(n: Node) = n is Node.RefN || n is Node.RangeN

    /** The numbers in a "number" function's arguments, or the error that stops it; [skipErrors] for COUNT. */
    private fun numbers(args: List<Node>, skipErrors: Boolean): Any {
        val out = ArrayList<Double>()
        for (a in args) {
            if (isArea(a)) {
                for (v in cells(a).values) {
                    if (v is SheetValue.Num) out += v.n
                    else if (v is SheetValue.Err && !skipErrors) return v
                }
            } else {
                when (val n = toNum(ev(a))) {
                    is Double -> out += n
                    else -> if (!skipErrors) return n
                }
            }
        }
        return out
    }

    private fun flat(args: List<Node>): List<SheetValue> {
        val out = ArrayList<SheetValue>()
        for (a in args) {
            if (isArea(a)) out += cells(a).values.filter { it != SheetValue.Empty } else out += ev(a)
        }
        return out
    }

    /** A number argument: a Double, or the SheetValue error. */
    private fun scalarNum(n: Node): Any = toNum(ev(n))

    private fun scalarText(n: Node): Any {
        val v = ev(n)
        return if (v is SheetValue.Err) v else textOf(v)
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(name: String, args: List<Node>): SheetValue {
        val arity = ARITY[name] ?: return err("#NAME?")
        if (args.size < arity.first || args.size > arity.second) return VALUE
        when (name) {
            "SUM", "AVERAGE", "MIN", "MAX" -> {
                val ns = numbers(args, false) as? List<Double> ?: return numbers(args, false) as SheetValue
                return when (name) {
                    "SUM" -> num(ns.sum())
                    "AVERAGE" -> if (ns.isEmpty()) err("#DIV/0!") else num(ns.sum() / ns.size)
                    "MIN" -> num(ns.minOrNull() ?: 0.0)
                    else -> num(ns.maxOrNull() ?: 0.0)
                }
            }
            "COUNT" -> return num((numbers(args, true) as List<Double>).size.toDouble())
            "COUNTA" -> {
                var n = 0
                for (a in args) {
                    n += if (isArea(a)) cells(a).values.count { it != SheetValue.Empty } else 1
                }
                return num(n.toDouble())
            }
            "COUNTIF", "SUMIF" -> {
                if (!isArea(args[0])) return VALUE
                val t = ev(args[1])
                if (t is SheetValue.Err) return t
                val test = makeTest(t)
                val area = cells(args[0])
                if (name == "COUNTIF") {
                    var n = area.values.count(test).toLong()
                    if (area.blanks > 0 && test(SheetValue.Empty)) n += area.blanks
                    return num(n.toDouble())
                }
                val sumArea = args.getOrNull(2) ?: args[0]
                val sb = bounds(sumArea) ?: return VALUE
                val tb = bounds(args[0])!!
                var total = 0.0
                for (i in area.values.indices) {
                    if (!test(area.values[i])) continue
                    val (r, c) = area.at[i]
                    val v = value(sb[0] + (r - tb[0]), sb[1] + (c - tb[1]))
                    if (v is SheetValue.Err) return v
                    if (v is SheetValue.Num) total += v.n
                }
                return num(total)
            }
            "IF" -> {
                val c = toCond(ev(args[0])) as? Boolean ?: return toCond(ev(args[0])) as SheetValue
                if (c) return ev(args[1])
                return if (args.size > 2) ev(args[2]) else SheetValue.Bool(false)
            }
            "IFERROR" -> {
                val v = ev(args[0])
                return if (v is SheetValue.Err) ev(args[1]) else v
            }
            "AND", "OR" -> {
                val conds = ArrayList<Boolean>()
                for (a in args) {
                    if (isArea(a)) {
                        for (v in cells(a).values) {
                            if (v is SheetValue.Err) return v
                            if (v is SheetValue.Num || v is SheetValue.Bool) conds += toCond(v) as Boolean
                        }
                    } else {
                        when (val c = toCond(ev(a))) {
                            is Boolean -> conds += c
                            else -> return c as SheetValue
                        }
                    }
                }
                if (conds.isEmpty()) return VALUE
                return SheetValue.Bool(if (name == "AND") conds.all { it } else conds.any { it })
            }
            "NOT" -> return when (val c = toCond(ev(args[0]))) {
                is Boolean -> SheetValue.Bool(!c)
                else -> c as SheetValue
            }
            "ROUND" -> {
                val x = scalarNum(args[0]) as? Double ?: return scalarNum(args[0]) as SheetValue
                val p = if (args.size > 1) scalarNum(args[1]) else 0.0
                if (p !is Double) return p as SheetValue
                return num(SheetNumbers.roundDec(SheetNumbers.dec15(x), truncate(p).toInt()).toDouble())
            }
            "ABS", "SQRT", "INT", "YEAR", "MONTH", "DAY" -> {
                val x = scalarNum(args[0]) as? Double ?: return scalarNum(args[0]) as SheetValue
                return when (name) {
                    "ABS" -> num(abs(x))
                    "SQRT" -> if (x < 0) err("#NUM!") else num(sqrt(x))
                    "INT" -> num(floor(x))
                    else -> {
                        if (x < 0) return err("#NUM!")
                        val (y, m, d) = SheetNumbers.dateOf(x)
                        num(if (name == "YEAR") y.toDouble() else if (name == "MONTH") m.toDouble() else d.toDouble())
                    }
                }
            }
            "POWER", "MOD" -> {
                val x = scalarNum(args[0]) as? Double ?: return scalarNum(args[0]) as SheetValue
                val y = scalarNum(args[1]) as? Double ?: return scalarNum(args[1]) as SheetValue
                if (name == "POWER") return power(x, y)
                if (y == 0.0) return err("#DIV/0!")
                return num(x - y * floor(x / y))
            }
            "TODAY" -> return num(floor(now))
            "NOW" -> return num(now)
            "DATE" -> {
                val parts = ArrayList<Long>()
                for (a in args) {
                    val n = scalarNum(a) as? Double ?: return scalarNum(a) as SheetValue
                    parts += truncate(n).toLong()
                }
                val (y0, m0, d) = parts
                val y = y0 + Math.floorDiv(m0 - 1, 12L)
                val m = (Math.floorMod(m0 - 1, 12L) + 1).toInt()
                val serial = SheetNumbers.serialOf(y, m, 1) + d - 1
                return if (serial < 0) err("#NUM!") else num(serial)
            }
            "CONCAT", "CONCATENATE" -> {
                val sb = StringBuilder()
                for (v in flat(args)) {
                    if (v is SheetValue.Err) return v
                    sb.append(textOf(v))
                }
                return SheetValue.Str(sb.toString())
            }
            "LEN", "UPPER", "LOWER", "TRIM" -> {
                val s = scalarText(args[0]) as? String ?: return scalarText(args[0]) as SheetValue
                return when (name) {
                    "LEN" -> num(s.length.toDouble())
                    "UPPER" -> SheetValue.Str(s.uppercase())
                    "LOWER" -> SheetValue.Str(s.lowercase())
                    else -> SheetValue.Str(s.split(" ").filter { it.isNotEmpty() }.joinToString(" "))
                }
            }
            "LEFT", "RIGHT" -> {
                val s = scalarText(args[0]) as? String ?: return scalarText(args[0]) as SheetValue
                val n = if (args.size > 1) scalarNum(args[1]) else 1.0
                if (n !is Double) return n as SheetValue
                val k = truncate(n)
                if (k < 0) return VALUE
                val kk = minOf(k, s.length.toDouble()).toInt()
                return SheetValue.Str(if (name == "LEFT") s.substring(0, kk) else s.substring(s.length - kk))
            }
            "MID" -> {
                val s = scalarText(args[0]) as? String ?: return scalarText(args[0]) as SheetValue
                val start = scalarNum(args[1]) as? Double ?: return scalarNum(args[1]) as SheetValue
                val n = scalarNum(args[2]) as? Double ?: return scalarNum(args[2]) as SheetValue
                val st = truncate(start)
                val k = truncate(n)
                if (st < 1 || k < 0) return VALUE
                val from = minOf(st - 1, s.length.toDouble()).toInt()
                val to = minOf(st - 1 + k, s.length.toDouble()).toInt()
                return SheetValue.Str(s.substring(from, to))
            }
        }
        return err("#NAME?")
    }

    companion object {
        private val VALUE = SheetValue.Err("#VALUE!")
        private val COMPARE = setOf("=", "<>", "<", ">", "<=", ">=")

        private val ARITY: Map<String, Pair<Int, Int>> = mapOf(
            "SUM" to (1 to 255), "AVERAGE" to (1 to 255), "MIN" to (1 to 255), "MAX" to (1 to 255),
            "COUNT" to (1 to 255), "COUNTA" to (1 to 255), "COUNTIF" to (2 to 2), "SUMIF" to (2 to 3),
            "IF" to (2 to 3), "IFERROR" to (2 to 2), "AND" to (1 to 255), "OR" to (1 to 255), "NOT" to (1 to 1),
            "ROUND" to (1 to 2), "ABS" to (1 to 1), "SQRT" to (1 to 1), "POWER" to (2 to 2), "INT" to (1 to 1),
            "MOD" to (2 to 2), "TODAY" to (0 to 0), "NOW" to (0 to 0), "DATE" to (3 to 3), "YEAR" to (1 to 1),
            "MONTH" to (1 to 1), "DAY" to (1 to 1), "CONCAT" to (1 to 255), "CONCATENATE" to (1 to 255),
            "LEN" to (1 to 1), "UPPER" to (1 to 1), "LOWER" to (1 to 1), "TRIM" to (1 to 1),
            "LEFT" to (1 to 2), "RIGHT" to (1 to 2), "MID" to (3 to 3),
        )

        /** The names of the functions sheets know, for suggestions. */
        val FUNCTIONS: List<String> = ARITY.keys.toList()

        /** Serial number for now, in local time. */
        fun serialNow(t: LocalDateTime = LocalDateTime.now()): Double =
            SheetNumbers.serialOf(t.year.toLong(), t.monthValue, t.dayOfMonth) +
                (t.hour * 3600 + t.minute * 60 + t.second) / 86400.0

        private fun err(e: String) = SheetValue.Err(e)

        private fun num(n: Double): SheetValue =
            if (n.isFinite()) SheetValue.Num(if (n == 0.0) 0.0 else n) else SheetValue.Err("#NUM!")

        private fun power(x: Double, y: Double): SheetValue {
            if (x == 0.0 && y < 0) return err("#DIV/0!")
            val p = x.pow(y)
            return if (p.isNaN()) err("#NUM!") else num(p)
        }

        private fun numEq(a: Double, b: Double) = a == b || SheetNumbers.dec15(a) == SheetNumbers.dec15(b)
        private fun numCmp(a: Double, b: Double) = if (numEq(a, b)) 0 else if (a < b) -1 else 1

        private fun strCmp(a: String, b: String): Int {
            val x = a.lowercase()
            val y = b.lowercase()
            return x.compareTo(y).coerceIn(-1, 1)
        }

        private fun passes(op: String, c: Int) = when (op) {
            "=" -> c == 0
            "<>" -> c != 0
            "<" -> c < 0
            ">" -> c > 0
            "<=" -> c <= 0
            else -> c >= 0
        }

        /** A value as text: numbers in General form. */
        fun textOf(v: SheetValue): String = when (v) {
            is SheetValue.Num -> SheetNumbers.general(v.n)
            is SheetValue.Str -> v.s
            is SheetValue.Bool -> if (v.b) "TRUE" else "FALSE"
            is SheetValue.Err -> v.e
            SheetValue.Empty -> ""
        }

        /** A value turned into a number for arithmetic: a Double, or the error SheetValue. */
        private fun toNum(v: SheetValue): Any = when (v) {
            is SheetValue.Num -> v.n
            SheetValue.Empty -> 0.0
            is SheetValue.Bool -> if (v.b) 1.0 else 0.0
            is SheetValue.Str -> SheetNumbers.parseNumber(v.s) ?: VALUE
            is SheetValue.Err -> v
        }

        /** A value as a condition: a Boolean, or the error SheetValue. */
        private fun toCond(v: SheetValue): Any = when (v) {
            is SheetValue.Num -> v.n != 0.0
            is SheetValue.Bool -> v.b
            SheetValue.Empty -> false
            is SheetValue.Str -> when (v.s.trim().uppercase()) {
                "TRUE" -> true
                "FALSE" -> false
                else -> VALUE
            }
            is SheetValue.Err -> v
        }

        private fun rank(v: SheetValue) = when (v) {
            is SheetValue.Num -> 0
            is SheetValue.Str -> 1
            is SheetValue.Bool -> 2
            else -> 3
        }

        private fun emptyLike(other: SheetValue): SheetValue = when (other) {
            is SheetValue.Str -> SheetValue.Str("")
            is SheetValue.Bool -> SheetValue.Bool(false)
            else -> SheetValue.Num(0.0)
        }

        /** Order of two values for comparisons: numbers, then text, then TRUE/FALSE. */
        private fun compareValues(a: SheetValue, b: SheetValue): Int {
            val x = if (a == SheetValue.Empty) emptyLike(b) else a
            val y = if (b == SheetValue.Empty) emptyLike(a) else b
            val rx = rank(x)
            val ry = rank(y)
            if (rx != ry) return if (rx < ry) -1 else 1
            return when {
                x is SheetValue.Num && y is SheetValue.Num -> numCmp(x.n, y.n)
                x is SheetValue.Str && y is SheetValue.Str -> strCmp(x.s, y.s)
                x is SheetValue.Bool && y is SheetValue.Bool -> if (x.b == y.b) 0 else if (x.b) 1 else -1
                else -> 0
            }
        }

        private val CRITERION = Regex("^(<=|>=|<>|=|<|>)?([\\s\\S]*)$")

        /** A COUNTIF/SUMIF test. */
        private fun makeTest(t: SheetValue): (SheetValue) -> Boolean {
            if (t is SheetValue.Num) return { x -> x is SheetValue.Num && numEq(x.n, t.n) }
            if (t is SheetValue.Bool) return { x -> x is SheetValue.Bool && x.b == t.b }
            val isBlank = { x: SheetValue -> x == SheetValue.Empty || (x is SheetValue.Str && x.s.isEmpty()) }
            if (t == SheetValue.Empty) return isBlank
            val s = (t as? SheetValue.Str)?.s ?: ""
            val m = CRITERION.matchEntire(s)!!
            val op = m.groupValues[1].ifEmpty { "=" }
            val rest = m.groupValues[2]
            val n = if (rest.isBlank()) null else SheetNumbers.parseNumber(rest)
            if (n != null) {
                if (op == "<>") return { x -> !(x is SheetValue.Num && numEq(x.n, n)) }
                return { x -> x is SheetValue.Num && passes(op, numCmp(x.n, n)) }
            }
            val sameText = { x: SheetValue -> x is SheetValue.Str && strCmp(x.s, rest) == 0 }
            if (op == "=") return if (rest.isEmpty()) isBlank else sameText
            if (op == "<>") {
                val notBlank = { x: SheetValue -> !isBlank(x) }
                val otherText = { x: SheetValue -> !sameText(x) }
                return if (rest.isEmpty()) notBlank else otherText
            }
            return { x -> x is SheetValue.Str && passes(op, strCmp(x.s, rest)) }
        }
    }
}
