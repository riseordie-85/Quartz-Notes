package com.quartz.notes.data.export

import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.sheet.SheetCalc
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.SheetValue
import com.quartz.notes.data.sheet.SheetXlsx
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A picture to put in a document: its encoded bytes ("png" or "jpeg") and size in pixels. */
class DocPicture(val bytes: ByteArray, val type: String, val width: Int, val height: Int)

/** What goes into a Word document, in order. */
sealed interface DocPart {
    /** A note's or page's title. */
    data class Title(val text: String) : DocPart

    /** Formatted text; in-line pictures come from [pictures] by their path. */
    data class Rich(val doc: RichDoc) : DocPart

    /** Plain paragraphs (a section page's description, say). */
    data class Text(val text: String) : DocPart

    /** A whole picture (a Freeform page, an attached photo), as wide as the page allows. */
    data class Picture(val picture: DocPicture, val widthFraction: Float = 1f) : DocPart

    /** A sheet, as a table of what its cells show. */
    data class Table(val sheet: SheetData) : DocPart

    data object PageBreak : DocPart
}

/**
 * Writes Word (.docx) documents: headings, lists, checklists, colors, highlights, fonts, alignment,
 * pictures and tables, on A4 pages.
 */
object DocxWriter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** The text area of an A4 page with 1" margins, in points. */
    private const val CONTENT_W = 451.3f
    private const val CONTENT_H = 697.9f
    private const val EMU_PER_PT = 12700L

    private val CONTROL = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace(CONTROL, "")

    /** "FFD6004C" or "#D6004C" → "D6004C". */
    private fun rgb(value: String?): String? {
        val v = value?.removePrefix("#") ?: return null
        return when (v.length) {
            8 -> v.substring(2)
            6 -> v
            else -> null
        }?.uppercase(Locale.ROOT)?.takeIf { it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
    }

    private class Builder(val pictures: (String) -> DocPicture?) {
        val body = StringBuilder()
        val media = ArrayList<Pair<String, ByteArray>>()
        var nextId = 1

        fun picture(p: DocPicture, widthPt: Float, align: String? = null) {
            var w = widthPt
            var h = w * p.height / p.width.coerceAtLeast(1)
            if (h > CONTENT_H) {
                w *= CONTENT_H / h
                h = CONTENT_H
            }
            val id = nextId++
            val ext = if (p.type == "jpeg") "jpeg" else "png"
            val name = "image$id.$ext"
            media += name to p.bytes
            val cx = (w * EMU_PER_PT).toLong()
            val cy = (h * EMU_PER_PT).toLong()
            body.append("<w:p>")
            if (align != null) body.append("<w:pPr><w:jc w:val=\"$align\"/></w:pPr>")
            body.append(
                "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
                    "<wp:docPr id=\"$id\" name=\"Picture $id\"/><a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                    "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                    "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                    "<pic:blipFill><a:blip r:embed=\"rImg$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                    "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                    "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>",
            )
        }

        /** Run properties, in the order Word expects them. */
        fun props(font: String? = null, b: Boolean = false, i: Boolean = false, strike: Boolean = false, color: String? = null, size: Int? = null, u: Boolean = false, fill: String? = null): String {
            val p = StringBuilder()
            if (font != null) p.append("<w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:cs=\"$font\"/>")
            if (b) p.append("<w:b/>")
            if (i) p.append("<w:i/>")
            if (strike) p.append("<w:strike/>")
            if (color != null) p.append("<w:color w:val=\"$color\"/>")
            if (size != null) p.append("<w:sz w:val=\"$size\"/>")
            if (u) p.append("<w:u w:val=\"single\"/>")
            if (fill != null) p.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$fill\"/>")
            return p.toString()
        }

        fun runs(text: String, spans: List<com.quartz.notes.data.rich.StyleSpan>, done: Boolean = false) {
            val cuts = sortedSetOf(0, text.length)
            spans.forEach {
                cuts += it.start.coerceIn(0, text.length)
                cuts += it.end.coerceIn(0, text.length)
            }
            val points = cuts.toList()
            for (k in 0 until points.size - 1) {
                val a = points[k]
                val b = points[k + 1]
                if (a >= b) continue
                val on = spans.filter { it.start <= a && it.end >= b }
                val font = when (on.firstOrNull { it.style == InlineStyle.FONT }?.value) {
                    "serif" -> "Georgia"
                    "mono" -> "Courier New"
                    "cursive" -> "Segoe Script"
                    else -> null
                }
                val highlight = on.firstOrNull { it.style == InlineStyle.HIGHLIGHT }?.let { rgb(it.value) ?: "FFE14D" }
                textRun(
                    text.substring(a, b),
                    props(
                        font = font,
                        b = on.any { it.style == InlineStyle.BOLD },
                        i = on.any { it.style == InlineStyle.ITALIC },
                        strike = done || on.any { it.style == InlineStyle.STRIKE },
                        color = rgb(on.firstOrNull { it.style == InlineStyle.COLOR }?.value) ?: if (done) "808088" else null,
                        u = on.any { it.style == InlineStyle.UNDERLINE },
                        fill = highlight,
                    ),
                )
            }
        }

        fun textRun(text: String, props: String = "") {
            body.append("<w:r>")
            if (props.isNotEmpty()) body.append("<w:rPr>").append(props).append("</w:rPr>")
            text.split('\n').forEachIndexed { li, line ->
                if (li > 0) body.append("<w:br/>")
                line.split('\t').forEachIndexed { ti, part ->
                    if (ti > 0) body.append("<w:tab/>")
                    if (part.isNotEmpty()) body.append("<w:t xml:space=\"preserve\">").append(esc(part)).append("</w:t>")
                }
            }
            body.append("</w:r>")
        }

        fun rich(doc: RichDoc) {
            for (b in doc.blocks) {
                val jc = when (b.align) {
                    Align.CENTER -> "center"
                    Align.END -> "right"
                    Align.JUSTIFY -> "both"
                    Align.START -> null
                }
                if (b.type == BlockType.IMAGE) {
                    val img = b.image ?: continue
                    val p = pictures(img.path) ?: continue
                    picture(p, CONTENT_W * b.imageWidth.coerceIn(0.1f, 1f), jc ?: "center")
                    if (img.caption.isNotBlank()) {
                        body.append("<w:p><w:pPr><w:pStyle w:val=\"Caption\"/><w:jc w:val=\"${jc ?: "center"}\"/></w:pPr>")
                        textRun(img.caption)
                        body.append("</w:p>")
                    }
                    continue
                }
                body.append("<w:p><w:pPr>")
                when (b.type) {
                    BlockType.HEADING -> body.append("<w:pStyle w:val=\"Heading1\"/>")
                    BlockType.SUBHEADING -> body.append("<w:pStyle w:val=\"Heading2\"/>")
                    BlockType.BULLET -> body.append("<w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"${b.indent.coerceIn(0, 8)}\"/><w:numId w:val=\"1\"/></w:numPr>")
                    BlockType.DIVIDER -> body.append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"1\" w:color=\"A0A0A8\"/></w:pBdr>")
                    else -> if (b.indent > 0) body.append("<w:ind w:left=\"${360 * b.indent}\"/>")
                }
                if (jc != null) body.append("<w:jc w:val=\"$jc\"/>")
                body.append("</w:pPr>")
                when (b.type) {
                    BlockType.TASK -> {
                        textRun(if (b.checked) "☑ " else "☐ ")
                        runs(b.text, b.spans, done = b.checked)
                    }
                    BlockType.DIVIDER -> {}
                    else -> runs(b.text, b.spans)
                }
                body.append("</w:p>")
            }
        }

        fun table(sheet: SheetData) {
            val (rows, cols) = sheet.usedSize
            if (rows == 0 || cols == 0) return
            val calc = SheetCalc(sheet)
            val valued = sheet.valuedSize
            // Columns as wide as on the page (100 units ≈ 75pt), shrunk to fit the page if need be.
            val widthsPt = (0 until cols).map { sheet.width(it) * 0.75f }
            val scale = minOf(1f, CONTENT_W / widthsPt.sum())
            val twips = widthsPt.map { (it * scale * 20).toInt() }
            body.append("<w:tbl><w:tblPr><w:tblW w:w=\"${twips.sum()}\" w:type=\"dxa\"/>")
            body.append("<w:tblBorders>")
            listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { body.append("<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D0D0D6\"/>") }
            body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
            twips.forEach { body.append("<w:gridCol w:w=\"$it\"/>") }
            body.append("</w:tblGrid>")
            val hidden = com.quartz.notes.data.sheet.SheetGrid.hiddenRows(sheet, calc)
            for (r in 0 until rows) {
                if (r in hidden) continue
                body.append("<w:tr>")
                for (c in 0 until cols) {
                    val s = SheetXlsx.lookOf(sheet, r, c, valued)
                    val value = calc.value(r, c)
                    body.append("<w:tc><w:tcPr><w:tcW w:w=\"${twips[c]}\" w:type=\"dxa\"/>")
                    val borders = s.border.orEmpty()
                    if (borders.isNotEmpty()) {
                        body.append("<w:tcBorders>")
                        listOf('t' to "top", 'l' to "left", 'b' to "bottom", 'r' to "right").forEach { (k, n) ->
                            if (k in borders) body.append("<w:$n w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"111114\"/>")
                        }
                        body.append("</w:tcBorders>")
                    }
                    rgb(s.fill)?.let { body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$it\"/>") }
                    body.append("</w:tcPr><w:p><w:pPr><w:spacing w:before=\"20\" w:after=\"20\"/>")
                    val align = s.align ?: when (value) {
                        is SheetValue.Num -> "right"
                        is SheetValue.Bool, is SheetValue.Err -> "center"
                        else -> null
                    }
                    when (align) {
                        "right" -> body.append("<w:jc w:val=\"right\"/>")
                        "center" -> body.append("<w:jc w:val=\"center\"/>")
                    }
                    body.append("</w:pPr>")
                    val text = calc.display(r, c)
                    if (text.isNotEmpty()) textRun(text, props(b = s.b, i = s.i, color = rgb(s.color), size = 20, u = s.u))
                    body.append("</w:p></w:tc>")
                }
                body.append("</w:tr>")
            }
            body.append("</w:tbl><w:p/>")
        }
    }

    /** The parts as a .docx file; [pictures] finds in-line pictures by their path. */
    fun write(parts: List<DocPart>, pictures: (String) -> DocPicture? = { null }): ByteArray {
        val b = Builder(pictures)
        for (part in parts) {
            when (part) {
                is DocPart.Title -> {
                    b.body.append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>")
                    b.textRun(part.text)
                    b.body.append("</w:p>")
                }
                is DocPart.Rich -> b.rich(part.doc)
                is DocPart.Text -> part.text.split('\n').forEach { line ->
                    b.body.append("<w:p>")
                    if (line.isNotEmpty()) b.textRun(line)
                    b.body.append("</w:p>")
                }
                is DocPart.Picture -> b.picture(part.picture, CONTENT_W * part.widthFraction.coerceIn(0.05f, 1f), "center")
                is DocPart.Table -> b.table(part.sheet)
                DocPart.PageBreak -> b.body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        val document = head +
            "<w:document xmlns:w=\"$W\" xmlns:r=\"$R\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
            "<w:body>${b.body}<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        val styles = head + "<w:styles xmlns:w=\"$W\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:spacing w:after=\"240\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"40\"/><w:szCs w:val=\"40\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"80\"/><w:outlineLvl w:val=\"2\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"40\"/><w:contextualSpacing/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Caption\"><w:name w:val=\"caption\"/><w:basedOn w:val=\"Normal\"/><w:rPr><w:i/><w:color w:val=\"6B6B72\"/><w:sz w:val=\"18\"/></w:rPr></w:style>" +
            "</w:styles>"
        val bullets = listOf("•", "◦", "▪")
        val numbering = head + "<w:numbering xmlns:w=\"$W\"><w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>" +
            (0..8).joinToString("") { lvl ->
                "<w:lvl w:ilvl=\"$lvl\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"${bullets[lvl % 3]}\"/><w:lvlJc w:val=\"left\"/>" +
                    "<w:pPr><w:ind w:left=\"${720 + 360 * lvl}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
            } +
            "</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"
        val types = head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/></Types>"
        val rootRels = head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        val docRels = head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rStyles\" Type=\"$R/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rNumbering\" Type=\"$R/numbering\" Target=\"numbering.xml\"/>" +
            b.media.joinToString("") { (name, _) ->
                val id = name.removePrefix("image").substringBefore('.')
                "<Relationship Id=\"rImg$id\" Type=\"$R/image\" Target=\"media/$name\"/>"
            } +
            "</Relationships>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("[Content_Types].xml", types.toByteArray())
            put("_rels/.rels", rootRels.toByteArray())
            put("word/document.xml", document.toByteArray())
            put("word/styles.xml", styles.toByteArray())
            put("word/numbering.xml", numbering.toByteArray())
            put("word/_rels/document.xml.rels", docRels.toByteArray())
            b.media.forEach { (name, bytes) -> put("word/media/$name", bytes) }
        }
        return out.toByteArray()
    }
}
