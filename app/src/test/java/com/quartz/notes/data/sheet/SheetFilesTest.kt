package com.quartz.notes.data.sheet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Excel and CSV files: the shared sample files (shared/fixtures/sheets, also read by the web app's
 * tests) read as expected, and a sheet written out reads back the same.
 */
class SheetFilesTest {
    private val dir = listOf(File("../shared/fixtures/sheets"), File("shared/fixtures/sheets")).first { it.isDirectory }
    private val expected = Json.parseToJsonElement(File(dir, "xlsx-expected.json").readText()).jsonObject

    private fun styleValue(s: SheetStyle?, key: String): Any? = when (key) {
        "fmt" -> s?.fmt
        "dp" -> s?.dp
        "b" -> s?.b
        "i" -> s?.i
        "u" -> s?.u
        "color" -> s?.color
        "fill" -> s?.fill
        "align" -> s?.align
        "wrap" -> s?.wrap
        "border" -> s?.border
        else -> error(key)
    }

    private fun jsonValue(p: JsonPrimitive): Any = when {
        p.isString -> p.content
        p.booleanOrNull != null -> p.boolean
        else -> p.int
    }

    @Test
    fun readsTheSamples() {
        for (file in expected["files"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            val sheets = SheetXlsx.read(File(dir, file).readBytes())
            val wanted = expected["sheets"]!!.jsonArray.map { it.jsonObject }
            assertEquals(file, wanted.map { it["name"]!!.jsonPrimitive.content }, sheets.map { it.name })
            wanted.forEachIndexed { i, want ->
                val got = sheets[i].sheet
                val calc = SheetCalc(got, 46326.0)
                val name = want["name"]!!.jsonPrimitive.content
                for ((a, cellEl) in want["cells"]!!.jsonObject) {
                    val cell = cellEl.jsonObject
                    val actual = got.cells[a]
                    assertEquals("$file $name!$a value", cell["v"]!!.jsonPrimitive.content, actual?.v)
                    val s = cell["s"] as? JsonObject
                    s?.forEach { (k, v) -> assertEquals("$file $name!$a $k", jsonValue(v.jsonPrimitive), styleValue(actual?.s, k)) }
                    assertEquals("$file $name!$a format", (s?.get("fmt") as? JsonPrimitive)?.content, actual?.s?.fmt)
                    cell["shows"]?.let { shows ->
                        val (r, c) = parseAddr(a)!!
                        assertEquals("$file $name!$a shows", shows.jsonPrimitive.content, calc.display(r, c))
                    }
                }
                (want["widths"] as? JsonObject)?.forEach { (k, w) -> assertEquals("$file width $k", w.jsonPrimitive.int.toFloat(), got.widths[k]) }
                assertEquals("$file hidden columns", want["hiddenCols"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), got.hiddenCols)
                assertEquals("$file hidden rows", want["hiddenRows"]!!.jsonArray.map { it.jsonPrimitive.int - 1 }.toSet(), got.hiddenRows)
            }
        }
    }

    @Test
    fun numberFormats() {
        val cases = listOf(
            Triple(0, null, null to null),
            Triple(4, null, "number" to 2),
            Triple(164, "\"$\"#,##0.00", "currency" to 2),
            Triple(164, "[\$\$-409]#,##0.00", "currency" to 2),
            Triple(164, "[\$-409]#,##0", "number" to 0),
            Triple(164, "0.0%", "percent" to 1),
            Triple(164, "yyyy-mm-dd", "date" to null),
            Triple(164, "mmm d", "date" to null),
            Triple(164, "[h]:mm:ss", "time" to null),
            Triple(164, "mm:ss", "time" to null),
            Triple(164, "m/d/yyyy h:mm", "datetime" to null),
            Triple(164, "@", "text" to null),
            Triple(164, "0.00E+00", null to null),
        )
        for ((id, code, want) in cases) assertEquals("$id $code", want, SheetXlsx.formatFromCode(id, code))
    }

    @Test
    fun excelMarks() {
        assertEquals("_xlfn.CONCAT(A1,\"CONCAT(\")&_xlfn.concat (B1)", SheetXlsx.excelFormula("CONCAT(A1,\"CONCAT(\")&concat (B1)"))
        assertEquals("CONCAT(A1,\"_xlfn.x\")", SheetXlsx.plainFormula("_xlfn.CONCAT(A1,\"_xlfn.x\")"))
    }

    private fun sample(): SheetData {
        var s = SheetData()
        listOf(
            "A1" to "Item", "B1" to "Cost", "A2" to "Rent", "B2" to "$1,450", "A3" to "Power & gas <est>", "B3" to "92.18",
            "A4" to "Total", "B4" to "=SUM(B2:B3)", "C4" to "=B4>1000", "D4" to "=IF(C4,\"over\",\"under\")", "C2" to "10/31/2026", "D2" to "45%",
            "E2" to "'00123", "E3" to "3:30 PM",
        ).forEach { (a, v) ->
            val (r, c) = parseAddr(a)!!
            s = SheetGrid.typeInto(s, r, c, v)
        }
        s = SheetGrid.updateStyles(s, SheetArea(0, 0, 0, 1)) { it.copy(b = true, fill = "#FFF3B0", align = "center") }
        s = SheetGrid.updateStyles(s, SheetArea(2, 0, 2, 0)) { it.copy(i = true, u = true, color = "#D6334C", wrap = true) }
        s = SheetGrid.updateStyles(s, SheetArea(1, 1, 2, 1)) { it.copy(fmt = "number", dp = 1) }
        s = SheetGrid.setBorders(s, SheetArea(3, 0, 3, 1), SheetGrid.Borders.OUTLINE)
        return s.copy(widths = mapOf("A" to 154f))
    }

    @Test
    fun writesAndReadsBack() {
        val s = sample()
        val back = SheetXlsx.read(SheetXlsx.write(s, "My: sheet?")).single()
        assertEquals("My  sheet", back.name)
        assertEquals(s.cells, back.sheet.cells)
        assertEquals(154f, back.sheet.widths["A"])
    }

    @Test
    fun keepsHiddenColumnsAndRows() {
        val s = SheetGrid.hideRows(SheetGrid.hideCols(sample(), 2, 2), 1, 1)
        val back = SheetXlsx.read(SheetXlsx.write(s)).single().sheet
        assertEquals(setOf("C"), back.hiddenCols)
        assertEquals(setOf(1), back.hiddenRows)
        // Saved and read back in the app's own format too.
        val again = SheetData.decode(back.encode())
        assertEquals(setOf("C"), again.hiddenCols)
        assertEquals(setOf(1), again.hiddenRows)
        // They move with inserted columns and rows.
        val moved = SheetGrid.insertCols(SheetGrid.insertRows(again, 0, 2), 0, 1)
        assertEquals(setOf("D"), moved.hiddenCols)
        assertEquals(setOf(3), moved.hiddenRows)
    }

    @Test
    fun bakesInTheTableLook() {
        val back = SheetXlsx.read(SheetXlsx.write(sample().copy(table = "green"))).single().sheet
        assertEquals(true, back.cells["A1"]?.s?.b)
        assertEquals("#FFF3B0", back.cells["A1"]?.s?.fill)
        assertEquals("#E8F5EC", back.cells["A3"]?.s?.fill)
        assertEquals("#FFFFFF", back.cells["A2"]?.s?.fill)
    }

    @Test
    fun csv() {
        var s = SheetData()
        s = SheetGrid.typeInto(s, 0, 0, "say \"hi\", ok")
        s = SheetGrid.typeInto(s, 0, 1, "$5")
        s = SheetGrid.typeInto(s, 1, 0, "=1+1")
        assertEquals("\"say \"\"hi\"\", ok\",$5.00\r\n2,\r\n", SheetCsv.write(s))
        val read = SheetCsv.read("﻿Name;Cost;Due\r\n\"Smith; J\";\"1.234,5\";10/31/2026\n\"two\nlines\";=1+2;\n")
        assertEquals("Smith; J", read.cells["A2"]?.v)
        assertEquals(SheetCell("46326", SheetStyle(fmt = "date")), read.cells["C2"])
        assertEquals("two\nlines", read.cells["A3"]?.v)
        assertEquals("3", SheetCalc(read).display(2, 1))
    }

    @Test
    fun longChainsOfFormulas() {
        val cells = LinkedHashMap<String, SheetCell>()
        cells["A1"] = SheetCell("1")
        for (r in 2..5000) cells["A$r"] = SheetCell("=A${r - 1}+1")
        cells["B5000"] = SheetCell("1")
        for (r in 4999 downTo 1) cells["B$r"] = SheetCell("=B${r + 1}+1")
        val calc = SheetCalc(SheetData(cells = cells))
        assertEquals("5000", calc.display(4999, 0))
        assertEquals("5000", calc.display(0, 1))
    }

    @Test
    fun hugeSelections() {
        var s = SheetGrid.typeInto(SheetData(), 0, 0, "5")
        s = SheetGrid.typeInto(s, 900_000, 3, "7")
        val all = SheetArea(0, 0, 1_000_000, 200)
        val start = System.currentTimeMillis()
        assertEquals(SheetGrid.Stats(12.0, 6.0, 2), SheetGrid.stats(s, SheetCalc(s), all))
        val bold = SheetGrid.updateStyles(s, all) { it.copy(b = true) }
        assertEquals(setOf("A1", "D900001"), bold.cells.keys)
        assertEquals(setOf("A1"), SheetGrid.clear(bold, SheetArea(800_000, 0, 1_000_000, 200), styles = true).cells.keys)
        assert(System.currentTimeMillis() - start < 3000)
        assertEquals("5", SheetGrid.plainText(s).substringBefore('\n'))
    }

    @Test
    fun strayFormattingIsLeftBehind() {
        var s = SheetGrid.typeInto(SheetData(), 0, 0, "x")
        s = SheetGrid.updateStyles(s, SheetArea(899, 25, 899, 25)) { it.copy(b = true) }
        s = SheetGrid.updateStyles(s, SheetArea(2, 1, 2, 1)) { it.copy(fill = "#FFF3B0") }
        val back = SheetXlsx.read(SheetXlsx.write(s)).single().sheet
        assertEquals(setOf("A1", "B3"), back.cells.keys)
    }
}
