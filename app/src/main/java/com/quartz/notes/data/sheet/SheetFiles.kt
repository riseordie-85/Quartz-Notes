package com.quartz.notes.data.sheet

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

// Excel (.xlsx) and CSV files for sheets. Kept in step with the web app's
// web/src/lib/sheet/xlsx.ts and csv.ts; both read shared/fixtures/sheets/*.xlsx the same way.

/** A worksheet read from a file: its name and its cells. */
data class ImportedSheet(val name: String, val sheet: SheetData)

object SheetXlsx {
    /** Rows and columns of styling kept past the last value when reading a file. */
    private const val MARGIN = 5

    // ---- Writing ----

    /** A cell's style with the sheet's table look baked in (header color and bold text, bands). */
    fun lookOf(sheet: SheetData, r: Int, c: Int, valued: Pair<Int, Int> = sheet.valuedSize): SheetStyle {
        val s = sheet.cell(r, c)?.s ?: SheetStyle.NONE
        val table = sheet.table?.let { TABLE_STYLES[it] } ?: return s
        if (r >= valued.first || c >= valued.second) return s
        fun hex(argb: Long) = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase(Locale.ROOT)
        return when {
            r == 0 && s.fill == null -> s.copy(b = true, fill = hex(table.header), color = s.color ?: "#FFFFFF")
            r == 0 -> s.copy(b = true)
            s.fill == null -> s.copy(fill = if (r % 2 == 0) hex(table.band) else "#FFFFFF")
            else -> s
        }
    }

    /** Excel's number format code for a style, or null for General. */
    fun formatCode(fmt: String?, dp: Int?): String? {
        fun places(n: Int) = if (n > 0) "." + "0".repeat(n) else ""
        return when (fmt) {
            "number" -> "#,##0" + places(dp ?: 2)
            "currency" -> "\"$\"#,##0" + places(dp ?: 2)
            "percent" -> "0" + places(dp ?: 0) + "%"
            "date" -> "m/d/yyyy"
            "time" -> "h:mm AM/PM"
            "datetime" -> "m/d/yyyy h:mm AM/PM"
            "text" -> "@"
            else -> null
        }
    }

    private val CONTROL = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace(CONTROL, "")
    private fun argb(hex: String) = "FF" + hex.removePrefix("#").uppercase(Locale.ROOT)

    private val QUOTED = Regex("(\"(?:[^\"]|\"\")*\")")

    /** Applies [f] to the parts of a formula outside quoted text. */
    private fun outsideQuotes(formula: String, f: (String) -> String): String {
        val out = StringBuilder()
        var last = 0
        for (m in QUOTED.findAll(formula)) {
            out.append(f(formula.substring(last, m.range.first))).append(m.value)
            last = m.range.last + 1
        }
        return out.append(f(formula.substring(last))).toString()
    }

    private val NEWER = Regex("(^|[^A-Za-z0-9_.])(CONCAT)(\\s*\\()", RegexOption.IGNORE_CASE)
    private val MARKS = Regex("_xl(fn|ws)\\.", RegexOption.IGNORE_CASE)

    /** A formula as Excel files keep it: newer functions carry Excel's "_xlfn." mark. */
    fun excelFormula(formula: String): String = outsideQuotes(formula) { it.replace(NEWER, "$1_xlfn.$2$3") }

    /** A formula from an Excel file, without Excel's marks on newer functions. */
    fun plainFormula(formula: String): String = outsideQuotes(formula) { it.replace(MARKS, "") }

    /** A worksheet name Excel accepts. */
    fun sheetName(title: String): String {
        val clean = title.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim('\'').trim().take(31).trim()
        return clean.ifEmpty { "Sheet1" }
    }

    /** The sheet as an .xlsx file (values, formulas with their results, formats, styles, widths). */
    fun write(sheet: SheetData, title: String = ""): ByteArray {
        val calc = SheetCalc(sheet)
        val (rows, cols) = sheet.usedSize
        val valued = sheet.valuedSize
        val hidden = SheetGrid.hiddenRows(sheet, calc)

        val numFmts = ArrayList<String>()
        val fonts = arrayListOf("<font><sz val=\"11\"/><name val=\"Calibri\"/></font>")
        val fills = arrayListOf("<fill><patternFill patternType=\"none\"/></fill>", "<fill><patternFill patternType=\"gray125\"/></fill>")
        val borders = arrayListOf("<border><left/><right/><top/><bottom/><diagonal/></border>")
        val xfs = arrayListOf("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
        fun index(list: MutableList<String>, item: String): Int {
            val i = list.indexOf(item)
            if (i >= 0) return i
            list += item
            return list.size - 1
        }
        fun xfOf(s: SheetStyle): Int {
            val code = formatCode(s.fmt, s.dp)
            val numFmtId = when (code) {
                null -> 0
                "@" -> 49
                else -> 164 + index(numFmts, code)
            }
            val font = "<font>" + (if (s.b) "<b/>" else "") + (if (s.i) "<i/>" else "") + (if (s.u) "<u/>" else "") +
                "<sz val=\"11\"/>" + (s.color?.let { "<color rgb=\"${argb(it)}\"/>" } ?: "") + "<name val=\"Calibri\"/></font>"
            val fontId = index(fonts, font)
            val fillId = s.fill?.let {
                index(fills, "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"${argb(it)}\"/><bgColor indexed=\"64\"/></patternFill></fill>")
            } ?: 0
            fun side(name: String, on: Boolean) = if (on) "<$name style=\"thin\"><color auto=\"1\"/></$name>" else "<$name/>"
            val b = s.border.orEmpty()
            val borderId = if (b.isEmpty()) 0 else index(
                borders,
                "<border>" + side("left", 'l' in b) + side("right", 'r' in b) + side("top", 't' in b) + side("bottom", 'b' in b) + "<diagonal/></border>",
            )
            val align = if (s.align != null || s.wrap) {
                "<alignment" + (s.align?.let { " horizontal=\"$it\"" } ?: "") + (if (s.wrap) " wrapText=\"1\"" else "") + "/>"
            } else {
                ""
            }
            val xf = "<xf numFmtId=\"$numFmtId\" fontId=\"$fontId\" fillId=\"$fillId\" borderId=\"$borderId\" xfId=\"0\"" +
                (if (numFmtId != 0) " applyNumberFormat=\"1\"" else "") + (if (fontId != 0) " applyFont=\"1\"" else "") +
                (if (fillId != 0) " applyFill=\"1\"" else "") + (if (borderId != 0) " applyBorder=\"1\"" else "") +
                (if (align.isNotEmpty()) " applyAlignment=\"1\">$align</xf>" else "/>")
            return index(xfs, xf)
        }

        val rowXml = StringBuilder()
        for (r in 0 until rows) {
            val cellsXml = StringBuilder()
            for (c in 0 until cols) {
                val cell = sheet.cell(r, c)
                val style = lookOf(sheet, r, c, valued)
                val v = cell?.v
                if (v.isNullOrEmpty() && style.isEmpty) continue
                val ref = addr(r, c)
                val xf = xfOf(style)
                val sAttr = if (xf != 0) " s=\"$xf\"" else ""
                when {
                    v.isNullOrEmpty() -> cellsXml.append("<c r=\"$ref\"$sAttr/>")
                    v.startsWith("=") && style.fmt != "text" -> {
                        val f = "<f>${esc(excelFormula(v.substring(1)))}</f>"
                        when (val value = calc.value(r, c)) {
                            is SheetValue.Num -> cellsXml.append("<c r=\"$ref\"$sAttr>$f<v>${SheetNumbers.canonical(value.n)}</v></c>")
                            is SheetValue.Bool -> cellsXml.append("<c r=\"$ref\"$sAttr t=\"b\">$f<v>${if (value.b) 1 else 0}</v></c>")
                            is SheetValue.Err -> {
                                val e = if (value.e == "#CIRC!" || value.e == "#ERROR!") "#VALUE!" else value.e
                                cellsXml.append("<c r=\"$ref\"$sAttr t=\"e\">$f<v>${esc(e)}</v></c>")
                            }
                            else -> cellsXml.append("<c r=\"$ref\"$sAttr t=\"str\">$f<v>${esc(SheetCalc.textOf(value))}</v></c>")
                        }
                    }
                    style.fmt != "text" && SheetNumbers.isStoredNumber(v) -> cellsXml.append("<c r=\"$ref\"$sAttr><v>$v</v></c>")
                    else -> cellsXml.append("<c r=\"$ref\"$sAttr t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                }
            }
            if (cellsXml.isNotEmpty() || r in hidden) {
                rowXml.append("<row r=\"${r + 1}\"" + (if (r in hidden) " hidden=\"1\"" else "") + ">").append(cellsXml).append("</row>")
            }
        }
        val colXml = (sheet.widths.keys + sheet.hiddenCols)
            .map { colIndex(it) + 1 }
            .sorted()
            .joinToString("") { i ->
                val letters = colName(i - 1)
                val w = sheet.widths[letters] ?: DEFAULT_WIDTH
                "<col min=\"$i\" max=\"$i\" width=\"${String.format(Locale.ROOT, "%.2f", w / 7f)}\"" +
                    (if (letters in sheet.widths) " customWidth=\"1\"" else "") + (if (letters in sheet.hiddenCols) " hidden=\"1\"" else "") + "/>"
            }

        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val worksheet = head + "<worksheet xmlns=\"$main\" xmlns:r=\"$rel\">" +
            "<dimension ref=\"A1" + (if (rows > 0 && cols > 0) ":" + addr(rows - 1, cols - 1) else "") + "\"/>" +
            "<sheetFormatPr defaultRowHeight=\"15\"/>" +
            (if (colXml.isNotEmpty()) "<cols>$colXml</cols>" else "") +
            "<sheetData>$rowXml</sheetData></worksheet>"
        val styles = head + "<styleSheet xmlns=\"$main\">" +
            (if (numFmts.isNotEmpty()) "<numFmts count=\"${numFmts.size}\">" + numFmts.mapIndexed { i, code -> "<numFmt numFmtId=\"${164 + i}\" formatCode=\"${esc(code)}\"/>" }.joinToString("") + "</numFmts>" else "") +
            "<fonts count=\"${fonts.size}\">${fonts.joinToString("")}</fonts>" +
            "<fills count=\"${fills.size}\">${fills.joinToString("")}</fills>" +
            "<borders count=\"${borders.size}\">${borders.joinToString("")}</borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"${xfs.size}\">${xfs.joinToString("")}</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
        val workbook = head + "<workbook xmlns=\"$main\" xmlns:r=\"$rel\">" +
            "<sheets><sheet name=\"${esc(sheetName(title))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>"
        val types = head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>"
        val rootRels = head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$rel/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
        val bookRels = head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$rel/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$rel/styles\" Target=\"styles.xml\"/></Relationships>"

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf(
                "[Content_Types].xml" to types,
                "_rels/.rels" to rootRels,
                "xl/workbook.xml" to workbook,
                "xl/_rels/workbook.xml.rels" to bookRels,
                "xl/worksheets/sheet1.xml" to worksheet,
                "xl/styles.xml" to styles,
            ).forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ---- Reading ----

    private val BUILT_IN: Map<Int, Pair<String?, Int?>> = mapOf(
        1 to ("number" to 0), 2 to ("number" to 2), 3 to ("number" to 0), 4 to ("number" to 2),
        5 to ("currency" to 0), 6 to ("currency" to 0), 7 to ("currency" to 2), 8 to ("currency" to 2),
        9 to ("percent" to 0), 10 to ("percent" to 2), 14 to ("date" to null), 15 to ("date" to null), 16 to ("date" to null),
        17 to ("date" to null), 18 to ("time" to null), 19 to ("time" to null), 20 to ("time" to null), 21 to ("time" to null),
        22 to ("datetime" to null), 37 to ("number" to 0), 38 to ("number" to 0), 39 to ("number" to 2), 40 to ("number" to 2),
        44 to ("currency" to 2), 45 to ("time" to null), 46 to ("time" to null), 47 to ("time" to null), 49 to ("text" to null),
    )

    /** What one of Excel's number formats shows as here: the format and its places (nulls for General). */
    fun formatFromCode(id: Int, code: String?): Pair<String?, Int?> {
        if (code == null) return BUILT_IN[id] ?: (null to null)
        val section = code.split(';')[0]
        val bare = section.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\\\."), "")
            .replace(Regex("\\[(?![hms]+\\])[^\\]]*\\]", RegexOption.IGNORE_CASE), "").replace(Regex("_.|\\*."), "")
        val places = Regex("\\.(0+)").find(bare)?.groupValues?.get(1)?.length ?: 0
        val lower = bare.lowercase(Locale.ROOT)
        if (bare.trim() == "@") return "text" to null
        val date = Regex("[yd]").containsMatchIn(lower) || (lower.contains('m') && !Regex("[hs]").containsMatchIn(lower) && !lower.contains('0'))
        val time = Regex("[hs]").containsMatchIn(lower)
        if (date && time) return "datetime" to null
        if (date) return "date" to null
        if (time) return "time" to null
        if (bare.contains('%')) return "percent" to places
        val hasDigits = bare.contains('0') || bare.contains('#')
        if (Regex("[\\$€£¥]").containsMatchIn(section.replace(Regex("\\[\\$-[^\\]]*\\]"), "")) && hasDigits) return "currency" to places
        if (hasDigits && !Regex("e[+-]", RegexOption.IGNORE_CASE).containsMatchIn(bare)) return "number" to places
        return null to null
    }

    private fun Element.kids(name: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == name) out += n
        }
        return out
    }

    private fun Element.kid(name: String): Element? = kids(name).firstOrNull()

    private fun Element.all(name: String): List<Element> {
        val list = getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

    private fun colorOf(e: Element?): String? {
        val rgb = e?.attr("rgb") ?: return null
        return when {
            Regex("^[0-9A-Fa-f]{8}$").matches(rgb) -> "#" + rgb.substring(2).uppercase(Locale.ROOT)
            Regex("^[0-9A-Fa-f]{6}$").matches(rgb) -> "#" + rgb.uppercase(Locale.ROOT)
            else -> null
        }
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.substring(1)
        val parts = base.split('/').dropLast(1).toMutableList()
        for (p in target.split('/')) {
            when (p) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts += p
            }
        }
        return parts.joinToString("/")
    }

    private fun parse(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { isExpandEntityReferences = false }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    /** The worksheets of an .xlsx file, as sheets (values, formulas, formats, styles, widths). */
    fun read(bytes: ByteArray): List<ImportedSheet> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) files[entry.name.removePrefix("/")] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        if (files.isEmpty()) throw IllegalArgumentException("This isn't an Excel file")
        fun xml(path: String): Element? = files[path]?.let(::parse)

        val bookPath = xml("_rels/.rels")?.all("Relationship")
            ?.firstOrNull { it.getAttribute("Type").endsWith("/officeDocument") }
            ?.getAttribute("Target")?.removePrefix("/") ?: "xl/workbook.xml"
        val book = xml(bookPath) ?: throw IllegalArgumentException("This isn't an Excel workbook")
        val rels = HashMap<String, Pair<String, String>>()
        xml(resolve(bookPath, "_rels/" + bookPath.substringAfterLast('/') + ".rels"))?.all("Relationship")?.forEach { r ->
            rels[r.getAttribute("Id")] = r.getAttribute("Type") to resolve(bookPath, r.getAttribute("Target"))
        }
        fun pathOf(type: String) = rels.values.firstOrNull { it.first.endsWith("/$type") }?.second

        val strings = ArrayList<String>()
        pathOf("sharedStrings")?.let(::xml)?.kids("si")?.forEach { si -> strings += si.all("t").joinToString("") { it.textContent } }

        val styles = ArrayList<SheetStyle>()
        pathOf("styles")?.let(::xml)?.let { root ->
            val codes = HashMap<Int, String>()
            root.kid("numFmts")?.kids("numFmt")?.forEach { codes[it.getAttribute("numFmtId").toIntOrNull() ?: -1] = it.getAttribute("formatCode") }
            data class Font(val b: Boolean, val i: Boolean, val u: Boolean, val color: String?)
            val fonts = root.kid("fonts")?.kids("font").orEmpty().map { f ->
                fun on(n: String): Boolean {
                    val e = f.kid(n) ?: return false
                    return e.attr("val") !in setOf("0", "false", "none")
                }
                Font(on("b"), on("i"), on("u"), colorOf(f.kid("color")))
            }
            val fills = root.kid("fills")?.kids("fill").orEmpty().map { f ->
                val p = f.kid("patternFill")
                if (p?.attr("patternType") == "solid") colorOf(p.kid("fgColor")) ?: colorOf(p.kid("bgColor")) else null
            }
            val borders = root.kid("borders")?.kids("border").orEmpty().map { b ->
                listOf("top" to 't', "right" to 'r', "bottom" to 'b', "left" to 'l')
                    .filter { (n, _) -> !b.kid(n)?.attr("style").isNullOrEmpty() }.map { it.second }.joinToString("")
            }
            root.kid("cellXfs")?.kids("xf")?.forEach { xf ->
                val id = xf.attr("numFmtId")?.toIntOrNull() ?: 0
                var (fmt, dp) = formatFromCode(id, codes[id])
                val font = fonts.getOrNull(xf.attr("fontId")?.toIntOrNull() ?: 0)
                val fill = fills.getOrNull(xf.attr("fillId")?.toIntOrNull() ?: 0)
                val border = borders.getOrNull(xf.attr("borderId")?.toIntOrNull() ?: 0)?.ifEmpty { null }
                val al = xf.kid("alignment")
                val h = al?.attr("horizontal")?.takeIf { it in setOf("left", "center", "right") }
                val wrap = al?.attr("wrapText") == "1" || al?.attr("wrapText") == "true"
                if (dp != null && dp == (if (fmt == "number" || fmt == "currency") 2 else 0)) dp = null
                styles += SheetStyle(
                    fmt = fmt,
                    dp = dp,
                    b = font?.b == true,
                    i = font?.i == true,
                    u = font?.u == true,
                    color = font?.color?.takeIf { it != "#000000" },
                    fill = fill,
                    align = h,
                    wrap = wrap,
                    border = border,
                )
            }
        }

        val out = ArrayList<ImportedSheet>()
        for (sh in book.all("sheet")) {
            val rid = sh.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id").ifEmpty { sh.getAttribute("r:id") }
            val doc = rels[rid]?.second?.let(::xml) ?: continue
            val cells = LinkedHashMap<String, SheetCell>()
            data class Shared(val formula: String, val r: Int, val c: Int)
            val shared = HashMap<String, Shared>()
            val hiddenRows = HashSet<Int>()
            for (row in doc.all("row")) {
                val hiddenAttr = row.attr("hidden")
                if (hiddenAttr == "1" || hiddenAttr == "true") row.attr("r")?.toIntOrNull()?.let { if (it >= 1) hiddenRows += it - 1 }
                for (c in row.kids("c")) {
                    val (r, col) = parseAddr(c.getAttribute("r")) ?: continue
                    val style = styles.getOrNull(c.attr("s")?.toIntOrNull() ?: 0) ?: SheetStyle.NONE
                    val t = c.attr("t") ?: "n"
                    val f = c.kid("f")
                    val v = c.kid("v")?.textContent
                    var value: String? = null
                    if (f != null) {
                        var formula = f.textContent
                        val si = f.attr("si")
                        if (f.attr("t") == "shared" && si != null) {
                            if (formula.isNotEmpty()) {
                                shared[si] = Shared(formula, r, col)
                            } else {
                                shared[si]?.let { base ->
                                    formula = SheetGrid.shiftFormula("=" + base.formula, r - base.r, col - base.c).substring(1)
                                }
                            }
                        }
                        if (formula.isNotEmpty()) value = "=" + plainFormula(formula)
                    }
                    if (value == null) {
                        value = when (t) {
                            "s" -> v?.toIntOrNull()?.let { strings.getOrNull(it) }
                            "inlineStr" -> c.all("t").joinToString("") { it.textContent }
                            "b" -> if (v == "1") "TRUE" else "FALSE"
                            "str", "e" -> v
                            else -> if (v.isNullOrEmpty()) null else if (style.fmt == "text") v else v.toDoubleOrNull()?.let { SheetNumbers.canonical(it) }
                        }
                    }
                    val cell = SheetCell(value?.takeIf { it.isNotEmpty() }, style)
                    if (!cell.isEmpty) cells[addr(r, col)] = cell
                }
            }
            val widths = LinkedHashMap<String, Float>()
            val hiddenCols = HashSet<String>()
            for (col in doc.all("col")) {
                val min = col.attr("min")?.toIntOrNull() ?: continue
                if (min < 1) continue
                val hiddenAttr = col.attr("hidden")
                if (hiddenAttr == "1" || hiddenAttr == "true") {
                    for (i in min..minOf(col.attr("max")?.toIntOrNull() ?: min, min + 200)) hiddenCols += colName(i - 1)
                }
                val max = minOf(col.attr("max")?.toIntOrNull() ?: min, min + 60)
                val width = col.attr("width")?.toDoubleOrNull() ?: continue
                if (width <= 0 || col.attr("customWidth") == "0" || col.attr("customWidth") == "false") continue
                for (i in min..max) widths[colName(i - 1)] = Math.round(width * 7).toFloat()
            }
            // Formatting that runs on far past the data (whole columns, say) is left behind.
            val valued = SheetData(cells = cells).valuedSize
            val keepRows = valued.first + MARGIN
            val keepCols = valued.second + MARGIN
            cells.entries.removeIf { (key, cell) ->
                val (r, c) = parseAddr(key) ?: return@removeIf true
                cell.v == null && (r >= keepRows || c >= keepCols)
            }
            hiddenRows.removeIf { it >= keepRows }
            hiddenCols.removeIf { colIndex(it) >= keepCols }
            val data = SheetData(cells = cells, widths = widths, hiddenCols = hiddenCols, hiddenRows = hiddenRows)
            val (rows, cols) = data.usedSize
            out += ImportedSheet(sh.getAttribute("name").ifEmpty { "Sheet" }, data.copy(rows = maxOf(DEFAULT_ROWS, rows + 1), cols = maxOf(DEFAULT_COLS, cols + 1)))
        }
        return out
    }
}

object SheetCsv {
    /** The sheet's shown values as CSV (commas, quotes where needed, CRLF line ends). */
    fun write(sheet: SheetData, calc: SheetCalc = SheetCalc(sheet)): String {
        val (rows, cols) = sheet.usedSize
        val sb = StringBuilder()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (c > 0) sb.append(',')
                val v = calc.display(r, c)
                val quote = v.any { it == '"' || it == ',' || it == '\r' || it == '\n' } || v.firstOrNull()?.isWhitespace() == true || v.lastOrNull()?.isWhitespace() == true
                if (quote) sb.append('"').append(v.replace("\"", "\"\"")).append('"') else sb.append(v)
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    /** The rows of a CSV file; the separator (comma, semicolon or tab) is worked out from the first line. */
    fun parse(text: String): List<List<String>> {
        val body = text.removePrefix("﻿")
        val first = body.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(',', ';', '\t').maxByOrNull { ch -> first.count { it == ch } }?.takeIf { ch -> first.count { it == ch } > 0 } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < body.length && body[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(ch)
                }
            } else if (ch == '"' && cell.isEmpty()) {
                quoted = true
            } else if (ch == sep) {
                row += cell.toString()
                cell.clear()
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < body.length && body[i + 1] == '\n') i++
                row += cell.toString()
                cell.clear()
                rows += row
                row = ArrayList()
            } else {
                cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }

    /** A CSV file as a sheet: each value typed into its cell (so numbers, dates and formulas work). */
    fun read(text: String): SheetData {
        var sheet = SheetData()
        parse(text).forEachIndexed { r, cells ->
            cells.forEachIndexed { c, v -> if (v.isNotBlank()) sheet = SheetGrid.typeInto(sheet, r, c, v) }
        }
        val (rows, cols) = sheet.usedSize
        return sheet.copy(rows = maxOf(DEFAULT_ROWS, rows + 1), cols = maxOf(DEFAULT_COLS, cols + 1))
    }
}
