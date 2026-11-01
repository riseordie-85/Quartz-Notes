package com.quartz.notes.data.export

import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.Block
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.rich.StyleSpan
import com.quartz.notes.data.sheet.SheetArea
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.SheetGrid
import com.quartz.notes.data.sheet.parseAddr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Word documents: well-formed parts, pictures and tables in place. Also leaves a sample for checking by eye. */
class DocxWriterTest {
    /** A small valid PNG: [w]×[h] pixels of one color. */
    private fun png(w: Int, h: Int, rgb: Int): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val d = java.io.DataOutputStream(out)
            d.writeInt(data.size)
            val td = type.toByteArray() + data
            d.write(td)
            val crc = java.util.zip.CRC32().apply { update(td) }
            d.writeInt(crc.value.toInt())
            return out.toByteArray()
        }
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array()
        val raw = java.io.ByteArrayOutputStream()
        repeat(h) {
            raw.write(0)
            repeat(w) { raw.write(rgb shr 16 and 0xFF); raw.write(rgb shr 8 and 0xFF); raw.write(rgb and 0xFF) }
        }
        val deflater = java.util.zip.Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val buf = ByteArray(65536)
        val packed = buf.copyOf(deflater.deflate(buf))
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + chunk("IDAT", packed) + chunk("IEND", ByteArray(0))
    }

    private val png = png(80, 40, 0x2E9E5B)

    private fun sampleParts(): List<DocPart> {
        val doc = RichDoc(
            listOf(
                Block(type = BlockType.HEADING, text = "Day one in Kyoto"),
                Block(
                    text = "Arrived early & walked to the <shrine> before the crowds.",
                    spans = listOf(
                        StyleSpan(0, 7, InlineStyle.BOLD),
                        StyleSpan(14, 20, InlineStyle.ITALIC),
                        StyleSpan(30, 38, InlineStyle.HIGHLIGHT, "FFFFE14D"),
                        StyleSpan(46, 52, InlineStyle.COLOR, "FFD6004C"),
                        StyleSpan(46, 52, InlineStyle.UNDERLINE),
                        StyleSpan(0, 57, InlineStyle.FONT, "serif"),
                    ),
                ),
                Block(type = BlockType.SUBHEADING, text = "To do"),
                Block(type = BlockType.TASK, text = "Buy a rail pass", checked = true),
                Block(type = BlockType.TASK, text = "Book the tea ceremony"),
                Block(type = BlockType.BULLET, text = "Fushimi Inari"),
                Block(type = BlockType.BULLET, text = "Early morning is best", indent = 1),
                Block(type = BlockType.DIVIDER),
                Block(type = BlockType.IMAGE, image = ImageData(path = "media/a.png", aspect = 0.5f, caption = "The gate"), imageWidth = 0.5f),
                Block(text = "Dinner was great.", align = Align.CENTER, spans = listOf(StyleSpan(0, 6, InlineStyle.STRIKE))),
            ),
        )
        var sheet = SheetData()
        listOf("A1" to "Item", "B1" to "Cost", "A2" to "Rent", "B2" to "$1,450", "A3" to "Power", "B3" to "92.18", "A4" to "Total", "B4" to "=SUM(B2:B3)").forEach { (a, v) ->
            val (r, c) = parseAddr(a)!!
            sheet = SheetGrid.typeInto(sheet, r, c, v)
        }
        sheet = SheetGrid.setBorders(sheet, SheetArea(3, 0, 3, 1), SheetGrid.Borders.TOP).copy(table = "blue")
        return listOf(
            DocPart.Title("Kyoto"),
            DocPart.Rich(doc),
            DocPart.PageBreak,
            DocPart.Title("Budget"),
            DocPart.Table(sheet),
            DocPart.PageBreak,
            DocPart.Title("Mood board"),
            DocPart.Picture(DocPicture(png, "png", 80, 40)),
            DocPart.Text("A section page\nwith two lines"),
        )
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                out[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun writesAWellFormedDocument() {
        val bytes = DocxWriter.write(sampleParts()) { path -> if (path == "media/a.png") DocPicture(png, "png", 80, 40) else null }
        val files = entries(bytes)
        listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml", "word/_rels/document.xml.rels")
            .forEach { assertTrue(it, it in files) }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, data) ->
            factory.newDocumentBuilder().parse(ByteArrayInputStream(data)) // throws if malformed
            assertTrue(name, data.isNotEmpty())
        }
        assertEquals(2, files.keys.count { it.startsWith("word/media/") })
        val document = String(files["word/document.xml"]!!)
        assertTrue(document.contains("&lt;shrine&gt;"))
        assertTrue(document.contains("<w:tbl>"))
        assertTrue(document.contains("$1,450.00"))
        assertTrue(document.contains("☑ "))
        // A sample to open and look at (published with the test results).
        val out = listOf(File("build"), File("app/build")).firstOrNull { it.isDirectory }?.let { File(it, "test-results/testDebugUnitTest") }
        out?.let {
            it.mkdirs()
            File(it, "sample-export.docx").writeBytes(bytes)
        }
    }
}
