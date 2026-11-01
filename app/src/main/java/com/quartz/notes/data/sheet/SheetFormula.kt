package com.quartz.notes.data.sheet

// Reading formulas: tokens, the parse tree, and rewriting the references in a formula's text.
// Kept in step with the web app's web/src/lib/sheet/formula.ts.

/** A cell reference: 0-based row and column, and which parts are fixed with `$`. */
data class Ref(val r: Int, val c: Int, val rAbs: Boolean = false, val cAbs: Boolean = false) {
    fun text(): String = (if (cAbs) "$" else "") + colName(c) + (if (rAbs) "$" else "") + (r + 1)
}

sealed interface Token {
    val start: Int
    val end: Int

    data class Num(val n: Double, override val start: Int, override val end: Int) : Token
    data class Str(val s: String, override val start: Int, override val end: Int) : Token
    data class Bool(val b: Boolean, override val start: Int, override val end: Int) : Token
    data class Err(val e: String, override val start: Int, override val end: Int) : Token
    data class RefT(val a: Ref, override val start: Int, override val end: Int) : Token
    data class RangeT(val a: Ref, val b: Ref, override val start: Int, override val end: Int) : Token
    data class Func(val name: String, override val start: Int, override val end: Int) : Token
    data class Name(val name: String, override val start: Int, override val end: Int) : Token
    data class Op(val op: String, override val start: Int, override val end: Int) : Token
}

sealed interface Node {
    data class Num(val n: Double) : Node
    data class Str(val s: String) : Node
    data class Bool(val b: Boolean) : Node
    data class Err(val e: String) : Node
    data class RefN(val r: Int, val c: Int) : Node
    data class RangeN(val r1: Int, val c1: Int, val r2: Int, val c2: Int) : Node
    data class Name(val name: String) : Node
    data class Neg(val x: Node) : Node
    data class Plus(val x: Node) : Node
    data class Pct(val x: Node) : Node
    data class Bin(val op: String, val a: Node, val b: Node) : Node
    data class Call(val name: String, val args: List<Node>) : Node
}

/** What happens to one reference or range; null means it becomes #REF!. */
class RefChange(val single: (Ref) -> Ref?, val range: (Ref, Ref) -> Pair<Ref, Ref>?)

object SheetFormula {
    val ERRORS = listOf("#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#N/A", "#NUM!", "#CIRC!", "#ERROR!")

    private val NUM = Regex("^(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_.]*")
    private val REF = Regex("^(\\$?)([A-Za-z]{1,3})(\\$?)(\\d+)")
    private val OPS = listOf("<=", ">=", "<>", "+", "-", "*", "/", "^", "&", "=", "<", ">", "%", "(", ")", ",")

    private fun isLetter(ch: Char) = ch in 'A'..'Z' || ch in 'a'..'z' || ch == '_'
    private fun isDigit(ch: Char) = ch in '0'..'9'

    private fun readRef(s: String, at: Int): Pair<Ref, Int>? {
        val m = REF.find(s.substring(at)) ?: return null
        val row = m.groupValues[4].toIntOrNull() ?: return null
        if (row < 1) return null
        val end = at + m.value.length
        if (end < s.length) {
            val ch = s[end]
            if (isLetter(ch) || isDigit(ch) || ch == '.' || ch == '(') return null
        }
        return Ref(row - 1, colIndex(m.groupValues[2]), cAbs = m.groupValues[1] == "$", rAbs = m.groupValues[3] == "$") to end
    }

    /** The tokens of a formula's text (without the leading `=`), or null if it can't be read. */
    fun tokenize(s: String): List<Token>? {
        val out = ArrayList<Token>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                i++
                continue
            }
            val start = i
            if (isDigit(ch) || (ch == '.' && i + 1 < s.length && isDigit(s[i + 1]))) {
                val m = NUM.find(s.substring(i))!!
                i += m.value.length
                out += Token.Num(m.value.toDouble(), start, i)
                continue
            }
            if (ch == '"') {
                val text = StringBuilder()
                i++
                while (true) {
                    if (i >= s.length) return null
                    if (s[i] == '"') {
                        if (i + 1 < s.length && s[i + 1] == '"') {
                            text.append('"')
                            i += 2
                            continue
                        }
                        i++
                        break
                    }
                    text.append(s[i++])
                }
                out += Token.Str(text.toString(), start, i)
                continue
            }
            if (ch == '#') {
                val e = ERRORS.firstOrNull { s.startsWith(it, i) } ?: return null
                i += e.length
                out += Token.Err(e, start, i)
                continue
            }
            if (ch == '$' || isLetter(ch)) {
                val ident = if (ch == '$') null else IDENT.find(s.substring(i))!!.value
                if (ident != null) {
                    var j = i + ident.length
                    while (j < s.length && s[j] == ' ') j++
                    if (j < s.length && s[j] == '(') {
                        i += ident.length
                        out += Token.Func(ident.uppercase(), start, i)
                        continue
                    }
                }
                val ref = readRef(s, i)
                if (ref != null) {
                    val (a, end) = ref
                    if (end < s.length && s[end] == ':') {
                        val ref2 = readRef(s, end + 1)
                        if (ref2 != null) {
                            i = ref2.second
                            out += Token.RangeT(a, ref2.first, start, i)
                            continue
                        }
                    }
                    i = end
                    out += Token.RefT(a, start, i)
                    continue
                }
                if (ident == null) return null
                i += ident.length
                val up = ident.uppercase()
                out += if (up == "TRUE" || up == "FALSE") Token.Bool(up == "TRUE", start, i) else Token.Name(up, start, i)
                continue
            }
            val op = OPS.firstOrNull { s.startsWith(it, i) } ?: return null
            i += op.length
            out += Token.Op(op, start, i)
        }
        return out
    }

    private val BAD = Node.Err("#ERROR!")
    private class ParseError : RuntimeException()

    /** The parse tree of a formula (with or without its `=`); a formula that can't be read gives #ERROR!. */
    fun parse(text: String): Node {
        val body = text.removePrefix("=")
        val tokens = tokenize(body)
        if (tokens.isNullOrEmpty()) return BAD
        return try {
            val p = Parser(tokens)
            val node = p.compare()
            if (p.pos == tokens.size) node else BAD
        } catch (_: ParseError) {
            BAD
        }
    }

    private class Parser(val tokens: List<Token>) {
        var pos = 0

        fun peekOp(vararg ops: String): String? {
            val t = tokens.getOrNull(pos) as? Token.Op ?: return null
            return if (t.op in ops) t.op else null
        }

        fun fail(): Nothing = throw ParseError()

        fun compare(): Node {
            var a = concat()
            while (true) {
                val op = peekOp("=", "<>", "<", ">", "<=", ">=") ?: break
                pos++
                a = Node.Bin(op, a, concat())
            }
            return a
        }

        fun concat(): Node {
            var a = additive()
            while (peekOp("&") != null) {
                pos++
                a = Node.Bin("&", a, additive())
            }
            return a
        }

        fun additive(): Node {
            var a = multiplicative()
            while (true) {
                val op = peekOp("+", "-") ?: break
                pos++
                a = Node.Bin(op, a, multiplicative())
            }
            return a
        }

        fun multiplicative(): Node {
            var a = power()
            while (true) {
                val op = peekOp("*", "/") ?: break
                pos++
                a = Node.Bin(op, a, power())
            }
            return a
        }

        fun power(): Node {
            var a = postfix()
            while (peekOp("^") != null) {
                pos++
                a = Node.Bin("^", a, postfix())
            }
            return a
        }

        fun postfix(): Node {
            var a = unary()
            while (peekOp("%") != null) {
                pos++
                a = Node.Pct(a)
            }
            return a
        }

        fun unary(): Node {
            val op = peekOp("-", "+")
            if (op != null) {
                pos++
                val x = unary()
                return if (op == "-") Node.Neg(x) else Node.Plus(x)
            }
            return primary()
        }

        fun primary(): Node {
            val t = tokens.getOrNull(pos++) ?: fail()
            return when (t) {
                is Token.Num -> Node.Num(t.n)
                is Token.Str -> Node.Str(t.s)
                is Token.Bool -> Node.Bool(t.b)
                is Token.Err -> Node.Err(t.e)
                is Token.Name -> Node.Name(t.name)
                is Token.RefT -> Node.RefN(t.a.r, t.a.c)
                is Token.RangeT -> Node.RangeN(
                    minOf(t.a.r, t.b.r), minOf(t.a.c, t.b.c), maxOf(t.a.r, t.b.r), maxOf(t.a.c, t.b.c),
                )
                is Token.Func -> {
                    if (peekOp("(") == null) fail()
                    pos++
                    val args = ArrayList<Node>()
                    if (peekOp(")") != null) {
                        pos++
                    } else {
                        while (true) {
                            args += compare()
                            if (peekOp(",") != null) {
                                pos++
                                continue
                            }
                            if (peekOp(")") != null) {
                                pos++
                                break
                            }
                            fail()
                        }
                    }
                    Node.Call(t.name, args)
                }
                is Token.Op -> {
                    if (t.op != "(") fail()
                    val x = compare()
                    if (peekOp(")") == null) fail()
                    pos++
                    x
                }
            }
        }
    }

    // ---- Rewriting references ----

    /** The formula with each reference and range rewritten; text that isn't a formula is returned as is. */
    fun rewriteRefs(formula: String, change: RefChange): String {
        if (!formula.startsWith("=")) return formula
        val body = formula.substring(1)
        val tokens = tokenize(body) ?: return formula
        val out = StringBuilder("=")
        var last = 0
        for (t in tokens) {
            var text: String? = null
            if (t is Token.RefT) {
                val a = change.single(t.a)
                text = a?.text() ?: "#REF!"
                if (a != null && a.r == t.a.r && a.c == t.a.c) text = null
            } else if (t is Token.RangeT) {
                val ab = change.range(t.a, t.b)
                text = if (ab != null) ab.first.text() + ":" + ab.second.text() else "#REF!"
                if (ab != null && ab.first.r == t.a.r && ab.first.c == t.a.c && ab.second.r == t.b.r && ab.second.c == t.b.c) text = null
            }
            if (text != null) {
                out.append(body, last, t.start).append(text)
                last = t.end
            }
        }
        return out.append(body.substring(last)).toString()
    }

    private fun Ref.along(rows: Boolean, to: Int): Ref = if (rows) copy(r = to) else copy(c = to)
    private fun Ref.at(rows: Boolean): Int = if (rows) r else c

    /** Inserting [n] rows (or columns) before 0-based [at]. */
    fun insertChange(rows: Boolean, at: Int, n: Int): RefChange {
        val move = { a: Ref -> if (a.at(rows) >= at) a.along(rows, a.at(rows) + n) else a }
        return RefChange(move) { a, b -> move(a) to move(b) }
    }

    /** Deleting [n] rows (or columns) from 0-based [at]. */
    fun deleteChange(rows: Boolean, at: Int, n: Int): RefChange {
        val end = at + n
        return RefChange(
            single = { a ->
                val i = a.at(rows)
                if (i < at) a else if (i >= end) a.along(rows, i - n) else null
            },
            range = { a0, b0 ->
                val (a, b) = if (a0.at(rows) <= b0.at(rows)) a0 to b0 else b0 to a0
                val ai = a.at(rows)
                val bi = b.at(rows)
                val lo = if (ai < at) ai else if (ai >= end) ai - n else at
                val hi = if (bi < at) bi else if (bi >= end) bi - n else at - 1
                if (lo > hi) null else a.along(rows, lo) to b.along(rows, hi)
            },
        )
    }

    /** Copying a formula [dr] rows down and [dc] columns across; `$` parts stay. */
    fun shiftChange(dr: Int, dc: Int): RefChange {
        val move = { a: Ref ->
            val r = if (a.rAbs) a.r else a.r + dr
            val c = if (a.cAbs) a.c else a.c + dc
            if (r < 0 || c < 0) null else a.copy(r = r, c = c)
        }
        return RefChange(move) { a, b ->
            val ma = move(a)
            val mb = move(b)
            if (ma != null && mb != null) ma to mb else null
        }
    }
}
