package com.quartz.notes.data.sheet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

// The sheet JSON kept in pages.sheet (docs/sheet-format.md), and cell addresses. Kept in step with
// the web app's web/src/lib/sheet/model.ts.

data class SheetStyle(
    val fmt: String? = null,
    val dp: Int? = null,
    val b: Boolean = false,
    val i: Boolean = false,
    val u: Boolean = false,
    val color: String? = null,
    val fill: String? = null,
    val align: String? = null,
    val wrap: Boolean = false,
    val border: String? = null,
) {
    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = SheetStyle()
    }
}

data class SheetCell(val v: String? = null, val s: SheetStyle = SheetStyle.NONE) {
    val isEmpty: Boolean get() = v == null && s.isEmpty
}

data class SheetData(
    val rows: Int = DEFAULT_ROWS,
    val cols: Int = DEFAULT_COLS,
    /** Column widths by letters; columns not listed are [DEFAULT_WIDTH]. */
    val widths: Map<String, Float> = emptyMap(),
    /** By address ("A1"). */
    val cells: Map<String, SheetCell> = emptyMap(),
    /** Table look: a key of [TABLE_STYLES], or null. */
    val table: String? = null,
    /** Filter: by column letters, the shown values whose rows are hidden. */
    val hide: Map<String, List<String>> = emptyMap(),
) {
    fun cell(r: Int, c: Int): SheetCell? = cells[addr(r, c)]

    fun width(c: Int): Float = widths[colName(c)] ?: DEFAULT_WIDTH

    /** One past the last row and column that hold anything. */
    val usedSize: Pair<Int, Int> by lazy {
        var r = 0
        var c = 0
        for (key in cells.keys) {
            val at = parseAddr(key) ?: continue
            r = maxOf(r, at.first + 1)
            c = maxOf(c, at.second + 1)
        }
        r to c
    }

    fun encode(): String = Json.encodeToString(JsonElement.serializer(), toJson())

    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("rows", rows)
        put("cols", cols)
        putJsonObject("widths") { widths.forEach { (k, w) -> put(k, number(w.toDouble())) } }
        putJsonObject("cells") {
            cells.forEach { (k, cell) ->
                putJsonObject(k) {
                    cell.v?.let { put("v", it) }
                    if (!cell.s.isEmpty) put("s", styleJson(cell.s))
                }
            }
        }
        table?.let { putJsonObject("table") { put("style", it) } }
        if (hide.isNotEmpty()) {
            putJsonObject("hide") { hide.forEach { (k, list) -> put(k, JsonArray(list.map(::JsonPrimitive))) } }
        }
    }

    companion object {
        fun empty() = SheetData()

        /** Reads stored sheet JSON, dropping anything malformed. */
        fun decode(text: String?): SheetData {
            if (text.isNullOrBlank()) return SheetData()
            val el = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return SheetData()
            return fromJson(el)
        }

        fun fromJson(el: JsonElement?): SheetData {
            val x = el as? JsonObject ?: return SheetData()
            val cells = LinkedHashMap<String, SheetCell>()
            var maxR = 0
            var maxC = 0
            (x["cells"] as? JsonObject)?.forEach { (key, value) ->
                val at = parseAddr(key) ?: return@forEach
                val obj = value as? JsonObject ?: return@forEach
                val v = (obj["v"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                val cell = SheetCell(v, readStyle(obj["s"]))
                if (cell.isEmpty) return@forEach
                cells[addr(at.first, at.second)] = cell
                maxR = maxOf(maxR, at.first + 1)
                maxC = maxOf(maxC, at.second + 1)
            }
            fun size(name: String, d: Int): Int {
                val n = (x[name] as? JsonPrimitive)?.doubleOrNull ?: return d
                return if (n.isFinite() && n >= 1) n.toInt() else d
            }
            val widths = LinkedHashMap<String, Float>()
            (x["widths"] as? JsonObject)?.forEach { (k, w) ->
                val n = (w as? JsonPrimitive)?.doubleOrNull
                if (LETTERS.matches(k) && n != null && n.isFinite() && n > 0) widths[k] = n.toFloat()
            }
            val table = ((x["table"] as? JsonObject)?.get("style") as? JsonPrimitive)?.content?.takeIf { it in TABLE_STYLES }
            val hide = LinkedHashMap<String, List<String>>()
            (x["hide"] as? JsonObject)?.forEach { (k, list) ->
                val values = (list as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (LETTERS.matches(k) && !values.isNullOrEmpty()) hide[k] = values
            }
            return SheetData(
                rows = maxOf(size("rows", DEFAULT_ROWS), maxR),
                cols = maxOf(size("cols", DEFAULT_COLS), maxC),
                widths = widths,
                cells = cells,
                table = table,
                hide = hide,
            )
        }

        private fun readStyle(el: JsonElement?): SheetStyle {
            val x = el as? JsonObject ?: return SheetStyle.NONE
            fun str(k: String) = (x[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun flag(k: String) = (x[k] as? JsonPrimitive)?.booleanOrNull == true
            val dp = (x["dp"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
            return SheetStyle(
                fmt = str("fmt"),
                dp = dp?.let { it.roundToInt().coerceIn(0, 10) },
                b = flag("b"),
                i = flag("i"),
                u = flag("u"),
                color = str("color"),
                fill = str("fill"),
                align = str("align"),
                wrap = flag("wrap"),
                border = str("border")?.takeIf { it.isNotEmpty() },
            )
        }

        private fun styleJson(s: SheetStyle): JsonObject = buildJsonObject {
            s.fmt?.let { put("fmt", it) }
            s.dp?.let { put("dp", it) }
            if (s.b) put("b", true)
            if (s.i) put("i", true)
            if (s.u) put("u", true)
            s.color?.let { put("color", it) }
            s.fill?.let { put("fill", it) }
            s.align?.let { put("align", it) }
            if (s.wrap) put("wrap", true)
            s.border?.let { put("border", it) }
        }

        private fun number(d: Double): JsonPrimitive =
            if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

        private val LETTERS = Regex("^[A-Z]{1,3}$")
    }
}

const val DEFAULT_ROWS = 50
const val DEFAULT_COLS = 12
const val DEFAULT_WIDTH = 100f

data class TableStyle(val header: Long, val band: Long)

/** Table looks: header color and the light band, as 0xAARRGGBB. */
val TABLE_STYLES: Map<String, TableStyle> = linkedMapOf(
    "blue" to TableStyle(0xFF2F6FDE, 0xFFEAF1FD),
    "green" to TableStyle(0xFF2E9E5B, 0xFFE8F5EC),
    "orange" to TableStyle(0xFFE07A1F, 0xFFFDF0E3),
    "red" to TableStyle(0xFFD6334C, 0xFFFCE8EB),
    "purple" to TableStyle(0xFF6A3FD0, 0xFFF0EAFB),
    "gray" to TableStyle(0xFF5F6368, 0xFFF1F3F4),
)

/** Column letters for a 0-based column: 0 → A, 26 → AA. */
fun colName(c: Int): String {
    val sb = StringBuilder()
    var n = c + 1
    while (n > 0) {
        val r = (n - 1) % 26
        sb.insert(0, ('A' + r))
        n = (n - 1) / 26
    }
    return sb.toString()
}

/** 0-based column for letters (any case): A → 0. */
fun colIndex(letters: String): Int {
    var n = 0
    for (ch in letters.uppercase()) n = n * 26 + (ch - 'A' + 1)
    return n - 1
}

/** "B3" for 0-based row 2, column 1. */
fun addr(r: Int, c: Int): String = colName(c) + (r + 1)

private val ADDR = Regex("^([A-Za-z]{1,3})(\\d+)$")

/** 0-based (row, column) of "B3", or null. */
fun parseAddr(a: String): Pair<Int, Int>? {
    val m = ADDR.matchEntire(a) ?: return null
    val row = m.groupValues[2].toIntOrNull() ?: return null
    if (row < 1) return null
    return (row - 1) to colIndex(m.groupValues[1])
}
