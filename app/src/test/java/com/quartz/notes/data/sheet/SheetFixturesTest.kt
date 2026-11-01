package com.quartz.notes.data.sheet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The shared sheet cases (shared/fixtures/sheets/cases.json), which the web app's tests also run,
 * so a sheet works out and shows the same on both.
 */
class SheetFixturesTest {
    private val file = listOf(File("../shared/fixtures/sheets/cases.json"), File("shared/fixtures/sheets/cases.json")).first { it.isFile }
    private val cases = Json.parseToJsonElement(file.readText()).jsonObject

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun sheetOf(cells: JsonObject, styles: JsonObject? = null): SheetData {
        val map = LinkedHashMap<String, SheetCell>()
        cells.forEach { (k, v) -> map[k] = SheetCell(v.jsonPrimitive.content) }
        val withStyles = JsonObject(
            buildMap {
                map.forEach { (k, c) -> put(k, JsonObject(mapOf("v" to JsonPrimitive(c.v)))) }
                styles?.forEach { (k, s) ->
                    val old = (get(k) as? JsonObject) ?: JsonObject(emptyMap())
                    put(k, JsonObject(old + ("s" to s)))
                }
            },
        )
        return SheetData.fromJson(JsonObject(mapOf("cells" to withStyles)))
    }

    private fun at(a: String): Pair<Int, Int> = parseAddr(a)!!

    @Test
    fun typing() {
        for (c in cases["input"]!!.jsonArray.map { it.jsonObject }) {
            val typed = c.str("typed")!!
            val t = SheetNumbers.parseInput(typed)
            if (c["clear"]?.jsonPrimitive?.booleanOrNull == true) assertNull(typed, t.v) else assertEquals(typed, c.str("v"), t.v)
            assertEquals(typed, c.str("fmt"), t.fmt)
        }
    }

    @Test
    fun showingNumbers() {
        for (c in cases["format"]!!.jsonArray.map { it.jsonObject }) {
            val value = c["value"]!!.jsonPrimitive.double
            val fmt = c.str("fmt")
            val dp = c["dp"]?.jsonPrimitive?.intOrNull
            assertEquals("$value $fmt $dp", c.str("out"), SheetNumbers.format(value, fmt, dp))
        }
    }

    @Test
    fun formulas() {
        for (s in cases["sheets"]!!.jsonArray.map { it.jsonObject }) {
            val cells = s["cells"]!!.jsonObject
            val calc = SheetCalc(sheetOf(cells, s["styles"] as? JsonObject), s["now"]?.jsonPrimitive?.doubleOrNull)
            for ((a, out) in s["expect"]!!.jsonObject) {
                val (r, c) = at(a)
                assertEquals("${s.str("name")}: $a ${cells.str(a)}", out.jsonPrimitive.content, calc.display(r, c))
            }
        }
    }

    @Test
    fun changingTheGrid() {
        for (c in cases["adjust"]!!.jsonArray.map { it.jsonObject }) {
            val op = c.str("op")!!
            val atN = c["at"]?.jsonPrimitive?.int ?: 0
            val n = c["n"]?.jsonPrimitive?.int ?: 0
            val change = when (op) {
                "insertRows" -> SheetFormula.insertChange(true, atN - 1, n)
                "deleteRows" -> SheetFormula.deleteChange(true, atN - 1, n)
                "insertCols" -> SheetFormula.insertChange(false, atN - 1, n)
                "deleteCols" -> SheetFormula.deleteChange(false, atN - 1, n)
                else -> SheetFormula.shiftChange(c["dr"]!!.jsonPrimitive.int, c["dc"]!!.jsonPrimitive.int)
            }
            val formula = c.str("formula")!!
            assertEquals("$formula $op", c.str("out"), SheetFormula.rewriteRefs(formula, change))
        }
    }

    @Test
    fun movingCells() {
        var s = sheetOf(Json.parseToJsonElement("""{"A1":"1","A2":"2","A3":"=A1+A2","C1":"x"}""").jsonObject)
        s = SheetGrid.insertRows(s, 1, 1)
        assertEquals("2", s.cells["A3"]?.v)
        assertEquals("=A1+A3", s.cells["A4"]?.v)
        assertEquals("3", SheetCalc(s).display(3, 0))
        s = SheetGrid.deleteCols(s, 1, 1)
        assertEquals("x", s.cells["B1"]?.v)
    }

    @Test
    fun sorting() {
        val sort = cases["sort"]!!.jsonObject
        for (c in sort["cases"]!!.jsonArray.map { it.jsonObject }) {
            val col = at(c.str("col")!! + "1").second
            val from = c["from"]!!.jsonPrimitive.int
            val to = c["to"]!!.jsonPrimitive.int
            val desc = c["desc"]!!.jsonPrimitive.boolean
            val sorted = SheetGrid.sortRows(sheetOf(sort["cells"]!!.jsonObject), col, desc, from - 1, to - 1)
            val names = (from..to).map { sorted.cells["A$it"]?.v }
            assertEquals("${c.str("col")} $desc", c["order"]!!.jsonArray.map { it.jsonPrimitive.content }, names)
        }
    }

    @Test
    fun filtering() {
        val sort = cases["sort"]!!.jsonObject
        val hide = sort["hide"]!!.jsonObject.mapValues { (_, v) -> (v as JsonArray).map { it.jsonPrimitive.content } }
        val s = sheetOf(sort["cells"]!!.jsonObject).copy(hide = hide)
        val expected = sort["hidden"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(expected, SheetGrid.hiddenRows(s).map { it + 1 }.sorted())
    }

    @Test
    fun searchText() {
        val p = cases["plainText"]!!.jsonObject
        assertEquals(p.str("out"), SheetGrid.plainText(sheetOf(p["cells"]!!.jsonObject)))
    }

    @Test
    fun typingKeepsFormats() {
        var s = SheetGrid.typeInto(SheetData(), 0, 0, "$5")
        assertEquals(SheetCell("5", SheetStyle(fmt = "currency")), s.cells["A1"])
        s = SheetGrid.typeInto(s, 0, 0, "7")
        assertEquals(SheetCell("7", SheetStyle(fmt = "currency")), s.cells["A1"])
        s = SheetGrid.typeInto(s, 0, 0, "")
        assertEquals(SheetCell(null, SheetStyle(fmt = "currency")), s.cells["A1"])
    }

    @Test
    fun savesAndReadsBack() {
        val s = SheetGrid.typeInto(SheetData(widths = mapOf("A" to 140f), table = "blue"), 1, 1, "12.5%")
        val again = SheetData.decode(s.encode())
        assertEquals(s.cells, again.cells)
        assertEquals(s.widths, again.widths)
        assertEquals("blue", again.table)
        assertEquals(s.rows, again.rows)
    }
}
