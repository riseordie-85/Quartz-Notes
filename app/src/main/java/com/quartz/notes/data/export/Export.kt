package com.quartz.notes.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.ShapeData
import com.quartz.notes.data.model.TextBoxData
import com.quartz.notes.data.model.decodeImageData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.isAttachedElement
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.SheetGrid
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The kinds of file notes, pages and notebooks can be exported as. */
enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    TEXT("txt", "text/plain", "Text"),
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    PDF("pdf", "application/pdf", "PDF"),
    PNG("png", "image/png", "PNG"),
    JPG("jpg", "image/jpeg", "JPG"),
    EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel"),
    CSV("csv", "text/csv", "CSV"),
    ;

    /** Made from a picture of each page. */
    val pictures: Boolean get() = this == PDF || this == PNG || this == JPG

    /** Several pages can go into one file. */
    val combines: Boolean get() = this == TEXT || this == WORD || this == PDF

    companion object {
        const val ZIP_MIME = "application/zip"
    }
}

/** File names: what a title becomes, kept apart from names already used. */
class FileNames {
    private val used = HashSet<String>()

    /** "Kyoto.png", then "Kyoto (2).png"; an empty title becomes [fallback]. */
    fun name(title: String, fallback: String, ext: String): String {
        val base = clean(title).ifEmpty { fallback }
        var name = "$base.$ext"
        var n = 2
        while (!used.add(name.lowercase())) name = "$base (${n++}).$ext"
        return name
    }

    companion object {
        private val BAD = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
        fun clean(title: String): String = title.replace(BAD, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(80).trim()
    }
}

/** Writes files into a zip. */
fun writeZip(out: OutputStream, files: List<Pair<String, File>>) {
    ZipOutputStream(out).use { zip ->
        files.forEach { (name, file) ->
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

/**
 * PDF documents from pictures of pages: each picture fills the width of an A4 page; a page-shaped
 * picture is one PDF page, a long one (a long text page) goes over as many A4 pages as it needs.
 */
object PdfMaker {
    private const val PAGE_W = 595
    private const val PAGE_H = 842

    fun write(pictures: List<File>, out: OutputStream) {
        val pdf = PdfDocument()
        var number = 1
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        try {
            for (file in pictures) {
                val bitmap = decode(file, 2400) ?: continue
                val scale = PAGE_W.toFloat() / bitmap.width
                val fullH = bitmap.height * scale
                if (fullH <= PAGE_H * 1.5f) {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, kotlin.math.ceil(fullH).toInt().coerceAtLeast(1), number++).create())
                    page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, PAGE_W.toFloat(), fullH), paint)
                    pdf.finishPage(page)
                } else {
                    // Slices of an A4 page's height.
                    val sliceSrc = (PAGE_H / scale).toInt().coerceAtLeast(1)
                    var top = 0
                    while (top < bitmap.height) {
                        val h = minOf(sliceSrc, bitmap.height - top)
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number++).create())
                        val c: Canvas = page.canvas
                        c.drawColor(Color.WHITE)
                        c.drawBitmap(bitmap, Rect(0, top, bitmap.width, top + h), RectF(0f, 0f, PAGE_W.toFloat(), h * scale), paint)
                        pdf.finishPage(page)
                        top += h
                    }
                }
                bitmap.recycle()
            }
            if (number == 1) {
                pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create()))
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    /** A picture file, shrunk to at most [maxWidth] pixels wide. */
    fun decode(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** A PNG picture re-saved as a JPEG (on white, as JPEGs have no see-through parts). */
fun pngToJpeg(png: File, out: OutputStream) {
    val bitmap = BitmapFactory.decodeFile(png.path) ?: error("Couldn't read the picture")
    val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    Canvas(flat).apply {
        drawColor(Color.WHITE)
        drawBitmap(bitmap, 0f, 0f, null)
    }
    flat.compress(Bitmap.CompressFormat.JPEG, 92, out)
    bitmap.recycle()
    flat.recycle()
}

/** A picture file for a document: its bytes, type and size. */
fun docPicture(file: File): DocPicture? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    val mime = bounds.outMimeType.orEmpty()
    return if (mime == "image/png" || mime == "image/jpeg") {
        DocPicture(file.readBytes(), if (mime == "image/png") "png" else "jpeg", bounds.outWidth, bounds.outHeight)
    } else {
        // Other kinds (WebP, HEIF…) go in as PNG.
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        DocPicture(bytes, "png", bitmap.width, bitmap.height).also { bitmap.recycle() }
    }
}

/** What a notebook page says, as text and as parts of a Word document. */
object PageContent {
    /** Pictures in the page's attachment strip (not placed on the page). */
    fun attachedPictures(elements: List<PageElementEntity>): List<String> = elements
        .filter { it.kind == ElementKind.IMAGE && isAttachedElement(it.kind, it.data) }
        .mapNotNull { decodeImageData(it.data)?.path }

    /** Text boxes and shape labels, top to bottom. */
    private fun itemTexts(elements: List<PageElementEntity>): List<RichDoc> = elements
        .filter { it.kind == ElementKind.TEXT || it.kind == ElementKind.SHAPE }
        .sortedWith(compareBy({ it.y }, { it.x }))
        .mapNotNull { e ->
            if (e.kind == ElementKind.TEXT) {
                RichDoc.decode(decodePayload<TextBoxData>(e.data)?.doc)
            } else {
                decodePayload<ShapeData>(e.data)?.text?.takeIf { it.isNotBlank() }?.let { RichDoc.fromPlainText(it) }
            }
        }

    fun text(page: PageEntity, elements: List<PageElementEntity>): String {
        val parts = ArrayList<String>()
        when {
            page.isSection -> parts += page.plainText
            page.layoutMode == "SHEET" -> parts += SheetGrid.plainText(SheetData.decode(page.sheet))
            else -> {
                if (page.layoutMode != "SCRAPBOOK") parts += docText(RichDoc.decode(page.content))
                itemTexts(elements).forEach { parts += docText(it) }
            }
        }
        val body = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        return listOf(page.title.trim(), body).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** Text of a document with bullets, checkboxes and dividers. */
    fun docText(doc: RichDoc): String = doc.blocks.joinToString("\n") { b ->
        val pad = "    ".repeat(b.indent.coerceIn(0, 8))
        when (b.type) {
            BlockType.BULLET -> "$pad• ${b.text}"
            BlockType.TASK -> pad + (if (b.checked) "☑ " else "☐ ") + b.text
            BlockType.DIVIDER -> "────────────"
            BlockType.IMAGE -> b.image?.caption.orEmpty()
            else -> pad + b.text
        }
    }.trimEnd()

    /**
     * The page as Word document parts. Freeform pages (and anything else that's more drawing than
     * words) come in as [picture], a picture of the page.
     */
    fun docParts(page: PageEntity, elements: List<PageElementEntity>, picture: DocPicture?): List<DocPart> {
        val parts = ArrayList<DocPart>()
        if (page.title.isNotBlank()) parts += DocPart.Title(page.title.trim())
        when {
            page.isSection -> if (page.plainText.isNotBlank()) parts += DocPart.Text(page.plainText)
            page.layoutMode == "SHEET" -> parts += DocPart.Table(SheetData.decode(page.sheet))
            page.layoutMode == "SCRAPBOOK" -> if (picture != null) parts += DocPart.Picture(picture)
            else -> {
                parts += DocPart.Rich(RichDoc.decode(page.content))
                itemTexts(elements).forEach { parts += DocPart.Rich(it) }
            }
        }
        return parts
    }
}

/** One page (or note) ready to export: its words, its parts for Word, a picture of it, its grid. */
class ExportPage(
    val title: String,
    /** The name used when there's no title ("Page 3"). */
    val fallback: String,
    val text: String,
    val parts: List<DocPart>,
    /** A PNG of the page, when the format needs one. */
    val picture: File?,
    /** Sheet pages: the grid. */
    val sheet: SheetData?,
    /** Pictures attached to it. */
    val attachments: List<File>,
)

/** Makes the export's file: one file, or a .zip of several. */
object ExportBuilder {
    /**
     * Writes [pages] as [format] into [dir]. [name] names a combined file or the .zip. Returns the
     * file and its type.
     */
    fun build(
        dir: File,
        name: String,
        pages: List<ExportPage>,
        format: ExportFormat,
        perPage: Boolean,
        attachments: Boolean,
        pictureOf: (String) -> DocPicture?,
    ): Pair<File, String> {
        val names = FileNames()
        val outputs = ArrayList<Pair<String, File>>()
        val one = pages.size == 1 || (format.combines && !perPage)
        val baseName = FileNames.clean(name).ifEmpty { "Export" }

        fun out(entryName: String): File = File(dir, entryName)

        when (format) {
            ExportFormat.TEXT -> if (one) {
                val n = names.name(baseName, "Export", "txt")
                out(n).writeText(pages.joinToString("\n\n\n") { it.text }.trim() + "\n")
                outputs += n to out(n)
            } else {
                pages.forEach { p ->
                    val n = names.name(p.title, p.fallback, "txt")
                    out(n).writeText(p.text.trim() + "\n")
                    outputs += n to out(n)
                }
            }
            ExportFormat.WORD -> if (one) {
                val n = names.name(baseName, "Export", "docx")
                val parts = pages.flatMapIndexed { i, p -> (if (i > 0) listOf(DocPart.PageBreak) else emptyList()) + p.parts }
                out(n).writeBytes(DocxWriter.write(parts, pictureOf))
                outputs += n to out(n)
            } else {
                pages.forEach { p ->
                    val n = names.name(p.title, p.fallback, "docx")
                    out(n).writeBytes(DocxWriter.write(p.parts, pictureOf))
                    outputs += n to out(n)
                }
            }
            ExportFormat.PDF -> if (one) {
                val n = names.name(baseName, "Export", "pdf")
                out(n).outputStream().use { PdfMaker.write(pages.mapNotNull { it.picture }, it) }
                outputs += n to out(n)
            } else {
                pages.forEach { p ->
                    val pic = p.picture ?: return@forEach
                    val n = names.name(p.title, p.fallback, "pdf")
                    out(n).outputStream().use { PdfMaker.write(listOf(pic), it) }
                    outputs += n to out(n)
                }
            }
            ExportFormat.PNG, ExportFormat.JPG -> pages.forEach { p ->
                val pic = p.picture ?: return@forEach
                val n = names.name(if (pages.size == 1) baseName else p.title, p.fallback, format.ext)
                if (format == ExportFormat.PNG) pic.copyTo(out(n), overwrite = true) else out(n).outputStream().use { pngToJpeg(pic, it) }
                outputs += n to out(n)
            }
            ExportFormat.EXCEL, ExportFormat.CSV -> pages.forEach { p ->
                val sheet = p.sheet ?: return@forEach
                val n = names.name(if (pages.size == 1) baseName else p.title, p.fallback, format.ext)
                if (format == ExportFormat.EXCEL) {
                    out(n).writeBytes(com.quartz.notes.data.sheet.SheetXlsx.write(sheet, p.title))
                } else {
                    out(n).writeText("﻿" + com.quartz.notes.data.sheet.SheetCsv.write(sheet))
                }
                outputs += n to out(n)
            }
        }
        if (attachments) {
            pages.forEach { p ->
                p.attachments.filter { it.isFile }.forEach { f ->
                    val n = names.name(p.title, p.fallback, f.extension.ifEmpty { "jpg" }.lowercase())
                    outputs += n to f
                }
            }
        }
        if (outputs.isEmpty()) throw IllegalStateException(
            if (format == ExportFormat.EXCEL || format == ExportFormat.CSV) "There are no sheet pages to export" else "There was nothing to export",
        )
        if (outputs.size == 1) {
            val (n, f) = outputs.single()
            val file = if (f.parentFile == dir) f else f.copyTo(out(n), overwrite = true)
            return file to format.mime
        }
        val zip = File(dir, "$baseName.zip")
        zip.outputStream().use { writeZip(it, outputs) }
        return zip to ExportFormat.ZIP_MIME
    }
}
